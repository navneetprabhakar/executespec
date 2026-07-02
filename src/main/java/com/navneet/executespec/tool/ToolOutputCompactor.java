package com.navneet.executespec.tool;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 5+6 of the engine extraction (RL-2026-05-052). Compacts bulky worker tool output through
 * the LIGHTWEIGHT tier before it lands in the WORKHORSE worker's history.
 *
 * <p>Pattern mirrors Anthropic's Claude Code CLI: bulk reads / searches / build output flow through
 * a cheap model (Haiku / Flash) and only the summary feeds the decision context — keeps the
 * worker's effective context window long and its prompt-cache prefix stable (large variable tool
 * output is the dominant cache-miss source).
 *
 * <p><b>Default OFF</b> behind {@link #FLAG_ENABLED}. With the flag off, callers fall through to
 * the raw tool output verbatim — production behaviour byte-identical.
 *
 * <p>Skip rules (return {@link Optional#empty()}; caller uses raw output):
 *
 * <ul>
 *   <li>Feature flag {@code worker.tool-compactor.enabled} is OFF (master kill switch).
 *   <li>{@code isImmediatelyPriorToolCall} is true — preserve the most recent tool output verbatim
 *       (the worker may need it for the next decision; loss of fidelity here costs more than the
 *       context savings).
 *   <li>Tool is in {@link #SKIP_TOOLS} — exact file reads and write/git/complete outputs are
 *       already load-bearing; summarising them would lose precision (line numbers, diff hunks,
 *       commit shas, exact edit-match text).
 *   <li>Raw output is null or smaller than {@link #COMPACT_THRESHOLD_BYTES} (3 KB).
 * </ul>
 *
 * <p>Fail-safe — any LLM call exception logs warn and returns empty. The worker NEVER loses tool
 * output to a compactor failure; degradation is silent + back to raw.
 *
 * <p>Cost recording is automatic: {@link LlmRouter#call} invokes {@link
 * RunCostTracker#record(String, String, String, String, ChatResponse)} on every successful call, so
 * the compactor's tokens are attributed to the run + task without per-call plumbing.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ToolOutputCompactor {

  /** Master kill switch. Default OFF — see {@code V216__seed_worker_tool_compactor_flag.sql}. */
  public static final String FLAG_ENABLED = "worker.tool-compactor.enabled";

  /** Bytes threshold — outputs smaller than this aren't worth a round-trip. */
  static final int COMPACT_THRESHOLD_BYTES = 3072;

  /** Output cap for the compactor's summary. */
  static final int COMPACTOR_MAX_TOKENS = 1500;

  /** Maximum chars of the worker goal passed to the compactor prompt. */
  static final int GOAL_TRUNCATE_CHARS = 800;

  /** Agent name registered in {@code application.yml agent-tier-map} → LIGHTWEIGHT. */
  static final String AGENT_NAME = "ToolOutputCompactor";

  /**
   * Tools whose output is already short OR is the explicit signal the worker is acting on — never
   * compact these:
   *
   * <ul>
   *   <li>{@code readFile} — exact source text may be needed for subsequent {@code editFile}
   *       old-string matching; never paraphrase it.
   *   <li>{@code writeFile} / {@code editFile} — small ack envelopes; line-number anchors matter.
   *   <li>{@code gitCommit} — short commit-sha echo; denied to worker anyway (defensive).
   *   <li>{@code completeTask} — the termination signal; bypassing compaction defensively.
   *   <li>{@code gitDiff} / {@code gitStatus} — already actionable + tightly bounded by tool
   *       truncation.
   * </ul>
   */
  static final Set<String> SKIP_TOOLS =
      Set.of(
          "readFile",
          "read_file",
          "writeFile",
          "write_file",
          "editFile",
          "edit_file",
          "multiEdit",
          "multi_edit",
          "applyPatch",
          "apply_patch",
          "gitCommit",
          "git_commit",
          "completeTask",
          "complete_task",
          "gitDiff",
          "git_diff",
          "gitStatus",
          "git_status");

  private static final String SYSTEM_PROMPT =
      "You are a tool-output compactor. You summarise verbose tool output for a coding agent,"
          + " preserving every signal the agent needs to decide its next action. Output ONLY the"
          + " compacted text — no preamble, no commentary.";

  private final LlmRouter router;
  private final ModelResolver modelResolver;
  private final PromptAssembler promptAssembler;
  private final RunCostTracker costTracker;
  private final FeatureFlagService featureFlagService;

  /** Result of a successful compaction. Caller substitutes {@link #summary} for the raw output. */
  public record Compacted(
      String summary,
      int rawBytes,
      int compactedBytes,
      long tokensIn,
      long tokensOut,
      double costUsd) {}

  /**
   * Compact {@code rawOutput} if the configured skip rules don't apply. Returns {@link
   * Optional#empty()} when the caller should use the raw output instead.
   *
   * @param toolName name of the worker tool that produced the output (e.g. {@code readFile})
   * @param rawOutput the verbatim tool output (post-envelope content)
   * @param workerGoal the task description / current goal — gives the compactor context for what to
   *     preserve; pass through verbatim (truncated to {@link #GOAL_TRUNCATE_CHARS} internally)
   * @param isImmediatelyPriorToolCall whether this is the LAST tool call in the current request
   *     batch (preserves the most recent output verbatim)
   * @param workerCtx worker's execution context (used for run/task ids on the LLM call)
   * @param workerProvider the run's provider ({@code "anthropic" | "openai" | "gemini" | …}) — the
   *     compactor's LIGHTWEIGHT call MUST stay inside the run's provider boundary so a BYOK OpenAI
   *     / Gemini run never silently bills an Anthropic call (and the implicit-cache prefix stays
   *     provider-local). Null/blank/unknown → ANTHROPIC (platform default).
   */
  public Optional<Compacted> compactIfNeeded(
      String toolName,
      String rawOutput,
      String workerGoal,
      boolean isImmediatelyPriorToolCall,
      ExecutionContext workerCtx,
      String workerProvider) {
    if (!featureFlagService.isEnabled(FLAG_ENABLED)) {
      return Optional.empty();
    }
    if (isImmediatelyPriorToolCall) {
      return Optional.empty();
    }
    if (toolName == null || SKIP_TOOLS.contains(toolName)) {
      return Optional.empty();
    }
    if (rawOutput == null || rawOutput.length() < COMPACT_THRESHOLD_BYTES) {
      return Optional.empty();
    }
    if (workerCtx == null) {
      return Optional.empty();
    }

    // Provider for the LIGHTWEIGHT tier — the run's OWN provider, so the compactor stays inside the
    // run's provider boundary (BYOK key resolution + implicit-cache prefix affinity). A BYOK OpenAI
    // or Gemini run must compact via its own LIGHTWEIGHT model (gpt-*-mini / Flash), never silently
    // bill an Anthropic Haiku call. ModelResolver maps the provider → its LIGHTWEIGHT model.
    LlmProvider provider = resolveProvider(workerProvider);
    ResolvedModel resolved;
    try {
      resolved = modelResolver.resolve(AGENT_NAME, provider);
    } catch (Exception e) {
      log.warn(
          "ToolOutputCompactor model resolve failed (tool={}, runId={}): {} — using raw output",
          toolName,
          workerCtx.runId(),
          e.getMessage());
      return Optional.empty();
    }

    String prompt;
    try {
      prompt =
          promptAssembler.assemble(
              "tool-output-compactor.md",
              Map.of(
                  "TOOL_NAME",
                  toolName,
                  "RAW_BYTES",
                  String.valueOf(rawOutput.length()),
                  "WORKER_GOAL",
                  truncate(workerGoal, GOAL_TRUNCATE_CHARS),
                  "RAW_OUTPUT",
                  rawOutput));
    } catch (Exception e) {
      log.warn(
          "ToolOutputCompactor prompt assemble failed (tool={}): {} — using raw output",
          toolName,
          e.getMessage());
      return Optional.empty();
    }

    List<Message> messages = List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(prompt));

    try {
      ChatResponse resp =
          router.call(
              LlmRequest.builder()
                  .provider(resolved.providerName())
                  .model(resolved.getModel())
                  .messages(messages)
                  .context(workerCtx)
                  .agentRole("ToolOutputCompactor")
                  .stepId("tool-output.compact")
                  .temperature(0.0)
                  .maxTokens(COMPACTOR_MAX_TOKENS)
                  .toolsOverride(Collections.emptyList())
                  .build());

      String summary = resp.getResult().getOutput().getText();
      if (summary == null || summary.isBlank()) {
        log.warn(
            "ToolOutputCompactor empty body (tool={}, runId={}) — using raw output",
            toolName,
            workerCtx.runId());
        return Optional.empty();
      }
      if (isLengthTruncated(resp)) {
        log.warn(
            "ToolOutputCompactor hit max-token finish (tool={}, runId={}) — using raw output",
            toolName,
            workerCtx.runId());
        return Optional.empty();
      }
      String compactedText =
          "[compacted from " + rawOutput.length() + " bytes]\n" + summary.strip();
      if (compactedText.length() >= rawOutput.length()) {
        // Compactor produced something larger than the input — fall back to raw (no benefit).
        log.warn(
            "ToolOutputCompactor produced no shrink (tool={} raw={}B summary={}B) — using raw",
            toolName,
            rawOutput.length(),
            compactedText.length());
        return Optional.empty();
      }

      long inTok = 0L, outTok = 0L;
      if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
        Usage u = resp.getMetadata().getUsage();
        if (u.getPromptTokens() != null) inTok = u.getPromptTokens();
        if (u.getCompletionTokens() != null) outTok = u.getCompletionTokens();
      }
      double cost =
          costTracker.computeCost(
              resolved.providerName(), resolved.getModel(), (int) inTok, (int) outTok);

      log.info(
          "ToolOutputCompactor OK tool={} raw={}B → summary={}B ({}% shrink) tokens={}/{}"
              + " cost=${} model={} run={}",
          toolName,
          rawOutput.length(),
          compactedText.length(),
          (rawOutput.length() == 0 ? 0 : 100 - (compactedText.length() * 100 / rawOutput.length())),
          inTok,
          outTok,
          String.format(Locale.ROOT, "%.6f", cost),
          resolved.getModel(),
          workerCtx.runId());

      return Optional.of(
          new Compacted(
              compactedText, rawOutput.length(), compactedText.length(), inTok, outTok, cost));
    } catch (Exception e) {
      // Fail-safe: any LLM error → keep raw output. The worker NEVER loses tool output to a
      // compactor failure. LlmRouter already handles its own transient retry (RL-2026-05-029).
      log.warn(
          "ToolOutputCompactor LLM call failed (tool={}, runId={}): {} — using raw output",
          toolName,
          workerCtx.runId(),
          e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Map the run's provider string to the {@link LlmProvider} whose LIGHTWEIGHT row the compactor
   * should call. The worker already knows its provider and threads it through {@code
   * compactIfNeeded} (RL-2026-05-052 follow-up — replaces the earlier hard-coded ANTHROPIC, which
   * routed every OpenAI / Gemini / BYOK run's bulk-output compaction through Anthropic Haiku:
   * wrong-key on BYOK + a cross-provider cache miss). Parse mirrors {@code SequentialExecutor}'s
   * {@code LlmProvider.valueOf(provider.toUpperCase())}; null / blank / unknown → ANTHROPIC
   * (platform default) so a missing value degrades safely rather than throwing.
   */
  private LlmProvider resolveProvider(String workerProvider) {
    if (workerProvider == null || workerProvider.isBlank()) {
      return LlmProvider.ANTHROPIC;
    }
    try {
      return LlmProvider.valueOf(workerProvider.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      log.warn(
          "ToolOutputCompactor unknown worker provider '{}' — defaulting LIGHTWEIGHT tier to"
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
    if (s.length() <= max) return s;
    return s.substring(0, max) + "…";
  }
}
