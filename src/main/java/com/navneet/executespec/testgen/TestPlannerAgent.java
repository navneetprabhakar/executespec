package com.navneet.executespec.testgen;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.testgen.model.*;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestTaskRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Plans integration tests from the spec (NOT from generated code).
 * Always runs at CAPABLE tier (Sonnet) — test planning requires reasoning about edge cases.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TestPlannerAgent {

    private final LlmRouter llmRouter;
    private final ModelResolver modelResolver;
    private final StateStore stateStore;
    private final TestPlanRepository testPlanRepository;
    private final TestTaskRepository testTaskRepository;
    private final ObjectMapper objectMapper;

    @Value("classpath:prompts/test-planner.txt")
    private Resource promptResource;

    /**
     * Generate a test plan from the user spec and conventions doc.
     *
     * @return saved TestPlan with associated TestTask records
     */
    public TestPlan planTests(String runId, String userRequest, String conventionsDoc, boolean isCli) {
        log.info("[TestPlanner] Starting test planning for run {}", runId);

        // Create TestPlan record
        TestPlan testPlan = TestPlan.builder()
                .runId(runId)
                .status(TestPlanStatus.PLANNING)
                .conventionsDoc(conventionsDoc)
                .build();
        testPlan = testPlanRepository.save(testPlan);

        String testingContext = isCli 
            ? "You are testing a Command Line Interface (CLI) application. Tests should verify via stdout, stderr, and exit codes."
            : "You are testing against a LIVE RUNNING APPLICATION via HTTP calls. These are NOT unit tests.";

        // Build prompt
        String systemPrompt = loadPrompt()
                .replace("{testing_context}", testingContext)
                .replace("{user_request}", userRequest != null ? userRequest : "")
                .replace("{conventions_doc}", conventionsDoc != null ? conventionsDoc : "");

        // Call LLM via ModelResolver (PLANNING tier for test planning)
        LlmProvider aiProvider = stateStore.findRun(runId)
                .map(r -> r.getAiProviderEnum()).orElse(LlmProvider.ANTHROPIC);
        ResolvedModel model = modelResolver.resolve("TestPlannerAgent", aiProvider);
        LlmRouter.LlmResponse lr = callLlm(runId, model, systemPrompt, 1);
        String llmResponse = lr.response().getResult().getOutput().getText();
        accumulatePlannerTokens(testPlan, lr, model);

        // Parse response — retry once on failure
        List<TestTaskPlan> taskPlans;
        try {
            taskPlans = objectMapper.readValue(llmResponse, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[TestPlanner] JSON parse failed, retrying with stricter prompt: {}", e.getMessage());
            String retryPrompt = systemPrompt + "\n\nIMPORTANT: Your previous response was not valid JSON. " +
                    "Respond with ONLY a valid JSON array. No markdown, no backticks, no explanation.";
            LlmRouter.LlmResponse lr2 = callLlm(runId, model, retryPrompt, 2);
            llmResponse = lr2.response().getResult().getOutput().getText();
            accumulatePlannerTokens(testPlan, lr2, model);
            try {
                taskPlans = objectMapper.readValue(llmResponse, new TypeReference<>() {});
            } catch (Exception e2) {
                log.error("[TestPlanner] JSON parse failed on retry for run {}: {}", runId, e2.getMessage());
                testPlan.setStatus(TestPlanStatus.FAILED);
                testPlanRepository.save(testPlan);
                throw new RuntimeException("Test planner failed to produce valid JSON after 2 attempts", e2);
            }
        }

        // Cap at 6 test classes to keep runs lean
        if (taskPlans.size() > 6) {
            log.info("[TestPlanner] Trimming {} test tasks to 6 for run {}", taskPlans.size(), runId);
            taskPlans = taskPlans.subList(0, 6);
        }

        // Cap test methods per class at 8
        for (TestTaskPlan tp : taskPlans) {
            if (tp.getTestMethods() != null && tp.getTestMethods().size() > 8) {
                log.info("[TestPlanner] Trimming {} methods to 8 in {} for run {}", tp.getTestMethods().size(), tp.getTaskName(), runId);
                tp.setTestMethods(tp.getTestMethods().subList(0, 8));
            }
        }

        // Persist test tasks
        for (TestTaskPlan tp : taskPlans) {
            TestCategory category;
            try {
                category = TestCategory.valueOf(tp.getCategory());
            } catch (Exception e) {
                category = TestCategory.CRUD;
            }

            TestTask task = TestTask.builder()
                    .testPlanId(testPlan.getId())
                    .taskName(tp.getTaskName())
                    .taskDescription(tp.getDescription())
                    .targetEntity(tp.getTargetEntity())
                    .testCategory(category)
                    .status(TestTaskStatus.PENDING)
                    .build();
            testTaskRepository.save(task);
        }

        // Save plan JSON and update status
        try {
            testPlan.setPlanJson(objectMapper.writeValueAsString(taskPlans));
        } catch (Exception e) {
            log.warn("[TestPlanner] Failed to serialize plan JSON: {}", e.getMessage());
        }
        testPlan.setStatus(TestPlanStatus.GENERATING);
        testPlan = testPlanRepository.save(testPlan);

        log.info("[TestPlanner] Created test plan with {} tasks for run {}", taskPlans.size(), runId);
        return testPlan;
    }

    private LlmRouter.LlmResponse callLlm(String runId, ResolvedModel model, String prompt, int attempt) {
        AnthropicCacheOptions cacheOpts = AnthropicCacheOptions.builder()
                .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                .build();
        AnthropicChatOptions opts = AnthropicChatOptions.builder()
                .model(model.getModel())
                .cacheOptions(cacheOpts)
                .build();
        Prompt chatPrompt = new Prompt(List.of(
                new SystemMessage(prompt),
                new UserMessage("Generate the test plan now.")
        ), opts);
        return llmRouter.callWithFallbackTracked(
                model.providerName(), model.getModel(),
                chatPrompt, runId, "test-planner",
                LlmRouter.FallbackMode.IMMEDIATE, attempt - 1, false);
    }

    private void accumulatePlannerTokens(TestPlan testPlan, LlmRouter.LlmResponse lr, ResolvedModel model) {
        try {
            var usage = lr.response().getMetadata().getUsage();
            int inTokens = usage.getPromptTokens().intValue();
            int outTokens = usage.getCompletionTokens().intValue();
            testPlan.setPlannerInputTokens(testPlan.getPlannerInputTokens() + inTokens);
            testPlan.setPlannerOutputTokens(testPlan.getPlannerOutputTokens() + outTokens);
            testPlan.setPlannerProvider(lr.provider() != null ? lr.provider() : model.providerName());
            testPlan.setPlannerModel(lr.model() != null ? lr.model() : model.getModel());
        } catch (Exception e) {
            log.warn("[TestPlanner] Failed to extract token usage: {}", e.getMessage());
        }
    }

    private String loadPrompt() {
        try {
            return promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load test-planner.txt prompt", e);
        }
    }

    /** DTO for parsing LLM output. */
    @Data
    public static class TestTaskPlan {
        private String taskName;
        private String description;
        private String targetEntity;
        private String category;
        private List<TestMethodPlan> testMethods;
    }

    @Data
    public static class TestMethodPlan {
        private String name;
        private String description;
    }
}
