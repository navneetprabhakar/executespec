package com.navneet.executespec.v2.chat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.v2.agent.LlmRequestV2;
import com.navneet.executespec.v2.agent.LlmRouterV2;
import com.navneet.executespec.v2.tool.ExecutionContext;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 post-run chat copilot. Answers questions about a completed run's workspace using {@link
 * LlmRouterV2} with no tool callbacks — read-only conversation, never modifies files. History is
 * persisted as a JSON array of {role,content} objects on {@code pipeline_run.chat_history_json}.
 *
 * <p>Intentionally minimal: no streaming, no tool calls, no multi-turn tool agent. If the user
 * wants to edit code, they submit a new run (the UI's "Iterate as new run" path — not yet wired on
 * V2). This service just lets them reason about the existing run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class V2ChatService {

  /** Matches frontend {@code MAX_MESSAGES = 50} in RunConversation.tsx. */
  public static final int MAX_USER_MESSAGES = 50;

  /**
   * Upper bound on file tree lines included in the system prompt so we don't blow the context
   * window on big monorepo workspaces.
   */
  private static final int MAX_FILE_TREE_LINES = 120;

  private final PipelineRunRepository runRepo;
  private final LlmRouterV2 router;
  private final ModelResolver modelResolver;
  private final AgentProperties agentProps;
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
      model = modelResolver.resolve("V2ChatService", provider, AgentTier.LIGHTWEIGHT);
    } catch (Exception e) {
      log.warn(
          "V2ChatService model resolve failed for provider={}: {} — falling back to ANTHROPIC",
          provider,
          e.getMessage());
      provider = LlmProvider.ANTHROPIC;
      model = modelResolver.resolve("V2ChatService", provider, AgentTier.LIGHTWEIGHT);
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

    // No tool callbacks — chat is read-only. ExecutionContext is synthesised
    // because LlmRequestV2 requires a non-null context even when tools are off.
    ExecutionContext ctx = new ExecutionContext(runId, "chat", workspace);
    LlmRequestV2 req =
        LlmRequestV2.builder()
            .provider(providerName)
            .model(model.getModel())
            .messages(messages)
            .context(ctx)
            .temperature(0.2) // tiny bit of variety for a chat voice
            .maxTokens(1200) // ~900-1000 word answer cap
            .toolsOverride(List.of()) // read-only: no file writes, no shell
            .build();

    String reply;
    try {
      ChatResponse resp = router.call(req);
      reply =
          resp.getResult() != null && resp.getResult().getOutput() != null
              ? String.valueOf(resp.getResult().getOutput().getText())
              : "";
      if (reply.isBlank())
        reply = "(The model returned an empty response. Try rephrasing the question.)";
    } catch (Exception e) {
      log.error("V2ChatService send failed for run={}: {}", runId, e.getMessage(), e);
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

  // ──────────────────────────────────────────────────────────────────────

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
    sb.append("suggest fixes when asked. You do NOT have file-edit or shell tools — your replies ");
    sb.append("are advisory. When suggesting code changes, output full file-diff blocks the user ");
    sb.append("can paste into a new run's spec.\n\n");

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

    sb.append("\n## Guardrails\n");
    sb.append("- Be concise. No emojis. No purple prose.\n");
    sb.append(
        "- When asked to 'fix' something, describe the fix and show the exact file + snippet.\n");
    sb.append("- If you're not sure, say so. Don't invent file paths or APIs.\n");
    return sb.toString();
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
      log.warn("V2ChatService failed to parse chat history — starting fresh: {}", e.getMessage());
      return new ArrayList<>();
    }
  }

  private String serialize(List<ChatMessage> history) {
    try {
      return mapper.writeValueAsString(history);
    } catch (Exception e) {
      log.warn("V2ChatService failed to serialize chat history: {}", e.getMessage());
      return "[]";
    }
  }
}
