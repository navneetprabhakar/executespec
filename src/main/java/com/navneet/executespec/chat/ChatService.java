package com.navneet.executespec.chat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptTrustBoundary;
import com.navneet.executespec.agent.ToolProtocolLeakGuard;
import com.navneet.executespec.config.AgentProperties;
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
import org.springframework.ai.chat.messages.ToolResponseMessage;
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

  /** Hard cap on tool-execution rounds per chat turn — mirrors ProjectChatService. */
  private static final int MAX_TOOL_ROUNDS = 4;

  private final PipelineRunRepository runRepo;
  private final LlmRouter router;
  private final ModelResolver modelResolver;
  private final AgentProperties agentProps;
  private final ValidationReportRepository validationReportRepo;
  private final RunChatTools runChatTools;
  private final LlmExecutionPolicyFactory executionPolicyFactory;
  private final ContextSanitizer contextSanitizer;
  private final ObjectMapper mapper = new ObjectMapper();

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
    this.router = router;
    this.modelResolver = modelResolver;
    this.agentProps = agentProps;
    this.validationReportRepo = validationReportRepo;
    this.runChatTools = runChatTools;
    this.executionPolicyFactory = executionPolicyFactory;
    this.contextSanitizer = contextSanitizer;
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

  public record SendResult(String reply, int messageCount, int maxMessages) {}

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
    for (ChatMessage m : history) {
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
    try {
      reply = runToolLoop(runId, providerName, model.getModel(), messages, ctx, tools);
      if (reply.isBlank())
        reply = "(The model returned an empty response. Try rephrasing the question.)";
      reply = contextSanitizer.sanitizeOutput(reply);
      reply = ChatEvidenceTruthPolicy.enforce(reply, findValidationReport(runId));
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

    return new SendResult(reply, (int) userCount + 1, MAX_USER_MESSAGES);
  }

  /**
   * Manual tool-execution loop. {@link LlmRouter} sets {@code internalToolExecutionEnabled(false)}
   * (the V2 worker pattern handles its own loop), so we dispatch each {@code tool_use} block
   * through the matching read-only {@link ToolCallback}, append a {@link ToolResponseMessage}, and
   * re-call the LLM until it stops emitting tool calls (or we hit {@link #MAX_TOOL_ROUNDS}).
   * Mirrors {@code ProjectChatService.send}. Returns only the model's final answer — intermediate
   * tool narration is kept in the in-memory conversation but never persisted as the answer.
   */
  String runToolLoop(
      String runId,
      String providerName,
      String modelName,
      List<Message> seed,
      ExecutionContext ctx,
      List<ToolCallback> tools) {
    List<Message> conversation = new ArrayList<>(seed);

    for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
      LlmRequest req =
          LlmRequest.builder()
              .provider(providerName)
              .model(modelName)
              .messages(conversation)
              .context(ctx)
              .agentRole("RunChatAgent")
              .stepId("run-chat.round-" + (round + 1))
              .temperature(0.2) // tiny bit of variety for a chat voice
              .maxTokens(1200) // ~900-1000 word answer cap
              .toolsOverride(tools) // read-only: list_run_files / read_run_file only
              .build();

      ChatResponse resp = router.call(req);

      // Aggregate text + tool calls across generations (Spring AI normally returns one).
      StringBuilder turnText = new StringBuilder();
      List<AssistantMessage.ToolCall> calls = new ArrayList<>();
      for (var gen : resp.getResults()) {
        String t = gen.getOutput().getText();
        if (t != null && !t.isEmpty()) {
          if (turnText.length() > 0) turnText.append('\n');
          turnText.append(t);
        }
        if (gen.getOutput().getToolCalls() != null) {
          calls.addAll(gen.getOutput().getToolCalls());
        }
      }
      // Fail closed when a provider serializes its private tool protocol as assistant text.
      if (calls.isEmpty()) {
        if (!ToolProtocolLeakGuard.containsProtocolMarkup(turnText.toString())) {
          return turnText.toString();
        }
        log.warn("Run chat suppressed textual tool-protocol markup for run {}", runId);
        conversation.add(new AssistantMessage(turnText.toString()));
        conversation.add(
            new UserMessage(
                "Your previous response exposed internal tool-call markup instead of an answer. "
                    + "Do not call or describe tools. Answer the original question now using only "
                    + "the run metadata and validation evidence already supplied. If that evidence "
                    + "is insufficient, state the exact missing evidence plainly."));
        return synthesizeFinal(runId, providerName, modelName, conversation, ctx);
      }

      // Append the assistant turn (text + tool_calls), then dispatch each read-only tool.
      conversation.add(
          AssistantMessage.builder().content(turnText.toString()).toolCalls(calls).build());

      List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
      for (AssistantMessage.ToolCall tc : calls) {
        ToolCallback callback = findCallback(tools, tc.name());
        String envelope;
        if (callback == null) {
          envelope = "{\"ok\":false,\"message\":\"unknown tool '" + tc.name() + "'\"}";
          log.warn("Run chat: model called unknown tool '{}' for run {}", tc.name(), runId);
        } else {
          try {
            envelope = callback.call(tc.arguments());
          } catch (Exception ex) {
            log.warn(
                "Run chat tool '{}' threw {}: {}",
                tc.name(),
                ex.getClass().getSimpleName(),
                ex.getMessage());
            envelope = "{\"ok\":false,\"message\":\"" + ex.getClass().getSimpleName() + "\"}";
          }
        }
        toolResponses.add(
            new ToolResponseMessage.ToolResponse(
                tc.id() == null ? "tc-" + System.nanoTime() : tc.id(), tc.name(), envelope));
      }
      conversation.add(ToolResponseMessage.builder().responses(toolResponses).build());
    }

    // The model used every inspection round. Intermediate narration such as "let me inspect the
    // tests" is not an answer and must never be returned as if the turn completed. Make one final
    // synthesis call with tools removed, so it must answer from the evidence already in the
    // conversation. This call is bounded and cannot recurse into another tool loop.
    log.warn(
        "Run chat hit MAX_TOOL_ROUNDS={} for run {}; forcing synthesis", MAX_TOOL_ROUNDS, runId);
    conversation.add(
        new UserMessage(
            "Tool inspection is complete. Answer the user's original question now using only the"
                + " evidence already collected. Apply the evidence precedence and fact-versus-"
                + "hypothesis rules from the system prompt. Be specific and do not describe future"
                + " tool actions."));
    return synthesizeFinal(runId, providerName, modelName, conversation, ctx);
  }

  private String synthesizeFinal(
      String runId,
      String providerName,
      String modelName,
      List<Message> conversation,
      ExecutionContext ctx) {
    LlmRequest synthesis =
        LlmRequest.builder()
            .provider(providerName)
            .model(modelName)
            .messages(conversation)
            .context(ctx)
            .agentRole("RunChatAgent")
            .stepId("run-chat.synthesis")
            .temperature(0.2)
            // Reasoning models count hidden reasoning against this cap. At 1,200 tokens DeepSeek
            // exhausted the entire allowance in reasoning and returned an empty visible answer.
            // Keep this bounded, but leave enough room for both reasoning and a concise synthesis.
            .maxTokens(3000)
            .toolsOverride(List.of())
            .toolChoice("none")
            .build();
    ChatResponse finalResponse = router.call(synthesis);
    StringBuilder answer = new StringBuilder();
    for (var generation : finalResponse.getResults()) {
      String text = generation.getOutput().getText();
      if (text == null || text.isBlank()) continue;
      if (!answer.isEmpty()) answer.append('\n');
      answer.append(text);
    }
    if (answer.isEmpty() || ToolProtocolLeakGuard.containsProtocolMarkup(answer.toString())) {
      log.warn("Run chat synthesis produced no safe customer answer for run {}", runId);
      return "I could not produce a grounded answer from the available run evidence. No internal "
          + "tool output was used as a substitute for evidence.";
    }
    return answer.toString();
  }

  /**
   * Lookup a {@link ToolCallback} by tool name. Returns null when the model calls an unknown tool.
   */
  private static ToolCallback findCallback(List<ToolCallback> tools, String name) {
    if (name == null) return null;
    for (ToolCallback t : tools) {
      if (name.equals(t.getToolDefinition().name())) return t;
    }
    return null;
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
    StringBuilder evidence = new StringBuilder();
    evidence.append("## Run metadata\n");
    evidence.append("- runId: ").append(run.getRunId()).append('\n');
    evidence.append("- status: ").append(run.getStatus());
    if (run.getStatusReason() != null)
      evidence.append(" (").append(run.getStatusReason()).append(')');
    evidence.append('\n');
    if (run.getAiProvider() != null)
      evidence.append("- provider: ").append(run.getAiProvider()).append('\n');

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
    ValidationReport rpt = findValidationReport(runId);
    if (rpt == null) return "";

    StringBuilder sb = new StringBuilder("## Validation report\n");
    if (rpt.getVerifiedScore() != null) {
      sb.append("- verifiedScore: ").append(rpt.getVerifiedScore()).append("/100");
      sb.append(" — internal summary score, not independent proof");
      sb.append('\n');
    }
    if (rpt.getAccuracyScore() != null) {
      sb.append("- accuracyScore: ").append(rpt.getAccuracyScore()).append("/100");
      if (rpt.getAccuracyGrade() != null)
        sb.append(" (").append(rpt.getAccuracyGrade()).append(")");
      sb.append(" — LLM-assessed, treat as estimate\n");
    }
    if (rpt.getReviewerVerdict() != null) {
      sb.append("- reviewerVerdict: ").append(rpt.getReviewerVerdict()).append('\n');
    }
    sb.append("- finalBuildPassed: ").append(rpt.getFinalBuildPassed()).append('\n');
    if (rpt.getTestsTotal() != null && rpt.getTestsTotal() > 0) {
      sb.append("- tests: ").append(rpt.getTestsPassed()).append("/").append(rpt.getTestsTotal());
      sb.append('\n');
    }

    // Per-dimension scores (skip nulls).
    appendScoreLine(sb, "codeQuality", rpt.getCodeQualityScore());
    appendScoreLine(sb, "testCoverage", rpt.getTestCoverageScore());
    appendScoreLine(sb, "architecture", rpt.getArchitectureScore());
    appendScoreLine(sb, "security", rpt.getSecurityScore());
    appendScoreLine(sb, "performance", rpt.getPerformanceScore());
    appendScoreLine(sb, "designCompliance", rpt.getDesignComplianceScore());

    // Issues — capped at 8 most critical so the prompt stays bounded.
    String issuesJson = rpt.getIssuesJson();
    if (issuesJson != null && !issuesJson.isBlank() && !"[]".equals(issuesJson.trim())) {
      sb.append("\n### Issues caught (raw JSON, up to 8)\n");
      String capped = issuesJson;
      if (capped.length() > 4_000) {
        capped = capped.substring(0, 4_000) + "\n…(truncated)";
      }
      sb.append(capped).append('\n');
    }
    return sb.toString();
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

  private static void appendScoreLine(StringBuilder sb, String label, Integer score) {
    if (score != null) sb.append("- ").append(label).append(": ").append(score).append("/100\n");
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
    if (json == null || json.isBlank()) return new ArrayList<>();
    try {
      return mapper.readValue(json, new TypeReference<>() {});
    } catch (Exception e) {
      log.warn("ChatService failed to parse chat history — starting fresh: {}", e.getMessage());
      return new ArrayList<>();
    }
  }

  private String serialize(List<ChatMessage> history) {
    try {
      return mapper.writeValueAsString(history);
    } catch (Exception e) {
      log.warn("ChatService failed to serialize chat history: {}", e.getMessage());
      return "[]";
    }
  }
}
