package com.navneet.executespec.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.StateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * LLM-powered agent that resolves Git merge conflicts by understanding
 * both tasks' intents and producing a merged file.
 *
 * <p>Resolution cascade:
 * <ol>
 *   <li>Haiku (cheap, fast) — handles most conflicts</li>
 *   <li>Sonnet (escalation) — for complex semantic conflicts</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConflictResolverAgent {

    private final LlmRouter llmRouter;
    private final ModelResolver modelResolver;
    private final StateStore stateStore;
    private final ObjectMapper objectMapper;

    @Value("classpath:prompts/conflict-resolver.txt")
    private Resource promptTemplate;
    private static final int MAX_OUTPUT_TOKENS = 8192;

    /**
     * Resolves a merge conflict using Haiku.
     *
     * @return merged file content, or null if resolution failed
     */
    public String resolveConflict(String runId,
                                   String conflictFilePath,
                                   String oursContent,
                                   String theirsContent,
                                   String baseContent,
                                   String taskADescription,
                                   String taskBDescription) {
        ResolvedModel resolved = resolveModelForRun(runId);
        return doResolve(runId, conflictFilePath, oursContent, theirsContent,
            baseContent, taskADescription, taskBDescription, resolved.getModel(),
            resolved.providerName());
    }

    /**
     * Escalated resolution using PLANNING tier when WORKHORSE fails.
     *
     * @return merged file content, or null if resolution failed
     */
    public String resolveWithEscalation(String runId,
                                         String conflictFilePath,
                                         String oursContent,
                                         String theirsContent,
                                         String baseContent,
                                         String taskADescription,
                                         String taskBDescription) {
        ResolvedModel resolved = resolveModelForRun(runId,
                com.navneet.executespec.router.AgentTier.PLANNING);
        return doResolve(runId, conflictFilePath, oursContent, theirsContent,
            baseContent, taskADescription, taskBDescription, resolved.getModel(),
            resolved.providerName());
    }

    private ResolvedModel resolveModelForRun(String runId) {
        var provider = stateStore.findRun(runId)
                .map(r -> r.getAiProviderEnum())
                .orElse(com.navneet.executespec.router.LlmProvider.ANTHROPIC);
        return modelResolver.resolve("ConflictResolverAgent", provider);
    }

    private ResolvedModel resolveModelForRun(String runId,
                                              com.navneet.executespec.router.AgentTier tierOverride) {
        var provider = stateStore.findRun(runId)
                .map(r -> r.getAiProviderEnum())
                .orElse(com.navneet.executespec.router.LlmProvider.ANTHROPIC);
        return modelResolver.resolve("ConflictResolverAgent", provider, tierOverride);
    }

    private String doResolve(String runId,
                              String filePath,
                              String ours,
                              String theirs,
                              String base,
                              String taskADesc,
                              String taskBDesc,
                              String model,
                              String provider) {
        try {
            String template = loadTemplate();
            String userContent = template
                .replace("{file_path}", filePath)
                .replace("{task_a_description}", taskADesc != null ? taskADesc : "Unknown")
                .replace("{task_b_description}", taskBDesc != null ? taskBDesc : "Unknown")
                .replace("{base_content}", truncate(base, 6000))
                .replace("{ours_content}", truncate(ours, 6000))
                .replace("{theirs_content}", truncate(theirs, 6000));

            org.springframework.ai.anthropic.api.AnthropicCacheOptions cacheOpts = org.springframework.ai.anthropic.api.AnthropicCacheOptions.builder()
                .strategy(org.springframework.ai.anthropic.api.AnthropicCacheStrategy.SYSTEM_ONLY)
                .build();

            AnthropicChatOptions options = AnthropicChatOptions.builder()
                .temperature(0.0)
                .model(model)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .cacheOptions(cacheOpts)
                .build();

            Prompt prompt = new Prompt(
                List.of(new SystemMessage(userContent), new UserMessage("Please resolve the conflict.")),
                options
            );

            ChatResponse response = llmRouter.callWithFallback(
                provider, model, prompt, runId, "conflict-resolver",
                LlmRouter.FallbackMode.IMMEDIATE, 0, false);

            if (response != null && response.getResult() != null
                && response.getResult().getOutput() != null) {
                String text = response.getResult().getOutput().getText();
                if (text != null && !text.isBlank()) {
                    String mergedContent = parseConflictResolution(text.strip(), filePath, runId);
                    if (mergedContent != null) {
                        return mergedContent;
                    }
                }
            }
            log.warn("ConflictResolverAgent returned empty response for {} (model={})", filePath, model);
            return null;
        } catch (Exception e) {
            log.error("ConflictResolverAgent failed for {} (model={}): {}", filePath, model, e.getMessage());
            return null;
        }
    }

    /**
     * Parses the JSON response from the conflict resolver prompt.
     * Extracts merged_content and logs merge_notes + risk_level.
     * Falls back to returning the raw text if JSON parsing fails (legacy model behaviour).
     */
    private String parseConflictResolution(String text, String filePath, String runId) {
        try {
            // Strip markdown fences if present
            String json = text;
            if (json.startsWith("```")) {
                json = json.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").trim();
            }
            // Find JSON object boundaries
            int start = json.indexOf('{');
            int end   = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            JsonNode node = objectMapper.readTree(json);
            String mergedContent = node.path("merged_content").asText(null);
            String mergeNotes    = node.path("merge_notes").asText("");
            String riskLevel     = node.path("risk_level").asText("LOW").toUpperCase();

            if (mergedContent == null || mergedContent.isBlank()) {
                log.warn("[ConflictResolver] Parsed JSON but merged_content empty for {} — falling back to raw", filePath);
                return text;
            }

            if ("HIGH".equals(riskLevel)) {
                log.warn("[ConflictResolver] HIGH-risk merge for {} in run {} — notes: {}. Manual review recommended.",
                        filePath, runId, mergeNotes);
            } else {
                log.info("[ConflictResolver] Resolved {} ({}) — {}", filePath, riskLevel, mergeNotes);
            }
            return mergedContent;
        } catch (Exception e) {
            // Response is not JSON (legacy prompt or model ignored format) — return raw
            log.debug("[ConflictResolver] JSON parse failed for {} — using raw response: {}", filePath, e.getMessage());
            return text;
        }
    }

    private String loadTemplate() throws IOException {
        return promptTemplate.getContentAsString(StandardCharsets.UTF_8);
    }

    private String truncate(String content, int maxChars) {
        if (content == null) return "(empty)";
        if (content.length() <= maxChars) return content;
        return content.substring(0, maxChars) + "\n... (truncated, " + content.length() + " chars total)";
    }
}
