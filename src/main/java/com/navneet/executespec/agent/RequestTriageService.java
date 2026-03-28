package com.navneet.executespec.agent;

import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Classifies incoming requests as SINGLE_SHOT or MULTI_TASK using a LIGHTWEIGHT LLM call.
 *
 * <p>SINGLE_SHOT requests bypass the planner and worktree system entirely — a synthetic
 * 1-task plan is built and executed directly on the main branch.
 *
 * <p>Falls back to MULTI_TASK on any error (LLM failure, parse error, timeout).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RequestTriageService {

    public enum TriageResult { SINGLE_SHOT, MULTI_TASK }

    private final LlmRouter llmRouter;
    private final ModelResolver modelResolver;

    @Value("${agent.triage.enabled:true}")
    private boolean triageEnabled;

    @Value("classpath:prompts/triage.txt")
    private Resource triagePrompt;

    /**
     * Classifies the user request. Returns MULTI_TASK if triage is disabled or on any error.
     *
     * @param userRequest the raw user specification / request text
     * @param runId       current run ID for LLM routing
     * @param provider    user's selected AI provider
     * @return SINGLE_SHOT or MULTI_TASK
     */
    public TriageResult classify(String userRequest, String runId, LlmProvider provider) {
        if (!triageEnabled) {
            log.info("[Triage] Disabled via config — defaulting to MULTI_TASK for run {}", runId);
            return TriageResult.MULTI_TASK;
        }

        try {
            String systemPrompt = triagePrompt.getContentAsString(StandardCharsets.UTF_8);
            ResolvedModel resolved = modelResolver.resolve("RequestTriageService", provider);

            Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userRequest)
            ));

            var response = llmRouter.callWithFallback(
                resolved.providerName(), resolved.getModel(), prompt,
                runId, "request-triage",
                LlmRouter.FallbackMode.IMMEDIATE, 0, false
            );

            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                log.warn("[Triage] Empty LLM response for run {} — defaulting to MULTI_TASK", runId);
                return TriageResult.MULTI_TASK;
            }

            // Parse the JSON response — look for "SINGLE_SHOT" anywhere in the response
            String cleaned = text.trim();
            if (cleaned.contains("SINGLE_SHOT")) {
                log.info("[Triage] Run {} classified as SINGLE_SHOT", runId);
                return TriageResult.SINGLE_SHOT;
            } else {
                log.info("[Triage] Run {} classified as MULTI_TASK", runId);
                return TriageResult.MULTI_TASK;
            }

        } catch (Exception e) {
            log.warn("[Triage] Failed for run {} — defaulting to MULTI_TASK: {}", runId, e.getMessage());
            return TriageResult.MULTI_TASK;
        }
    }
}
