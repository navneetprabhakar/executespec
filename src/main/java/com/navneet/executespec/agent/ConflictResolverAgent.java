package com.navneet.executespec.agent;
import com.navneet.executespec.config.ModelConstants;

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

    @Value("classpath:prompts/conflict-resolver.txt")
    private Resource promptTemplate;

    private static final String HAIKU_MODEL = ModelConstants.HAIKU;
    private static final String SONNET_MODEL = ModelConstants.SONNET;
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
        return doResolve(runId, conflictFilePath, oursContent, theirsContent,
            baseContent, taskADescription, taskBDescription, HAIKU_MODEL);
    }

    /**
     * Escalated resolution using Sonnet when Haiku fails.
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
        return doResolve(runId, conflictFilePath, oursContent, theirsContent,
            baseContent, taskADescription, taskBDescription, SONNET_MODEL);
    }

    private String doResolve(String runId,
                              String filePath,
                              String ours,
                              String theirs,
                              String base,
                              String taskADesc,
                              String taskBDesc,
                              String model) {
        try {
            String template = loadTemplate();
            String userContent = template
                .replace("{file_path}", filePath)
                .replace("{task_a_description}", taskADesc != null ? taskADesc : "Unknown")
                .replace("{task_b_description}", taskBDesc != null ? taskBDesc : "Unknown")
                .replace("{base_content}", truncate(base, 6000))
                .replace("{ours_content}", truncate(ours, 6000))
                .replace("{theirs_content}", truncate(theirs, 6000));

            AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(model)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .build();

            Prompt prompt = new Prompt(
                List.of(new UserMessage(userContent)),
                options
            );

            ChatResponse response = llmRouter.callWithFallback(
                "anthropic", model, prompt, runId, "conflict-resolver",
                LlmRouter.FallbackMode.IMMEDIATE, 0, false);

            if (response != null && response.getResult() != null
                && response.getResult().getOutput() != null) {
                String text = response.getResult().getOutput().getText();
                if (text != null && !text.isBlank()) {
                    log.info("ConflictResolverAgent resolved {} using {} for run {}", filePath, model, runId);
                    return text.strip();
                }
            }
            log.warn("ConflictResolverAgent returned empty response for {} (model={})", filePath, model);
            return null;
        } catch (Exception e) {
            log.error("ConflictResolverAgent failed for {} (model={}): {}", filePath, model, e.getMessage());
            return null;
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
