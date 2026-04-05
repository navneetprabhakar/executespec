package com.navneet.executespec.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmOutputParser;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.eval.PlannerScaffoldValidator.CheckResult;
import com.navneet.executespec.eval.PlannerScaffoldValidator.ValidationResult;
import com.navneet.executespec.router.ModelMatrixProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Admin-only endpoint for testing planner scaffold generation across providers.
 * Runs extreme scenarios and validates that critical files (Application.java, manage.py, etc.)
 * are never dropped, and FULL_STACK projects are correctly split into two scaffold tasks.
 *
 * Secured via SecurityConfig: /api/admin/** requires SUPER_ADMIN role.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/eval")
@RequiredArgsConstructor
public class PlannerScaffoldTestController {

    private final LlmRouter llmRouter;
    private final LlmOutputParser outputParser;
    private final ObjectMapper mapper;
    private final ModelMatrixProperties modelMatrix;

    @Value("classpath:prompts/planner.txt")
    private Resource plannerPromptResource;

    /**
     * Run planner scaffold tests.
     *
     * @param providers  Comma-separated: anthropic,google,openai. Default: all.
     * @param scenarios  Comma-separated scenario IDs. Default: all.
     */
    @PostMapping("/planner-scaffold")
    public ResponseEntity<?> runScaffoldTests(
            @RequestParam(value = "providers", required = false) List<String> providers,
            @RequestParam(value = "scenarios", required = false) List<String> scenarios) {

        Set<String> providerFilter = (providers != null && !providers.isEmpty())
                ? new LinkedHashSet<>(providers)
                : new LinkedHashSet<>(List.of("anthropic", "google", "openai"));

        List<PlannerScaffoldTestCase> testCases = PlannerScaffoldTestCase.all();
        if (scenarios != null && !scenarios.isEmpty()) {
            Set<String> scenarioFilter = new HashSet<>(scenarios);
            testCases = testCases.stream()
                    .filter(tc -> scenarioFilter.contains(tc.id()))
                    .toList();
        }

        String systemPrompt = loadPlannerPrompt();
        if (systemPrompt == null) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to load planner.txt"));
        }

        // Replace the {{WORKER_MODEL}} placeholder — the planner prompt uses it
        // In testing, we don't care about the actual worker model assignment
        systemPrompt = systemPrompt.replace("{{WORKER_MODEL}}", "test-worker-model");

        // Remove the edit mode block (only active when editMode=true)
        systemPrompt = removeEditModeBlock(systemPrompt);

        log.info("[ScaffoldTest] Starting: {} scenarios x {} providers = {} tests",
                testCases.size(), providerFilter.size(),
                testCases.size() * providerFilter.size());

        List<ValidationResult> results = new ArrayList<>();
        long totalStart = System.currentTimeMillis();

        for (PlannerScaffoldTestCase testCase : testCases) {
            for (String providerName : providerFilter) {
                String model = resolvePlanningModel(providerName);
                if (model == null) {
                    results.add(ValidationResult.error(testCase.id(), providerName, "unknown", 0,
                            "No PLANNING model configured for provider: " + providerName));
                    continue;
                }

                log.info("[ScaffoldTest] Running: scenario={} provider={} model={}",
                        testCase.id(), providerName, model);

                long start = System.currentTimeMillis();
                try {
                    String userMessage = """
                            <user_request>
                            %s
                            </user_request>

                            Current workspace context:
                            Workspace is empty (new project). No existing files.
                            """.formatted(testCase.userRequest());

                    Prompt prompt = new Prompt(List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userMessage)));

                    LlmRouter.LlmResponse lr = llmRouter.callDirect(providerName, model, prompt);
                    String raw = lr.response().getResult().getOutput().getText();
                    long latency = System.currentTimeMillis() - start;

                    TaskPlan plan = outputParser.parse(raw, TaskPlan.class);

                    if (plan.getTasks() == null || plan.getTasks().isEmpty()) {
                        results.add(ValidationResult.error(testCase.id(), providerName, model,
                                latency, "Plan has no tasks"));
                        continue;
                    }

                    List<CheckResult> checks = PlannerScaffoldValidator.validate(plan, testCase);
                    boolean allPassed = checks.stream().allMatch(CheckResult::passed);
                    List<String> taskIds = plan.getTasks().stream()
                            .map(t -> t.getTaskId() + "(" + t.getComplexity() + ")")
                            .toList();

                    results.add(new ValidationResult(
                            testCase.id(), providerName, model, true,
                            plan.getTasks().size(), taskIds, checks,
                            allPassed ? "PASS" : "FAIL", latency,
                            plan.getProjectType(), null));

                    log.info("[ScaffoldTest] {} scenario={} provider={} tasks={} latency={}ms",
                            allPassed ? "PASS" : "FAIL", testCase.id(), providerName,
                            plan.getTasks().size(), latency);

                } catch (Exception e) {
                    long latency = System.currentTimeMillis() - start;
                    log.error("[ScaffoldTest] ERROR scenario={} provider={}: {}",
                            testCase.id(), providerName, e.getMessage());
                    results.add(ValidationResult.error(testCase.id(), providerName, model,
                            latency, e.getClass().getSimpleName() + ": " + e.getMessage()));
                }
            }
        }

        long totalDuration = System.currentTimeMillis() - totalStart;

        // Build summary
        long passed = results.stream().filter(r -> "PASS".equals(r.overall())).count();
        long failed = results.stream().filter(r -> "FAIL".equals(r.overall())).count();
        long errors = results.stream().filter(r -> "ERROR".equals(r.overall())).count();

        return ResponseEntity.ok(Map.of(
                "summary", Map.of(
                        "total", results.size(),
                        "passed", passed,
                        "failed", failed,
                        "errors", errors,
                        "durationMs", totalDuration
                ),
                "results", results
        ));
    }

    private String resolvePlanningModel(String provider) {
        var tiers = modelMatrix.getProviders().get(provider);
        if (tiers == null) return null;
        return tiers.getPlanning();
    }

    private String loadPlannerPrompt() {
        try {
            return plannerPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load planner prompt", e);
            return null;
        }
    }

    private String removeEditModeBlock(String prompt) {
        // Remove the {{#if editMode}}...{{/if}} block since we're testing fresh plans
        int start = prompt.indexOf("{{#if editMode}}");
        int end = prompt.indexOf("{{/if}}");
        if (start >= 0 && end > start) {
            return prompt.substring(0, start) + prompt.substring(end + "{{/if}}".length());
        }
        return prompt;
    }
}
