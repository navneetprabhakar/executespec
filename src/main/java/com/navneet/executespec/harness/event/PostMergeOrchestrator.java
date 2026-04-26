package com.navneet.executespec.harness.event;

import com.navneet.executespec.apitests.ApiTestSuiteAgent;
import com.navneet.executespec.context.ContextAgent;
import com.navneet.executespec.optimiser.PromptAuditService;
import com.navneet.executespec.optimiser.PromptOptimiserAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Listens for {@link PipelineEvent.MergeCompleted} and runs all non-fatal
 * post-merge agents in parallel virtual threads.
 *
 * <p>Decouples the V2 run orchestrator from optional post-merge processing
 * (context, optimiser, API tests, preview).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostMergeOrchestrator {

    private final ContextAgent contextAgent;
    private final PromptOptimiserAgent promptOptimiserAgent;
    private final PromptAuditService promptAuditService;
    private final ApiTestSuiteAgent apiTestSuiteAgent;

    @Async
    @EventListener
    public void onMergeCompleted(PipelineEvent.MergeCompleted event) {
        log.info("[PostMerge] Orchestration started for run {}", event.runId());

        // Record outcome synchronously — cheap in-memory call, no LLM involved
        promptAuditService.recordRunOutcome(event.hadFailures());

        // All post-merge agents run in parallel virtual threads.
        // Each is non-fatal — failures are logged but do NOT affect run status.
        Thread.startVirtualThread(() -> safeRun("ContextAgent", () ->
            contextAgent.runAsync(event.completedRun(), event.reports(), event.review())));

        Thread.startVirtualThread(() -> safeRun("PromptOptimiser", () ->
            promptOptimiserAgent.runIfDue(event.completedRun())));

        Thread.startVirtualThread(() -> safeRun("ApiTestSuite", () ->
            apiTestSuiteAgent.generateAsync(event.completedRun())));
    }

    private void safeRun(String agentName, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.error("[PostMerge] Agent {} failed (non-fatal): {}", agentName, e.getMessage());
        }
    }
}
