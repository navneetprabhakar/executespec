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
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
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
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
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
  private final ObjectMapper mapper = new ObjectMapper();

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
    try {
      provider =
          run.getAiProvider() == null
              ? LlmProvider.ANTHROPIC
              : LlmProvider.valueOf(run.getAiProvider().toUpperCase());
    } catch (IllegalArgumentException ex) {
      provider = LlmProvider.ANTHROPIC;
    }
    ResolvedModel model;
    try {
      model = modelResolver.resolve("ChatService", provider, AgentTier.LIGHTWEIGHT);
    } catch (Exception e) {
      log.warn(
          "ChatService model resolve failed for provider={}: {} — falling back to ANTHROPIC",
          provider,
          e.getMessage());
      provider = LlmProvider.ANTHROPIC;
      model = modelResolver.resolve("ChatService", provider, AgentTier.LIGHTWEIGHT);
    }
    String providerName = provider.name().toLowerCase();

    Path workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    String systemPrompt = buildSystemPrompt(run, workspace);

    // Assemble message list: system + prior history + current user message.
    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(systemPrompt));
    for (ChatMessage m : history) {
      messages.add(
          "user".equals(m.role())
              ? new UserMessage(m.content())
              : new AssistantMessage(m.content()));
    }
    messages.add(new UserMessage(userMessage));

    // Read-only run tools (list_run_files / read_run_file) let the copilot drill into the
    // actual generated code to ground a suggestion. ExecutionContext is run-scoped so
    // LlmRouter attributes token cost to this run.
    ExecutionContext ctx = new ExecutionContext(runId, "chat", workspace);
    List<ToolCallback> tools = runChatTools.bind(new RunChatTools.ToolContext(runId, ctx));

    String reply;
    try {
      reply = runToolLoop(runId, providerName, model.getModel(), messages, ctx, tools);
      if (reply.isBlank())
        reply = "(The model returned an empty response. Try rephrasing the question.)";
    } catch (Exception e) {
      log.error("ChatService send failed for run={}: {}", runId, e.getMessage(), e);
      throw new RuntimeException("Chat failed: " + e.getMessage(), e);
    }

    // Persist: append both messages + bump counter.
    history.add(new ChatMessage("user", userMessage));
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
   * Mirrors {@code ProjectChatService.send}. Returns only the model's accumulated narration text —
   * the intermediate tool turns are kept in the in-memory conversation but never persisted to chat
   * history.
   */
  private String runToolLoop(
      String runId,
      String providerName,
      String modelName,
      List<Message> seed,
      ExecutionContext ctx,
      List<ToolCallback> tools) {
    List<Message> conversation = new ArrayList<>(seed);
    StringBuilder finalText = new StringBuilder();

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
      if (turnText.length() > 0) {
        if (finalText.length() > 0) finalText.append("\n\n");
        finalText.append(turnText);
      }

      // No tool calls → final answer reached.
      if (calls.isEmpty()) break;

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

      if (round == MAX_TOOL_ROUNDS - 1) {
        log.warn("Run chat hit MAX_TOOL_ROUNDS={} for run {}", MAX_TOOL_ROUNDS, runId);
        if (finalText.length() > 0) finalText.append("\n\n");
        finalText.append(
            "_(I hit my tool-call limit for this turn. Ask me to continue if you want me to keep"
                + " going.)_");
      }
    }
    return finalText.toString();
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

  private String buildSystemPrompt(PipelineRun run, Path workspace) {
    StringBuilder sb = new StringBuilder();
    sb.append("You are a conversational assistant for a completed code-generation run. ");
    sb.append("Answer questions about the generated code, the plan, the build/test results, and ");
    sb.append("suggest fixes when asked.\n\n");

    sb.append("## Tools (read-only)\n");
    sb.append("You have three read-only tools to inspect the run's generated code:\n");
    sb.append("- `list_run_files` — the complete file manifest (the tree below is truncated).\n");
    sb.append(
        "- `read_run_file` — the full content of one file by relative path. Call it before "
            + "asserting what a file contains or suggesting a change to it, so your answer is "
            + "grounded in the real code rather than a guess.\n");
    sb.append(
        "- `search_run_files` — regex content search across the whole workspace (file:line "
            + "matches). Use it to locate where a symbol or string lives before reading a file, "
            + "instead of guessing paths.\n");
    sb.append(
        "You have NO file-edit, shell, push, or re-run tools — your replies are advisory only. "
            + "When suggesting code changes, output full file-diff blocks the user can apply via a "
            + "new run; do NOT claim you have changed anything.\n\n");

    sb.append("## Run\n");
    sb.append("- runId: ").append(run.getRunId()).append('\n');
    sb.append("- status: ").append(run.getStatus());
    if (run.getStatusReason() != null) sb.append(" (").append(run.getStatusReason()).append(')');
    sb.append('\n');
    if (run.getAiProvider() != null)
      sb.append("- provider: ").append(run.getAiProvider()).append('\n');

    sb.append("\n## Original spec\n");
    String spec = run.getUserRequest();
    if (spec != null && !spec.isBlank()) {
      sb.append(spec.length() > 2000 ? spec.substring(0, 2000) + "…" : spec).append('\n');
    } else {
      sb.append("(empty)\n");
    }

    sb.append("\n## Workspace file tree (top ").append(MAX_FILE_TREE_LINES).append(" entries)\n");
    sb.append(renderFileTree(workspace)).append('\n');

    // Fix F (run a076c876, 2026-05-03): inject the validation report so the chat copilot
    // can answer questions like "why is architecture 0?", "what tests failed?", "what are
    // the open issues?". Without this, the LLM only saw the runId / status / spec / file
    // tree — every score-related question was a hallucination from zero context.
    String reportBlock = renderValidationReport(run.getRunId());
    if (!reportBlock.isBlank()) {
      sb.append("\n").append(reportBlock);
    }

    sb.append("\n## Guardrails\n");
    sb.append("- Be concise. No emojis. No purple prose.\n");
    sb.append(
        "- When asked to 'fix' something, describe the fix and show the exact file + snippet.\n");
    sb.append("- If you're not sure, say so. Don't invent file paths or APIs.\n");
    sb.append(
        "- When asked about scores or issues, cite the Validation report block verbatim — do"
            + " NOT invent numbers or issue descriptions.\n");
    return sb.toString();
  }

  /**
   * Render the validation report as a concise markdown section for the chat system prompt. Returns
   * the empty string when no report exists yet (run still in flight, or terminal but report not
   * computed). All score fields handled defensively — null-safe so a partially-populated report
   * still produces useful output.
   */
  String renderValidationReport(String runId) {
    ValidationReport rpt;
    try {
      rpt = validationReportRepo.findByRunId(runId).orElse(null);
    } catch (Exception e) {
      log.debug(
          "ChatService validation-report lookup failed for run={}: {}", runId, e.getMessage());
      return "";
    }
    if (rpt == null) return "";

    StringBuilder sb = new StringBuilder("## Validation report\n");
    if (rpt.getVerifiedScore() != null) {
      sb.append("- verifiedScore: ").append(rpt.getVerifiedScore()).append("/100");
      sb.append(" (deterministic — build + tests + reviewer)");
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
