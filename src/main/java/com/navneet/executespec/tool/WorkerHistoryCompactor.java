package com.navneet.executespec.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Worker conversation-history compaction (RL-2026-05-052 follow-up — the second half of the
 * context-discipline work; {@link ToolOutputCompactor} is the first half).
 *
 * <p>The V2 {@code WorkerAgent} loop keeps its FULL conversation history every turn (no sliding
 * window). On a long MEDIUM/HIGH task (75–120 iterations) the history grows unboundedly, so the
 * input the worker reasons over balloons: reasoning quality degrades as the model juggles a
 * 100k-token transcript, and the per-call input cost climbs (the implicit cache softens cost but
 * not window pressure). This compactor summarises the OLDEST turns into a single "progress so far"
 * note once the last call's input crosses a token threshold — keeping the system prompt + the
 * original task + the most-recent turns verbatim. Mirrors Claude Code's periodic auto-compaction.
 *
 * <p><b>Default OFF</b> behind {@link #FLAG_ENABLED}; with the flag off this is a pass-through and
 * production behaviour is byte-identical.
 *
 * <p><b>Tool-pairing safety (load-bearing):</b> the cut is aligned to an {@link AssistantMessage}
 * boundary, so the compactor NEVER splits an {@code AssistantMessage(toolCalls)} from its paired
 * {@link org.springframework.ai.chat.messages.ToolResponseMessage}. Anthropic + OpenAI both 400 on
 * a dangling tool_use/tool_result, so a wrong cut would break the very runs this is meant to help.
 * If no safe boundary exists in range, the history is returned unchanged (try again next threshold
 * cross). The rebuilt suffix therefore always reads {@code system → user(task+summary) → assistant
 * → toolResponse → …} — clean role alternation, every tool pair intact.
 *
 * <p><b>Fail-safe:</b> any error (resolve / assemble / LLM call / blank summary) logs warn and
 * returns the ORIGINAL history. The worker never loses its transcript to a compactor failure.
 *
 * <p>Cost is auto-recorded: {@link LlmRouter#call} invokes the {@code RunCostTracker} on every
 * successful call, so the compactor's LIGHTWEIGHT tokens are attributed to the run + task.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerHistoryCompactor {

  /**
   * Master kill switch. Default OFF — see {@code V222__seed_worker_history_compaction_flag.sql}.
   */
  public static final String FLAG_ENABLED = "worker.history-compaction.enabled";

  /**
   * Compact once the previous call's INPUT (prompt) tokens cross this. Conservative — ~30% of a
   * 200k window — so we only pay the compaction round-trip (+ the one-time cache miss its prefix
   * rewrite causes) on genuinely long tasks, not routine ones.
   */
  static final long TRIGGER_INPUT_TOKENS = 60_000L;

  /** Keep this many of the most-recent messages verbatim (the worker's live working set). */
  static final int KEEP_RECENT_MESSAGES = 8;

  /**
   * Keep this many leading messages verbatim before summarising: index 0 is the system prompt (the
   * cache prefix — must never change) and index 1 is the task user-prompt. We fold the task TEXT
   * into the summary message so the literal task survives even though the original message is
   * replaced.
   */
  static final int HEAD_KEEP = 2;

  static final int SUMMARY_MAX_TOKENS = 1200;
  static final int GOAL_TRUNCATE_CHARS = 800;

  /** Agent name registered in {@code application.yml agent-tier-map} → LIGHTWEIGHT. */
  static final String AGENT_NAME = "WorkerHistoryCompactor";

  private static final String SYSTEM_PROMPT =
      "You compress a coding agent's earlier conversation turns into a compact progress note,"
          + " preserving every decision, file touched, and unresolved problem the agent needs to"
          + " continue. Output ONLY the note — no preamble.";

  private final LlmRouter router;
  private final ModelResolver modelResolver;
  private final PromptAssembler promptAssembler;
  private final FeatureFlagService featureFlagService;

  /**
   * Window-aware trigger (harness-resolution task 9): the compaction threshold is {@code
   * WINDOW_TRIGGER_RATIO × the worker model's context window} instead of the DeepSeek-128k-era
   * static 60k (30% of a 200k window — TB review R2 measured the evidence loss of firing that
   * early). Nullable (legacy call sites / tests) ⇒ static {@link #TRIGGER_INPUT_TOKENS} fallback.
   */
  private final com.navneet.executespec.router.ModelRegistry modelRegistry;

  static final double WINDOW_TRIGGER_RATIO = 0.75;

  /** Recent-tail token budget as a fraction of the window (≈4 chars/token estimate). */
  static final double TAIL_BUDGET_RATIO = 0.25;

  /** Dossier budget for the window-aware path — 1,200 tokens destroyed evidence (TB R2). */
  static final int DOSSIER_MAX_TOKENS = 6_000;

  /**
   * Return a (possibly) compacted copy of {@code history}. Returns the SAME list reference when no
   * compaction happened (flag off / under threshold / no safe boundary / any failure) so the caller
   * can cheaply detect a no-op by identity.
   *
   * @param history the worker's live message list (system, task, then assistant/tool-response
   *     turns)
   * @param workerProvider the run's provider ({@code "anthropic" | "openai" | …}); the summariser
   *     call stays inside the run's provider boundary (BYOK key + cache affinity), same as {@link
   *     ToolOutputCompactor}. Null/blank/unknown → ANTHROPIC.
   * @param ctx worker execution context (run/task ids for cost attribution)
   * @param lastInputTokens prompt tokens the PREVIOUS LLM call reported — the trigger signal
   * @param workerGoal the task description, given to the summariser so it preserves task-relevant
   *     detail
   */
  public List<Message> compactIfNeeded(
      List<Message> history,
      String workerProvider,
      ExecutionContext ctx,
      long lastInputTokens,
      String workerGoal) {
    // Legacy signature — static trigger, fixed tail, 1,200-token summary (pre-task-9 behavior).
    if (!featureFlagService.isEnabled(FLAG_ENABLED)) {
      return history;
    }
    if (lastInputTokens < TRIGGER_INPUT_TOKENS) {
      return history;
    }
    return compact(history, workerProvider, ctx, lastInputTokens, workerGoal, false, 0);
  }

  /**
   * Window-aware variant (task 9): trigger at {@link #WINDOW_TRIGGER_RATIO} of the worker model's
   * context window, keep a token-budgeted recent tail, and produce the structured dossier (up to
   * {@link #DOSSIER_MAX_TOKENS} tokens).
   */
  public List<Message> compactIfNeeded(
      List<Message> history,
      String workerProvider,
      String workerModel,
      ExecutionContext ctx,
      long lastInputTokens,
      String workerGoal) {
    if (!featureFlagService.isEnabled(FLAG_ENABLED)) {
      return history;
    }
    long window = windowFor(workerModel);
    long trigger = window > 0 ? (long) (window * WINDOW_TRIGGER_RATIO) : TRIGGER_INPUT_TOKENS;
    if (lastInputTokens < trigger) {
      return history;
    }
    return compact(history, workerProvider, ctx, lastInputTokens, workerGoal, true, window);
  }

  /**
   * Emergency compaction for context-overflow recovery (task 9): bypasses BOTH the feature flag
   * and the token trigger — when the provider has already rejected the request for length, "the
   * flag is off" is not a reason to lose the run. Same fail-safe contract (any failure returns
   * the original list).
   */
  public List<Message> compactNow(
      List<Message> history, String workerProvider, String workerModel, ExecutionContext ctx,
      String workerGoal) {
    return compact(history, workerProvider, ctx, Long.MAX_VALUE, workerGoal, true, windowFor(workerModel));
  }

  private long windowFor(String workerModel) {
    try {
      return modelRegistry == null || workerModel == null
          ? 0
          : modelRegistry.contextLimitForOrPrefixFallback(workerModel);
    } catch (Exception e) {
      return 0;
    }
  }

  private List<Message> compact(
      List<Message> history,
      String workerProvider,
      ExecutionContext ctx,
      long lastInputTokens,
      String workerGoal,
      boolean windowAware,
      long window) {
    if (history == null || ctx == null) {
      return history;
    }
    final int n = history.size();
    // Need: HEAD_KEEP leading + at least one message to summarise + KEEP_RECENT trailing.
    if (n <= HEAD_KEEP + KEEP_RECENT_MESSAGES + 1) {
      return history;
    }
    if (!(history.get(0) instanceof SystemMessage)) {
      // Defensive: the worker always sets index 0 = system prompt. If the shape is unexpected
      // (e.g. an externally-assembled resume), don't risk a malformed rewrite.
      return history;
    }

    // Align the cut to an AssistantMessage boundary so we never split a tool_use/tool_result pair
    // (see class javadoc). Scan DOWN from the target so we summarise as much as is safe.
    // Window-aware path: keep as many recent messages as fit the tail token budget (>= the fixed
    // legacy 8), so big windows retain a large verbatim working set instead of a fixed 8 messages.
    int keepRecent = KEEP_RECENT_MESSAGES;
    if (windowAware && window > 0) {
      long tailCharBudget = (long) (window * TAIL_BUDGET_RATIO) * 4L; // ~4 chars/token
      long acc = 0;
      int count = 0;
      for (int i = n - 1; i > HEAD_KEEP; i--) {
        String t = renderMessages(history.subList(i, i + 1));
        acc += t.length();
        if (acc > tailCharBudget) break;
        count++;
      }
      keepRecent = Math.max(KEEP_RECENT_MESSAGES, count);
      if (n <= HEAD_KEEP + keepRecent + 1) {
        return history; // everything already fits the tail budget — nothing worth summarising
      }
    }
    final int targetCut = n - keepRecent;
    int cut = -1;
    for (int i = targetCut; i >= HEAD_KEEP + 1; i--) {
      if (history.get(i) instanceof AssistantMessage) {
        cut = i;
        break;
      }
    }
    if (cut < 0) {
      log.debug(
          "WorkerHistoryCompactor no safe AssistantMessage boundary in [{}, {}) — skip (run={})",
          HEAD_KEEP + 1,
          targetCut + 1,
          ctx.runId());
      return history;
    }

    List<Message> middle = history.subList(HEAD_KEEP, cut);
    if (middle.isEmpty()) {
      return history;
    }

    LlmProvider provider = resolveProvider(workerProvider);
    ResolvedModel resolved;
    try {
      resolved = modelResolver.resolve(AGENT_NAME, provider);
    } catch (Exception e) {
      log.warn(
          "WorkerHistoryCompactor model resolve failed (run={}): {} — history unchanged",
          ctx.runId(),
          e.getMessage());
      return history;
    }

    String taskText =
        (history.get(1) instanceof UserMessage tu) && tu.getText() != null ? tu.getText() : "";
    String middleText = renderMessages(middle);

    String prompt;
    try {
      prompt =
          promptAssembler.assemble(
              "worker-history-compactor.md",
              Map.of(
                  "WORKER_GOAL", truncate(workerGoal, GOAL_TRUNCATE_CHARS), "HISTORY", middleText));
    } catch (Exception e) {
      log.warn(
          "WorkerHistoryCompactor prompt assemble failed (run={}): {} — history unchanged",
          ctx.runId(),
          e.getMessage());
      return history;
    }

    try {
      ChatResponse resp =
          router.call(
              LlmRequest.builder()
                  .provider(resolved.providerName())
                  .model(resolved.getModel())
                  .messages(List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(prompt)))
                  .context(ctx)
                  .agentRole("WorkerHistoryCompactor")
                  .stepId("worker-history.compact")
                  .temperature(0.0)
                  .maxTokens(windowAware ? DOSSIER_MAX_TOKENS : SUMMARY_MAX_TOKENS)
                  .toolsOverride(Collections.emptyList())
                  .build());
      String summary = resp.getResult().getOutput().getText();
      if (summary == null || summary.isBlank()) {
        log.warn("WorkerHistoryCompactor empty summary (run={}) — history unchanged", ctx.runId());
        return history;
      }
      if (isLengthTruncated(resp)) {
        log.warn(
            "WorkerHistoryCompactor hit max-token finish (run={}) — history unchanged",
            ctx.runId());
        return history;
      }

      // Rebuild: system(0) + ONE user message folding the task + the compacted progress note +
      // the recent block (which starts at `cut`, an AssistantMessage). The single combined user
      // message avoids two consecutive user turns (Anthropic rejects those) while preserving the
      // literal task text.
      StringBuilder folded = new StringBuilder();
      if (!taskText.isBlank()) {
        folded.append(taskText).append("\n\n");
      }
      folded
          .append("--- PROGRESS SO FAR (")
          .append(middle.size())
          .append(" earlier turns compacted to save context) ---\n")
          .append(summary);

      List<Message> compacted = new ArrayList<>(n - middle.size() + 1);
      compacted.add(history.get(0));
      compacted.add(new UserMessage(folded.toString()));
      compacted.addAll(history.subList(cut, n));

      log.info(
          "WorkerHistoryCompactor OK run={} task={} {}msg → {}msg (summarised {} turns)"
              + " lastInTok={} model={}",
          ctx.runId(),
          ctx.taskId(),
          n,
          compacted.size(),
          middle.size(),
          lastInputTokens,
          resolved.getModel());
      return compacted;
    } catch (Exception e) {
      // Fail-safe: any LLM error → keep the full history. The worker never loses its transcript.
      log.warn(
          "WorkerHistoryCompactor LLM call failed (run={}): {} — history unchanged",
          ctx.runId(),
          e.getMessage());
      return history;
    }
  }

  /** Flatten a message block into a role-tagged transcript for the summariser prompt. */
  private static String renderMessages(List<Message> messages) {
    StringBuilder sb = new StringBuilder();
    for (Message m : messages) {
      String role;
      String text;
      if (m instanceof SystemMessage sm) {
        role = "SYSTEM";
        text = sm.getText();
      } else if (m instanceof UserMessage um) {
        role = "USER";
        text = um.getText();
      } else if (m instanceof AssistantMessage am) {
        role = "ASSISTANT";
        StringBuilder t = new StringBuilder(am.getText() == null ? "" : am.getText());
        if (am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
          for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
            t.append("\n[tool call] ").append(tc.name()).append(' ').append(tc.arguments());
          }
        }
        text = t.toString();
      } else if (m instanceof ToolResponseMessage trm) {
        // Tool outcomes — the findings the summary needs ("read X, build failed with Y").
        role = "TOOL";
        StringBuilder t = new StringBuilder();
        if (trm.getResponses() != null) {
          for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
            t.append('[').append(r.name()).append("] ").append(r.responseData()).append('\n');
          }
        }
        text = t.toString();
      } else {
        role = "OTHER";
        text = m.getText();
      }
      sb.append(role).append(": ").append(text == null ? "" : text).append("\n\n");
    }
    return sb.toString();
  }

  /** Same parse as {@link ToolOutputCompactor} — keep the summariser inside the run's provider. */
  private LlmProvider resolveProvider(String workerProvider) {
    if (workerProvider == null || workerProvider.isBlank()) {
      return LlmProvider.ANTHROPIC;
    }
    try {
      return LlmProvider.valueOf(workerProvider.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      log.warn(
          "WorkerHistoryCompactor unknown worker provider '{}' — defaulting LIGHTWEIGHT to"
              + " ANTHROPIC",
          workerProvider);
      return LlmProvider.ANTHROPIC;
    }
  }

  private static boolean isLengthTruncated(ChatResponse resp) {
    if (resp == null || resp.getResult() == null || resp.getResult().getMetadata() == null) {
      return false;
    }
    Object finish = resp.getResult().getMetadata().getFinishReason();
    return finish != null && "length".equalsIgnoreCase(String.valueOf(finish));
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }
}
