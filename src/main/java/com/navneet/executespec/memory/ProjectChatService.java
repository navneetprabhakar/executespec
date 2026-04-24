package com.navneet.executespec.memory;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Project-level chat (v2.4.0). ENTERPRISE + MAX premium feature. Each turn feeds
 * the compacted project memory + latest arch doc as system context and dispatches
 * through {@link LlmRouterV2} at PLANNING tier using the project's configured
 * provider. Read-only — no file tools, just talk over project context. Unlimited
 * turns; users bring their own tokens via project provider.
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

    public List<ProjectChatMessage> history(String projectId) {
        return chatRepo.findByProjectIdOrderByCreatedAtAsc(projectId);
    }

    /**
     * Distils the project chat history into a single-paragraph execution spec
     * ready to submit as a new project run. Uses the LIGHTWEIGHT tier + falls
     * back to a deterministic concatenation if the LLM call errors.
     */
    public String compileToSpec(String projectId) {
        List<ProjectChatMessage> messages = chatRepo.findByProjectIdOrderByCreatedAtAsc(projectId);
        if (messages.isEmpty()) return "";
        Project project = projectRepo.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project"));

        StringBuilder transcript = new StringBuilder();
        int from = Math.max(0, messages.size() - 30);
        for (int i = from; i < messages.size(); i++) {
            ProjectChatMessage m = messages.get(i);
            transcript.append(m.getRole()).append(": ").append(m.getContent()).append("\n\n");
        }

        String system =
                "You are compiling a project chat transcript into a single concrete " +
                "execution spec the ExecuteSpec planner will consume.\n\n" +
                "Rules:\n" +
                "- Produce 1-2 paragraphs, imperative voice, no bullet lists.\n" +
                "- Keep ONLY what the user has decided to build. Drop background " +
                "explanations, trade-off discussion, and anything the user rejected.\n" +
                "- Preserve concrete specifics: endpoint names, file paths, libraries, " +
                "acceptance criteria.\n" +
                "- If multiple features were discussed, focus on the most recently " +
                "agreed-on one unless the user explicitly asked to bundle them.\n" +
                "- Output the spec ONLY — no markdown heading, no preamble, no trailing " +
                "commentary.";

        try {
            LlmProvider provider = project.getAiProvider() != null
                    ? project.getAiProvider() : LlmProvider.OPENAI;
            ResolvedModel resolved = modelResolver.resolve(
                    "ProjectChatCompiler", provider, AgentTier.PLANNING);
            ExecutionContext ctx = new ExecutionContext(
                    "chat-compile-" + projectId, "__project_chat_compile__",
                    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath());
            ChatResponse resp = router.call(LlmRequestV2.builder()
                    .provider(resolved.getActualProvider().name().toLowerCase())
                    .model(resolved.getModel())
                    .messages(List.of(
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

    @Transactional
    public ProjectChatMessage send(String projectId, String orgId, String userId, String userText) {
        Project project = projectRepo.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project"));

        ProjectChatMessage userMsg = ProjectChatMessage.builder()
                .projectId(projectId).orgId(orgId).userId(userId)
                .role("USER").content(userText)
                .build();
        chatRepo.save(userMsg);

        List<Message> messages = buildMessages(project);

        LlmProvider provider = project.getAiProvider() != null
                ? project.getAiProvider() : LlmProvider.ANTHROPIC;
        ResolvedModel resolved = modelResolver.resolve(
                "ProjectChatAgent", provider, AgentTier.PLANNING);

        ExecutionContext ctx = new ExecutionContext(
                "chat-" + projectId, "__project_chat__",
                Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath());

        String assistantText;
        Integer tokensIn = null, tokensOut = null;
        try {
            ChatResponse resp = router.call(LlmRequestV2.builder()
                    .provider(resolved.getActualProvider().name().toLowerCase())
                    .model(resolved.getModel())
                    .messages(messages)
                    .context(ctx)
                    .temperature(0.3)
                    .maxTokens(2048)
                    .toolsOverride(Collections.emptyList())
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

        ProjectChatMessage assistantMsg = ProjectChatMessage.builder()
                .projectId(projectId).orgId(orgId).userId(userId)
                .role("ASSISTANT").content(assistantText)
                .tokensIn(tokensIn).tokensOut(tokensOut)
                .build();
        chatRepo.save(assistantMsg);
        return assistantMsg;
    }

    private List<Message> buildMessages(Project project) {
        StringBuilder sys = new StringBuilder();
        sys.append("You are the ExecuteSpec project assistant. Answer the user's questions ")
           .append("about project '").append(project.getName()).append("' strictly using the context ")
           .append("below. Be concise and direct. If the answer is not in the context, say so ")
           .append("plainly — do not fabricate.\n\n");

        String memory = memoryProvider.buildPlannerContext(project.getId());
        if (memory != null && !memory.isBlank()) {
            sys.append("## PROJECT MEMORY\n").append(memory).append("\n\n");
        }
        try {
            String arch = archService.latest(project.getId())
                    .map(d -> d.getContent()).orElse(null);
            if (arch != null && !arch.isBlank()) {
                sys.append("## ARCHITECTURE DOCUMENT\n").append(arch).append("\n");
            }
        } catch (Exception ignored) { /* arch is optional */ }

        List<ProjectChatMessage> prior = chatRepo.findByProjectIdOrderByCreatedAtAsc(project.getId());
        // Use the most recent user turn as the retrieval query so REFERENCE DOCS
        // are picked relative to whatever the user just asked.
        String retrievalQuery = prior.stream()
                .filter(m -> "USER".equals(m.getRole()))
                .reduce((a, b) -> b)
                .map(ProjectChatMessage::getContent)
                .orElse("");
        if (!retrievalQuery.isBlank()) {
            try {
                String refs = retrievalService.retrieve(project.getId(), retrievalQuery);
                if (refs != null && !refs.isBlank()) sys.append(refs).append("\n");
            } catch (Exception ignored) {}
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
