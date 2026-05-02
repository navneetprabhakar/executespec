package com.navneet.executespec.memory;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.memory.entity.ProjectChatMessage;
import com.navneet.executespec.memory.repository.ProjectChatMessageRepository;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.v2.agent.LlmRequestV2;
import com.navneet.executespec.v2.agent.LlmRouterV2;
import com.navneet.executespec.v2.tool.ExecutionContext;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Project-level chat (v2.4.0). ENTERPRISE + MAX premium feature. Each turn feeds the compacted
 * project memory + latest arch doc as system context and dispatches through {@link LlmRouterV2} at
 * PLANNING tier using the project's configured provider. Read-only — no file tools, just talk over
 * project context. Unlimited turns; users bring their own tokens via project provider.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectChatService {

  /** Tail window of prior turns fed back into the LLM. Older turns persist in DB. */
  private static final int HISTORY_WINDOW = 20;

  private final ProjectRepository projectRepo;
  private final ProjectChatMessageRepository chatRepo;
  private final ProjectMemoryProvider memoryProvider;
  private final ProjectArchitectureService archService;
  private final com.navneet.executespec.project.rag.RetrievalService retrievalService;
  private final ModelResolver modelResolver;
  private final LlmRouterV2 router;
  private final com.navneet.executespec.memory.chattools.ProjectChatTools projectChatTools;

  public List<ProjectChatMessage> history(String projectId) {
    return chatRepo.findByProjectIdOrderByCreatedAtAsc(projectId);
  }

  /**
   * v2.6.1 — wipe every persisted chat message for the project. Used when the assistant starts
   * hallucinating from a long history and the user wants to start fresh. Project memory +
   * architecture are NOT touched — those are what the new conversation will draw from.
   *
   * @return number of rows deleted
   */
  @Transactional
  public int clearHistory(String projectId) {
    if (projectId == null || projectId.isBlank()) return 0;
    int deleted = chatRepo.deleteByProjectId(projectId);
    log.info("ProjectChatService cleared {} chat messages for project={}", deleted, projectId);
    return deleted;
  }

  /** Number of recent messages we keep verbatim during a compaction. */
  private static final int COMPACT_KEEP_TAIL = 6;

  /** Minimum total messages required for a compaction to do anything. */
  private static final int COMPACT_MIN_TOTAL = COMPACT_KEEP_TAIL + 4;

  public record CompactResult(int compactedFrom, int kept, int totalAfter) {}

  /**
   * v2.6.1 — compact older chat messages into a single ASSISTANT-role summary, keeping the most
   * recent {@value #COMPACT_KEEP_TAIL} messages verbatim. Use when the conversation has grown past
   * the planner's effective context window and answers start drifting. Project memory +
   * architecture are untouched; only the chat log is rewritten.
   *
   * <p>No-op when the conversation has fewer than {@value #COMPACT_MIN_TOTAL} messages — there's
   * nothing meaningful to compact yet, so the caller gets a 409 (handled in the controller) rather
   * than wasting an LLM call.
   */
  @Transactional
  public CompactResult compactHistory(String projectId) {
    if (projectId == null || projectId.isBlank()) {
      throw new IllegalArgumentException("projectId required");
    }
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project"));

    List<ProjectChatMessage> messages = chatRepo.findByProjectIdOrderByCreatedAtAsc(projectId);
    if (messages.size() < COMPACT_MIN_TOTAL) {
      throw new IllegalStateException(
          "Not enough history to compact (need ≥ "
              + COMPACT_MIN_TOTAL
              + ", have "
              + messages.size()
              + ")");
    }

    int splitAt = messages.size() - COMPACT_KEEP_TAIL;
    List<ProjectChatMessage> toCompact = messages.subList(0, splitAt);
    List<ProjectChatMessage> toKeep = messages.subList(splitAt, messages.size());

    StringBuilder transcript = new StringBuilder();
    for (ProjectChatMessage m : toCompact) {
      transcript.append(m.getRole()).append(": ").append(m.getContent()).append("\n\n");
    }

    String system =
        "You are summarising the early portion of a project chat so the assistant "
            + "can keep the conversation grounded without re-reading every message.\n\n"
            + "Rules:\n"
            + "- Output a single ASSISTANT-voice summary in 2-4 paragraphs of natural prose.\n"
            + "- Lead with WHAT WAS DECIDED (concrete features, libraries, file paths, names).\n"
            + "- Then WHAT WAS RULED OUT and why.\n"
            + "- Then OPEN QUESTIONS the user has flagged but not resolved.\n"
            + "- Preserve every concrete identifier (endpoint paths, table names, env vars, "
            + "  file paths, library names). These are the anchors future answers will cite.\n"
            + "- Drop chit-chat, restatement, and meta-commentary.\n"
            + "- Begin with a single line marker: '[Summary of earlier conversation]' so the "
            + "  user can see at a glance which message is the compaction.";

    LlmProvider provider =
        project.getAiProvider() != null ? project.getAiProvider() : LlmProvider.OPENAI;

    String summary;
    try {
      ResolvedModel resolved =
          modelResolver.resolve("ProjectChatCompactor", provider, AgentTier.LIGHTWEIGHT);
      ExecutionContext ctx =
          new ExecutionContext(
              "chat-compact-" + projectId,
              "__project_chat_compact__",
              Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath());
      ChatResponse resp =
          router.call(
              LlmRequestV2.builder()
                  .provider(resolved.getActualProvider().name().toLowerCase())
                  .model(resolved.getModel())
                  .messages(
                      List.of(
                          new SystemMessage(system),
                          new UserMessage("Transcript:\n\n" + transcript)))
                  .context(ctx)
                  .temperature(0.2)
                  .maxTokens(1500)
                  .toolsOverride(Collections.emptyList())
                  .build());
      String out = resp.getResult().getOutput().getText();
      summary = (out == null || out.isBlank()) ? null : out.trim();
    } catch (Exception e) {
      log.warn("compactHistory LLM failed for project {}: {}", projectId, e.getMessage());
      summary = null;
    }

    if (summary == null) {
      // Deterministic fallback — concatenate first/last user messages so the
      // summary still carries the strongest anchors when the LLM call dies.
      StringBuilder fb = new StringBuilder("[Summary of earlier conversation]\n\n");
      ProjectChatMessage firstUser =
          toCompact.stream().filter(m -> "USER".equals(m.getRole())).findFirst().orElse(null);
      ProjectChatMessage lastUser = null;
      for (ProjectChatMessage m : toCompact) {
        if ("USER".equals(m.getRole())) lastUser = m;
      }
      if (firstUser != null)
        fb.append("First ask: ").append(firstUser.getContent().trim()).append("\n\n");
      if (lastUser != null && lastUser != firstUser)
        fb.append("Most recent ask: ").append(lastUser.getContent().trim()).append("\n\n");
      fb.append("(LLM summary unavailable — showing first/last user asks for context.)");
      summary = fb.toString();
    }

    // Atomic swap: delete the compacted range, insert one summary message,
    // keep the tail untouched. All inside the @Transactional boundary so a
    // crash mid-write doesn't leave the chat in a half-compacted state.
    chatRepo.deleteAll(toCompact);
    chatRepo.flush();
    ProjectChatMessage summaryMsg =
        ProjectChatMessage.builder()
            .projectId(projectId)
            .orgId(project.getOrgId())
            .userId(toCompact.get(toCompact.size() - 1).getUserId())
            .role("ASSISTANT")
            .content(summary)
            .build();
    chatRepo.save(summaryMsg);

    int totalAfter = 1 + toKeep.size();
    log.info(
        "ProjectChatService compacted project={} compactedFrom={} kept={} totalAfter={}",
        projectId,
        toCompact.size(),
        toKeep.size(),
        totalAfter);
    return new CompactResult(toCompact.size(), toKeep.size(), totalAfter);
  }

  /**
   * Distils the project chat history into a single-paragraph execution spec ready to submit as a
   * new project run. Uses the LIGHTWEIGHT tier + falls back to a deterministic concatenation if the
   * LLM call errors.
   */
  public String compileToSpec(String projectId) {
    List<ProjectChatMessage> messages = chatRepo.findByProjectIdOrderByCreatedAtAsc(projectId);
    if (messages.isEmpty()) return "";
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project"));

    StringBuilder transcript = new StringBuilder();
    int from = Math.max(0, messages.size() - 30);
    for (int i = from; i < messages.size(); i++) {
      ProjectChatMessage m = messages.get(i);
      transcript.append(m.getRole()).append(": ").append(m.getContent()).append("\n\n");
    }

    String system =
        "You are compiling a project chat transcript into a single concrete "
            + "execution spec the ExecuteSpec planner will consume.\n\n"
            + "Rules:\n"
            + "- Produce 1-2 paragraphs, imperative voice, no bullet lists.\n"
            + "- Keep ONLY what the user has decided to build. Drop background "
            + "explanations, trade-off discussion, and anything the user rejected.\n"
            + "- Preserve concrete specifics: endpoint names, file paths, libraries, "
            + "acceptance criteria.\n"
            + "- If multiple features were discussed, focus on the most recently "
            + "agreed-on one unless the user explicitly asked to bundle them.\n"
            + "- Output the spec ONLY — no markdown heading, no preamble, no trailing "
            + "commentary.";

    try {
      LlmProvider provider =
          project.getAiProvider() != null ? project.getAiProvider() : LlmProvider.OPENAI;
      ResolvedModel resolved =
          modelResolver.resolve("ProjectChatCompiler", provider, AgentTier.PLANNING);
      ExecutionContext ctx =
          new ExecutionContext(
              "chat-compile-" + projectId,
              "__project_chat_compile__",
              java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath());
      ChatResponse resp =
          router.call(
              LlmRequestV2.builder()
                  .provider(resolved.getActualProvider().name().toLowerCase())
                  .model(resolved.getModel())
                  .messages(
                      List.of(
                          new SystemMessage(system),
                          new UserMessage("Transcript:\n\n" + transcript)))
                  .context(ctx)
                  .temperature(0.2)
                  .maxTokens(800)
                  .toolsOverride(java.util.Collections.emptyList())
                  .build());
      String out = resp.getResult().getOutput().getText();
      if (out != null && !out.isBlank()) return out.trim();
    } catch (Exception e) {
      log.warn("compileToSpec LLM failed for project {}: {}", projectId, e.getMessage());
    }
    // Deterministic fallback — concatenate the last user message + last assistant.
    ProjectChatMessage lastUser = null, lastAsst = null;
    for (ProjectChatMessage m : messages) {
      if ("USER".equals(m.getRole())) lastUser = m;
      else lastAsst = m;
    }
    StringBuilder fb = new StringBuilder();
    if (lastUser != null) fb.append(lastUser.getContent().trim());
    if (lastAsst != null) fb.append("\n\nAssistant's take: ").append(lastAsst.getContent().trim());
    return fb.toString();
  }

  /**
   * V82 contract — chat without project-action tools. Kept so any non-controller caller (tests,
   * scripts) doesn't need a PAT.
   */
  @Transactional
  public ProjectChatMessage send(String projectId, String orgId, String userId, String userText) {
    return send(projectId, orgId, userId, userText, null);
  }

  /**
   * V169 (2026-05-02) — chat with project-action tools enabled. The caller's GitHub PAT is
   * threaded into tools that hit private repos (regenerate_architecture). When {@code callerPat}
   * is null, those tools still bind but return a clear error if the project's repos are private.
   */
  @Transactional
  public ProjectChatMessage send(
      String projectId, String orgId, String userId, String userText, String callerPat) {
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project"));

    ProjectChatMessage userMsg =
        ProjectChatMessage.builder()
            .projectId(projectId)
            .orgId(orgId)
            .userId(userId)
            .role("USER")
            .content(userText)
            .build();
    chatRepo.save(userMsg);

    List<Message> messages = buildMessages(project);

    LlmProvider provider =
        project.getAiProvider() != null ? project.getAiProvider() : LlmProvider.ANTHROPIC;
    ResolvedModel resolved =
        modelResolver.resolve("ProjectChatAgent", provider, AgentTier.PLANNING);

    ExecutionContext ctx =
        new ExecutionContext(
            "chat-" + projectId,
            "__project_chat__",
            Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath());

    // V169 — bind project-action tools for this turn. Long-running tools
    // (regenerate_architecture, generate_project_doc) trade chat latency
    // for the ability to actually act on the project from inside chat.
    var tools =
        projectChatTools.bind(
            new com.navneet.executespec.memory.chattools.ProjectChatTools.ToolContext(
                projectId, userId, callerPat));

    String assistantText;
    Integer tokensIn = null, tokensOut = null;
    try {
      ChatResponse resp =
          router.call(
              LlmRequestV2.builder()
                  .provider(resolved.getActualProvider().name().toLowerCase())
                  .model(resolved.getModel())
                  .messages(messages)
                  .context(ctx)
                  .temperature(0.3)
                  .maxTokens(2048)
                  .toolsOverride(tools)
                  .build());
      assistantText = resp.getResult().getOutput().getText();
      if (assistantText == null) assistantText = "(no response)";
      Usage usage = resp.getMetadata() != null ? resp.getMetadata().getUsage() : null;
      if (usage != null) {
        if (usage.getPromptTokens() != null) tokensIn = usage.getPromptTokens().intValue();
        if (usage.getCompletionTokens() != null) tokensOut = usage.getCompletionTokens().intValue();
      }
    } catch (RuntimeException e) {
      log.warn("Project chat LLM call failed for project {}: {}", projectId, e.getMessage());
      assistantText = "Sorry — chat failed: " + e.getMessage();
    }

    ProjectChatMessage assistantMsg =
        ProjectChatMessage.builder()
            .projectId(projectId)
            .orgId(orgId)
            .userId(userId)
            .role("ASSISTANT")
            .content(assistantText)
            .tokensIn(tokensIn)
            .tokensOut(tokensOut)
            .build();
    chatRepo.save(assistantMsg);
    return assistantMsg;
  }

  private List<Message> buildMessages(Project project) {
    StringBuilder sys = new StringBuilder();
    sys.append("You are the ExecuteSpec project assistant. Answer the user's questions ")
        .append("about project '")
        .append(project.getName())
        .append("' strictly using the context ")
        .append("below. Be concise and direct. If the answer is not in the context, say so ")
        .append("plainly — do not fabricate.\n\n");

    // V169 (2026-05-02) — surface project-action tools to the model. The
    // body restates intent rather than enumerating schemas (the runtime
    // already exposes those via the ToolDefinition list); this paragraph
    // exists so the model knows when tool calls are appropriate vs when
    // to answer from context.
    sys.append("## TOOLS\n")
        .append("You can call project-action tools when the user asks you to DO something on the ")
        .append("project. Examples that should trigger a tool call rather than a text answer:\n")
        .append("- \"regenerate / refresh / update the architecture\" → call regenerate_architecture\n")
        .append("- \"generate / update the explanation doc\" → call generate_project_doc with docType=EXPLANATION\n")
        .append("- \"generate / update the setup doc\" → call generate_project_doc with docType=SETUP\n")
        .append("- \"what repos are linked / show me the repos\" → call list_repos\n")
        .append("- \"recent runs / what has shipped lately\" → call list_recent_runs\n")
        .append("- \"open PRs / merged PRs / pull requests\" → call list_pull_requests\n")
        .append("- \"what does the project remember / project history\" → call get_project_memory\n")
        .append("- \"explain the project's architecture / data flow / module boundaries\" → call get_latest_architecture\n")
        .append("After a write tool returns, briefly narrate what changed (1-2 sentences) so the ")
        .append("user knows where to look in the dashboard. After a read tool, summarise the ")
        .append("relevant rows — don't dump the JSON envelope verbatim. If a tool returns ")
        .append("ok=false, surface the message field and suggest the next step.\n\n")
        .append("Settings (GitHub PAT, AI provider keys, billing) are deliberately NOT exposed ")
        .append("as tools. If the user asks you to update those, point them to /settings/manage.\n\n");

    String memory = memoryProvider.buildPlannerContext(project.getId());
    if (memory != null && !memory.isBlank()) {
      sys.append("## PROJECT MEMORY\n").append(memory).append("\n\n");
    }
    try {
      String arch = archService.latest(project.getId()).map(d -> d.getContent()).orElse(null);
      if (arch != null && !arch.isBlank()) {
        sys.append("## ARCHITECTURE DOCUMENT\n").append(arch).append("\n");
      }
    } catch (Exception ignored) {
      /* arch is optional */
    }

    List<ProjectChatMessage> prior = chatRepo.findByProjectIdOrderByCreatedAtAsc(project.getId());
    // Use the most recent user turn as the retrieval query so REFERENCE DOCS
    // are picked relative to whatever the user just asked.
    String retrievalQuery =
        prior.stream()
            .filter(m -> "USER".equals(m.getRole()))
            .reduce((a, b) -> b)
            .map(ProjectChatMessage::getContent)
            .orElse("");
    if (!retrievalQuery.isBlank()) {
      try {
        String refs = retrievalService.retrieve(project.getId(), retrievalQuery);
        if (refs != null && !refs.isBlank()) sys.append(refs).append("\n");
      } catch (Exception ignored) {
      }
    }

    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(sys.toString()));

    int from = Math.max(0, prior.size() - HISTORY_WINDOW);
    for (int i = from; i < prior.size(); i++) {
      ProjectChatMessage m = prior.get(i);
      if ("USER".equals(m.getRole())) messages.add(new UserMessage(m.getContent()));
      else messages.add(new AssistantMessage(m.getContent()));
    }
    return messages;
  }
}
