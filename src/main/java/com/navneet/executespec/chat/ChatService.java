package com.navneet.executespec.chat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptTrustBoundary;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.executioncontext.LlmDispatchPolicyException;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * V2 post-run chat copilot. Answers questions about a completed run's workspace using {@link
 * LlmRouter} with a small set of <b>read-only</b> tool callbacks ({@link RunChatTools}: {@code
 * list_run_files} / {@code read_run_file}) so the copilot can ground its answers + suggestions in
 * the actual generated code rather than the truncated file-tree snapshot. History is persisted as a
 * JSON array of {role,content} objects on {@code pipeline_run.chat_history_json} (intermediate
 * tool-call turns are NOT persisted — only the final user + assistant text).
 *
 * <p>Strictly advisory: the tools never write / shell / push, and there is no re-run tool. If the
 * user wants to edit code, they submit a new run (the UI's "Iterate as new run" / "Apply to Run"
 * paths), which keep their own gates. This service just lets them reason about the existing run.
 * (2026-05-26 — RL-2026-05-047: added the read-only run tools per the founder's "give suggestions"
 * ask; previously this ran with {@code toolsOverride(List.of())}.)
 */
@Slf4j
@Service
public class ChatService {

  /** Matches frontend {@code MAX_MESSAGES = 50} in RunConversation.tsx. */
  public static final int MAX_USER_MESSAGES = 50;

  /**
   * Upper bound on file tree lines included in the system prompt so we don't blow the context
   * window on big monorepo workspaces.
   */
  private static final int MAX_FILE_TREE_LINES = 120;

  /**
   * Visible-answer allowance for each bounded Ask call. Reasoning-capable providers count hidden
   * reasoning against maxTokens, so the old 1,200 ceiling could consume the full allowance and
   * return an empty answer even though the provider completed successfully.
   */
  static final int MAX_TOKENS_PER_CALL = 3000;

  /** Persist the full transcript, but never resend an unbounded transcript to a provider. */
  static final int MAX_HISTORY_MESSAGES_IN_PROMPT = 16;

  /** Character ceiling for prior persisted messages; current evidence/question are separate. */
  static final int MAX_HISTORY_CHARS_IN_PROMPT = 32_000;

  private final PipelineRunRepository runRepo;
  private final ModelResolver modelResolver;
  private final AgentProperties agentProps;
  private final ValidationReportRepository validationReportRepo;
  private final RunChatTools runChatTools;
  private final LlmExecutionPolicyFactory executionPolicyFactory;
  private final ContextSanitizer contextSanitizer;
  private final RunChatTurnExecutor turnExecutor;
  private static final ChatHistoryCodec HISTORY_CODEC = new ChatHistoryCodec();

  @Autowired
  public ChatService(
      PipelineRunRepository runRepo,
      LlmRouter router,
      ModelResolver modelResolver,
      AgentProperties agentProps,
      ValidationReportRepository validationReportRepo,
      RunChatTools runChatTools,
      LlmExecutionPolicyFactory executionPolicyFactory,
      ContextSanitizer contextSanitizer) {
    this.runRepo = runRepo;
    this.modelResolver = modelResolver;
    this.agentProps = agentProps;
    this.validationReportRepo = validationReportRepo;
    this.runChatTools = runChatTools;
    this.executionPolicyFactory = executionPolicyFactory;
    this.contextSanitizer = contextSanitizer;
    this.turnExecutor = new RunChatTurnExecutor(router);
  }

  /** Compatibility constructor for deterministic unit callers. */
  public ChatService(
      PipelineRunRepository runRepo,
      LlmRouter router,
      ModelResolver modelResolver,
      AgentProperties agentProps,
      ValidationReportRepository validationReportRepo,
      RunChatTools runChatTools) {
    this(
        runRepo,
        router,
        modelResolver,
        agentProps,
        validationReportRepo,
        runChatTools,
        null,
        new ContextSanitizer());
  }

  public record ChatMessage(String role, String content) {}

  public static final String OUTPUT_LIMIT_REASON = "PROVIDER_OUTPUT_LIMIT";

  private static final String OUTPUT_LIMIT_NOTICE =
      "\n\n"
          + "_(The provider stopped at its output limit, so this answer is incomplete. Ask a"
          + " narrower question or name the specific file or evidence to inspect, then retry.)_";

  /**
   * Additive completion contract for Ask. The text remains available for diagnosis, but callers can
   * no longer mistake a provider-limited prefix for a complete answer.
   */
  public record SendResult(
      String reply, int messageCount, int maxMessages, boolean complete, String incompleteReason) {}

  record ChatAnswer(String text, boolean complete, String incompleteReason) {
    static ChatAnswer complete(String text) {
      return new ChatAnswer(text, true, null);
    }

    static ChatAnswer outputLimited(String text) {
      return new ChatAnswer(text + OUTPUT_LIMIT_NOTICE, false, OUTPUT_LIMIT_REASON);
    }
  }

  /** Loaded history for the chat tab — empty list when no messages yet. */
  public List<ChatMessage> history(String runId) {
    PipelineRun run =
        runRepo
            .findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    return deserialize(run.getChatHistoryJson());
  }

  /**
   * Append a user message, call the LLM, append the reply, save. Throws if the run isn't terminal
   * (chat only makes sense after the run is done) or the message cap is hit.
   */
  public SendResult send(String runId, String userMessage) {
    if (userMessage == null || userMessage.isBlank()) {
      throw new IllegalArgumentException("message is required");
    }
    String safeUserMessage = contextSanitizer.sanitizeOutput(userMessage);
    PipelineRun run =
        runRepo
            .findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    if (!isTerminal(run.getStatus())) {
      throw new IllegalStateException(
          "Chat only available for terminal runs (current: " + run.getStatus() + ")");
    }

    List<ChatMessage> history = deserialize(run.getChatHistoryJson());
    long userCount = history.stream().filter(m -> "user".equals(m.role())).count();
    if (userCount >= MAX_USER_MESSAGES) {
      throw new IllegalStateException(
          "Max "
              + MAX_USER_MESSAGES
              + " chat messages per run exceeded. Submit a new run to continue.");
    }

    // A deterministic plan-contract rejection is already fully answered by server-owned run
    // evidence. Do not spend another provider call merely to paraphrase it: besides wasting
    // tokens, a model can reinterpret the umbrella PLANNER_UNAVAILABLE status as an outage even
    // when the exact persisted reason proves that provider calls succeeded and the contract gate
    // rejected their plan. Keep ordinary code questions on the grounded tool loop below.
    var deterministicAnswer = RunChatDeterministicAnswer.answer(run, safeUserMessage);
    if (deterministicAnswer.isPresent()) {
      String reply = contextSanitizer.sanitizeOutput(deterministicAnswer.get());
      history.add(new ChatMessage("user", safeUserMessage));
      history.add(new ChatMessage("assistant", reply));
      run.setChatHistoryJson(serialize(history));
      run.setChatMessageCount(history.size());
      runRepo.save(run);
      return new SendResult(reply, (int) userCount + 1, MAX_USER_MESSAGES, true, null);
    }

    // Resolve provider + model (LIGHTWEIGHT tier — chat is Q&A, not planning).
    LlmProvider provider;
    if (run.getAiProvider() == null || run.getAiProvider().isBlank()) {
      throw new IllegalStateException("Run provider is missing");
    }
    provider = LlmProvider.valueOf(run.getAiProvider().toUpperCase());
    ResolvedModel model;
    if (run.isRoutePinned()) {
      if (run.getRequestedProvider() == null
          || run.getRequestedModel() == null
          || run.getRequestedModel().isBlank()) {
        throw new IllegalStateException("Run has an incomplete exact route policy");
      }
      provider = LlmProvider.valueOf(run.getRequestedProvider().toUpperCase());
      model =
          ResolvedModel.builder()
              .requestedProvider(provider)
              .actualProvider(provider)
              .model(run.getRequestedModel())
              .agentTier(AgentTier.LIGHTWEIGHT)
              .fallback(false)
              .build();
    } else {
      model = modelResolver.resolve("ChatService", provider, AgentTier.LIGHTWEIGHT);
    }
    String providerName = model.getActualProvider().name().toLowerCase();

    Path workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    String systemPrompt = buildSystemPrompt();
    String runEvidence = buildRunEvidence(run, workspace);

    // Assemble message list: system + prior history + current user message.
    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(systemPrompt));
    messages.add(new UserMessage(runEvidence));
    for (ChatMessage m : boundedHistoryForPrompt(history)) {
      messages.add(
          "user".equals(m.role())
              ? new UserMessage(m.content())
              : new AssistantMessage(m.content()));
    }
    messages.add(new UserMessage(safeUserMessage));

    // Read-only run tools (list_run_files / read_run_file) let the copilot drill into the
    // actual generated code to ground a suggestion. ExecutionContext is run-scoped so
    // LlmRouter attributes token cost to this run.
    var executionReference =
        executionPolicyFactory == null
            ? null
            : executionPolicyFactory.forRunChatTurn(
                runId, "turn-" + history.size() + "-" + java.util.UUID.randomUUID());
    ExecutionContext ctx = new ExecutionContext(runId, "chat", workspace, executionReference);
    List<ToolCallback> tools = runChatTools.bind(new RunChatTools.ToolContext(runId, ctx));

    String reply;
    ChatAnswer answer;
    try {
      answer = runToolLoop(runId, providerName, model.getModel(), messages, ctx, tools);
      reply = answer.text();
      if (reply.isBlank())
        reply = "(The model returned an empty response. Try rephrasing the question.)";
      reply = contextSanitizer.sanitizeOutput(reply);
      reply = ChatEvidenceTruthPolicy.enforce(reply, findValidationReport(runId));
    } catch (LlmDispatchPolicyException e) {
      log.warn("ChatService policy rejected run={} reason={}", runId, e.reason());
      throw new IllegalStateException(
          e.reason() == LlmDispatchPolicyException.Reason.BUDGET_EXHAUSTED
              ? "This Ask turn could not start because its provider budget is unavailable."
              : "This Ask turn could not start because the run's provider route is unavailable.");
    } catch (org.springframework.web.server.ResponseStatusException e) {
      // Managed-AI admission uses an intentional HTTP status (normally 402). Preserve it for the
      // controller instead of turning a credit/policy refusal into a misleading generic 500.
      throw e;
    } catch (Exception e) {
      String safeFailure = contextSanitizer.sanitizeException(e);
      log.error(
          "ChatService send failed for run={} type={} detail={}",
          runId,
          e.getClass().getSimpleName(),
          safeFailure);
      throw new RuntimeException("Chat failed: " + safeFailure);
    }

    // Persist: append both messages + bump counter.
    history.add(new ChatMessage("user", safeUserMessage));
    history.add(new ChatMessage("assistant", reply));
    run.setChatHistoryJson(serialize(history));
    run.setChatMessageCount(history.size());
    runRepo.save(run);

    return new SendResult(
        reply,
        (int) userCount + 1,
        MAX_USER_MESSAGES,
        answer.complete(),
        answer.incompleteReason());
  }

  /**
   * Selects the newest prior messages within deterministic message and character ceilings.
   *
   * <p>The database remains the complete audit transcript. Only the provider request is windowed,
   * so a long-lived 50-turn chat cannot overflow a lightweight model's context merely because old
   * turns are still retained. If the oldest selected message crosses the remaining character
   * budget, only its head is retained with an explicit marker.
   */
  static List<ChatMessage> boundedHistoryForPrompt(List<ChatMessage> history) {
    return HISTORY_CODEC.boundedForPrompt(
        history, MAX_HISTORY_MESSAGES_IN_PROMPT, MAX_HISTORY_CHARS_IN_PROMPT);
  }

  ChatAnswer runToolLoop(
      String runId,
      String providerName,
      String modelName,
      List<Message> seed,
      ExecutionContext ctx,
      List<ToolCallback> tools) {
    return turnExecutor.execute(runId, providerName, modelName, seed, ctx, tools);
  }

  static boolean responseWasTruncated(ChatResponse response) {
    return RunChatTurnExecutor.responseWasTruncated(response);
  }

  private static boolean isTerminal(String status) {
    return "COMPLETE".equals(status)
        || "FAILED".equals(status)
        || "CANCELLED".equals(status)
        || "PARTIAL".equals(status);
  }

  String buildSystemPrompt() {
    return RunChatPrompt.build();
  }

  private String buildRunEvidence(PipelineRun run, Path workspace) {
    StringBuilder evidence = new StringBuilder(RunChatEvidence.render(run));

    String spec = run.getUserRequest();
    String boundedSpec =
        spec == null || spec.isBlank()
            ? "(empty)"
            : (spec.length() > 2000 ? spec.substring(0, 2000) + "…" : spec);
    evidence
        .append("\n")
        .append(PromptTrustBoundary.wrap("ORIGINAL_SPEC", boundedSpec))
        .append("\n\n")
        .append(PromptTrustBoundary.wrap("WORKSPACE_FILE_TREE", renderFileTree(workspace)))
        .append('\n');

    String reportBlock = renderValidationReport(run.getRunId());
    if (!reportBlock.isBlank()) {
      evidence
          .append("\n")
          .append(PromptTrustBoundary.wrap("VALIDATION_REPORT", reportBlock))
          .append('\n');
    }
    return evidence.toString();
  }

  /**
   * Render the validation report as a concise markdown section for the chat system prompt. Returns
   * the empty string when no report exists yet (run still in flight, or terminal but report not
   * computed). All score fields handled defensively — null-safe so a partially-populated report
   * still produces useful output.
   */
  String renderValidationReport(String runId) {
    return RunChatValidationEvidence.render(findValidationReport(runId));
  }

  private ValidationReport findValidationReport(String runId) {
    try {
      return validationReportRepo.findByRunId(runId).orElse(null);
    } catch (Exception e) {
      log.debug(
          "ChatService validation-report lookup failed for run={}: {}", runId, e.getMessage());
      return null;
    }
  }

  /** Snapshot the top level of the workspace — files first, then directories. */
  private String renderFileTree(Path root) {
    if (root == null || !Files.isDirectory(root)) return "(workspace missing)";
    try (Stream<Path> walk = Files.walk(root, 3)) {
      List<String> lines =
          walk.filter(p -> !p.equals(root))
              .filter(
                  p -> {
                    String n = p.getFileName().toString();
                    // Hide noise
                    return !n.equals(".git")
                        && !n.equals("node_modules")
                        && !n.equals("target")
                        && !n.equals(".agent-context")
                        && !n.equals("__pycache__")
                        && !n.startsWith(".");
                  })
              .sorted(Comparator.comparing(p -> root.relativize(p).toString()))
              .limit(MAX_FILE_TREE_LINES)
              .map(
                  p -> {
                    String rel = root.relativize(p).toString();
                    return Files.isDirectory(p) ? rel + "/" : rel;
                  })
              .toList();
      return lines.isEmpty() ? "(empty workspace)" : String.join("\n", lines);
    } catch (IOException e) {
      return "(walk failed: " + e.getMessage() + ")";
    }
  }

  private List<ChatMessage> deserialize(String json) {
    return HISTORY_CODEC.deserialize(json);
  }

  private String serialize(List<ChatMessage> history) {
    return HISTORY_CODEC.serialize(history);
  }
}
