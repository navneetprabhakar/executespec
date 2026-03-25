package com.navneet.executespec.testgen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.ModelRouter;
import com.navneet.executespec.router.ModelSelection;
import com.navneet.executespec.router.ModelTier;
import com.navneet.executespec.testgen.model.TestPlan;
import com.navneet.executespec.testgen.model.TestTask;
import com.navneet.executespec.testgen.model.TestTaskStatus;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates a single integration test class per TestTask.
 * Always runs at CAPABLE tier (Sonnet) — test generation requires understanding
 * of HTTP semantics, assertion patterns, and edge cases.
 * One retry with compilation error context; no tier escalation.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TestWorkerAgent {

    private final LlmRouter llmRouter;
    private final ModelRouter modelRouter;
    private final TestTaskRepository testTaskRepository;
    private final TestPlanRepository testPlanRepository;
    private final TestProjectScaffold scaffold;
    private final ObjectMapper objectMapper;

    @Value("classpath:prompts/test-worker.txt")
    private Resource promptResource;

    /**
     * Generate a test class for the given task and write it to the test workspace.
     *
     * @return the generated Java source code
     */
    public String generateTestClass(String runId, TestTask task,
                                     String conventionsDoc, String userRequest) {
        log.info("[TestWorker] Generating test class {} for run {}", task.getTaskName(), runId);

        task.setStatus(TestTaskStatus.GENERATING);
        testTaskRepository.save(task);

        // Build prompt
        String systemPrompt = buildPrompt(task, conventionsDoc, userRequest);

        // Call LLM (CAPABLE tier)
        ModelSelection model = modelRouter.selectFromTier(ModelTier.CAPABLE);
        LlmRouter.LlmResponse lr = callLlmTracked(runId, model, systemPrompt);
        String code = lr.response().getResult().getOutput().getText();
        accumulateWorkerTokens(task.getTestPlanId(), lr, model);

        // Strip markdown fences if present
        code = stripMarkdownFences(code);

        // Write to workspace-test
        try {
            writeTestFile(runId, task.getTaskName(), code);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write test file " + task.getTaskName(), e);
        }

        // Persist
        task.setGeneratedCode(code);
        task.setStatus(TestTaskStatus.COMPILED); // optimistic — compilation gate will verify
        testTaskRepository.save(task);

        log.info("[TestWorker] Generated {} ({} chars) for run {}", task.getTaskName(), code.length(), runId);
        return code;
    }

    /**
     * Retry generating a test class with compilation error context.
     */
    public String retryWithError(String runId, TestTask task,
                                  String conventionsDoc, String userRequest,
                                  String compilationError) {
        log.info("[TestWorker] Retrying {} with compilation error context", task.getTaskName());

        String systemPrompt = buildPrompt(task, conventionsDoc, userRequest)
                + "\n\nYour previous attempt had COMPILATION ERRORS:\n" + compilationError
                + "\n\nFix ALL compilation errors. Output ONLY valid, compilable Java source code.";

        ModelSelection model = modelRouter.selectFromTier(ModelTier.CAPABLE);
        LlmRouter.LlmResponse lr = callLlmTracked(runId, model, systemPrompt);
        String code = lr.response().getResult().getOutput().getText();
        accumulateWorkerTokens(task.getTestPlanId(), lr, model);
        code = stripMarkdownFences(code);

        try {
            writeTestFile(runId, task.getTaskName(), code);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write retry test file " + task.getTaskName(), e);
        }

        task.setGeneratedCode(code);
        task.setCompilationError(null);
        testTaskRepository.save(task);

        return code;
    }

    private String buildPrompt(TestTask task, String conventionsDoc, String userRequest) {
        String testMethodsJson;
        try {
            // plan_json may contain test methods info; use task description as fallback
            testMethodsJson = task.getTaskDescription();
        } catch (Exception e) {
            testMethodsJson = task.getTaskDescription();
        }

        return loadPrompt()
                .replace("{task_name}", task.getTaskName())
                .replace("{task_description}", task.getTaskDescription())
                .replace("{target_entity}", task.getTargetEntity() != null ? task.getTargetEntity() : "")
                .replace("{category}", task.getTestCategory().name())
                .replace("{test_methods_json}", testMethodsJson)
                .replace("{conventions_doc}", conventionsDoc != null ? conventionsDoc : "")
                .replace("{user_request}", userRequest != null ? userRequest : "");
    }

    private LlmRouter.LlmResponse callLlmTracked(String runId, ModelSelection model, String systemPrompt) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage("Generate the test class now.")
        ));
        return llmRouter.callWithFallbackTracked(
                model.getProvider().name().toLowerCase(), model.getModelName(),
                prompt, runId, "test-worker",
                LlmRouter.FallbackMode.IMMEDIATE, 0, false);
    }

    private synchronized void accumulateWorkerTokens(Long testPlanId, LlmRouter.LlmResponse lr, ModelSelection model) {
        try {
            var usage = lr.response().getMetadata().getUsage();
            int inTokens = usage.getPromptTokens().intValue();
            int outTokens = usage.getCompletionTokens().intValue();
            TestPlan testPlan = testPlanRepository.findById(testPlanId).orElse(null);
            if (testPlan != null) {
                testPlan.setWorkerInputTokens(testPlan.getWorkerInputTokens() + inTokens);
                testPlan.setWorkerOutputTokens(testPlan.getWorkerOutputTokens() + outTokens);
                testPlan.setWorkerProvider(lr.provider() != null ? lr.provider() : model.getProvider().name().toLowerCase());
                testPlan.setWorkerModel(lr.model() != null ? lr.model() : model.getModelName());
                testPlanRepository.save(testPlan);
            }
        } catch (Exception e) {
            log.warn("[TestWorker] Failed to extract token usage: {}", e.getMessage());
        }
    }

    private void writeTestFile(String runId, String className, String code) throws IOException {
        Path testDir = scaffold.getTestSourceDir(runId);
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve(className + ".java"), code);
    }

    private String stripMarkdownFences(String code) {
        if (code == null) return code;
        code = code.strip();
        if (code.startsWith("```java")) {
            code = code.substring(7);
        } else if (code.startsWith("```")) {
            code = code.substring(3);
        }
        if (code.endsWith("```")) {
            code = code.substring(0, code.length() - 3);
        }
        return code.strip();
    }

    private String loadPrompt() {
        try {
            return promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load test-worker.txt prompt", e);
        }
    }
}
