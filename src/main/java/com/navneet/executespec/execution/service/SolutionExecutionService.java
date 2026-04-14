package com.navneet.executespec.execution.service;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.entity.ExecutionSolutionRepo;
import com.navneet.executespec.execution.entity.ExecutionSolutionRun;
import com.navneet.executespec.execution.event.ExecutionEventTypes;
import com.navneet.executespec.execution.model.ExecutionDecision;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRunRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.v2.integration.V2RunOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Bridges a single {@link ExecutionSolution} into a V2 {@link PipelineRun}.
 * Each solution is dispatched via {@code V2RunOrchestrator.submitRun(...)} with
 * {@code skipApproval=true}. The caller's virtual thread then polls the
 * V2 run's status until it terminates and maps that back to the solution's
 * lifecycle:
 *
 * <ul>
 *   <li>V2 {@code COMPLETE} → solution {@code DONE}, record handover contracts,
 *       optionally transition the JIRA ticket to {@code Done}.</li>
 *   <li>V2 {@code PARTIAL} → retry if {@code retryCount < maxRetries}, else
 *       treat as failure + JIRA comment + pause for user decision.</li>
 *   <li>V2 {@code FAILED}/{@code CANCELLED} → solution {@code FAILED},
 *       transition JIRA to {@code Blocked}, pause for user decision.</li>
 *   <li>Poll timeout (default 2h) → solution {@code FAILED} with a timeout reason.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SolutionExecutionService {

    private static final Set<String> V2_TERMINAL = Set.of(
            "COMPLETE", "PARTIAL", "FAILED", "CANCELLED", "ABORTED");
    private static final long POLL_INTERVAL_MS = 10_000L;
    private static final long POLL_TIMEOUT_MS = 2L * 60L * 60L * 1000L;  // 2h

    private final V2RunOrchestrator v2Orchestrator;
    private final CrossRepoContextService contextService;
    private final ExecutionBranchService branchService;
    private final ExecutionJiraSync jiraSync;
    private final ExecutionRunRepository runRepo;
    private final ExecutionSolutionRepository solutionRepo;
    private final ExecutionSolutionRunRepository solutionRunRepo;
    private final ProjectRepoRepository projectRepoRepo;
    private final PipelineRunRepository pipelineRunRepo;
    private final AppUserRepository appUserRepo;
    private final SseBroadcaster broadcaster;

    public void executeSolution(ExecutionSolution solution, ExecutionRun run) {
        // Entry-point marker: either first attempt or retry.
        solution.setStatus("RUNNING");
        solution.setStartedAt(Instant.now());
        branchService.tagSolutionStart(run, solution);

        // JIRA — create the ticket on first attempt only.
        if (solution.getJiraTicketId() == null && run.isJiraEnabled()) {
            String ticket = jiraSync.createTicket(run, solution);
            if (ticket != null) solution.setJiraTicketId(ticket);
        }
        solutionRepo.save(solution);
        emit(run.getId(), ExecutionEventTypes.EXEC_SOLUTION_RUNNING, solution.getId());

        String pipelineRunId;
        ExecutionSolutionRun attempt;
        try {
            pipelineRunId = submitV2Run(solution, run);
            solution.setPipelineRunId(pipelineRunId);
            solutionRepo.save(solution);

            int attemptNumber = solutionRunRepo.countBySolutionId(solution.getId()) + 1;
            attempt = ExecutionSolutionRun.builder()
                    .solutionId(solution.getId())
                    .pipelineRunId(pipelineRunId)
                    .attemptNumber(attemptNumber)
                    .build();
            attempt = solutionRunRepo.save(attempt);
        } catch (Exception e) {
            handleFailure(run, solution, "RUNNING", "V2 submit failed: " + shortMsg(e));
            return;
        }

        // Poll V2 terminal status.
        String terminal = pollUntilTerminal(pipelineRunId);
        if (terminal == null) {
            recordAttemptTerminal(attempt, "TIMEOUT", null);
            handleFailure(run, solution, "RUNNING", "V2 run timed out after 2h");
            return;
        }
        recordAttemptTerminal(attempt, terminal, pipelineRunId);

        switch (terminal) {
            case "COMPLETE" -> onV2Complete(run, solution);
            case "PARTIAL"  -> onV2Partial(run, solution);
            default         -> onV2Failed(run, solution, terminal);
        }
    }

    private String submitV2Run(ExecutionSolution solution, ExecutionRun run) {
        String handover = contextService.buildHandoverContext(solution);
        String fullRequest = solution.getDescription() == null
                ? solution.getTitle()
                : solution.getDescription();
        if (!handover.isBlank()) fullRequest = fullRequest + "\n\n" + handover;

        ExecutionSolutionRepo mapping = solution.getRepoMappings() == null
                || solution.getRepoMappings().isEmpty()
                ? null : solution.getRepoMappings().get(0);
        UUID repoId = mapping == null ? null : mapping.getRepoId();
        String repoUrl = null;
        if (repoId != null) {
            repoUrl = projectRepoRepo.findById(repoId)
                    .map(r -> "https://github.com/" + r.getGithubRepoFullName())
                    .orElse(null);
        }
        String githubPat = appUserRepo.findById(run.getCreatedBy())
                .map(u -> u.getGithubPat()).orElse(null);

        PipelineRun pipelineRun = v2Orchestrator.submitRun(
                fullRequest, "EXECUTION_VIEW", repoUrl, repoUrl != null ? "EXISTING" : "NEW",
                githubPat, null, true, true, null, null,
                run.getCreatedBy(), run.getOrgId(), run.getProjectId(), "PRODUCTION",
                repoId == null ? List.of() : List.of(repoId));
        return pipelineRun.getRunId();
    }

    /** Blocks (on a virtual thread) until the V2 run reaches a terminal status,
     *  or returns {@code null} on timeout. */
    private String pollUntilTerminal(String pipelineRunId) {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
            PipelineRun run = pipelineRunRepo.findById(pipelineRunId).orElse(null);
            if (run == null) continue;
            String status = run.getStatus();
            if (status != null && V2_TERMINAL.contains(status.toUpperCase())) {
                return status.toUpperCase();
            }
        }
        return null;
    }

    // ── Terminal handlers ─────────────────────────────────────────────────

    private void onV2Complete(ExecutionRun run, ExecutionSolution solution) {
        solution.setStatus("DONE");
        solution.setCompletedAt(Instant.now());
        branchService.tagSolutionEnd(run, solution);

        // Copy V2 token usage back to the solution for accurate cost reporting.
        if (solution.getPipelineRunId() != null) {
            pipelineRunRepo.findById(solution.getPipelineRunId()).ifPresent(pr -> {
                long tokens = (pr.getTotalInputTokens() == null ? 0 : pr.getTotalInputTokens())
                        + (pr.getTotalOutputTokens() == null ? 0 : pr.getTotalOutputTokens());
                solution.setActualTokens(tokens);
                if (pr.getActualCostUsd() != null) {
                    solution.setCostUsd(java.math.BigDecimal.valueOf(pr.getActualCostUsd()));
                }
            });
        }
        solutionRepo.save(solution);

        // Write handover contracts to every direct dependent solution.
        List<ExecutionSolution> dependents = solutionRepo
                .findByExecutionRun_IdOrderBySequenceNumberAsc(run.getId())
                .stream()
                .filter(s -> s.getDependencies() != null
                        && s.getDependencies().stream().anyMatch(d -> d.getId().equals(solution.getId())))
                .toList();
        contextService.recordContracts(solution, dependents);

        jiraSync.updateTicket(run, solution,
                "Solution completed. Token usage ~" + solution.getActualTokens(),
                "Done");

        emit(run.getId(), ExecutionEventTypes.EXEC_SOLUTION_DONE, solution.getId());
    }

    private void onV2Partial(ExecutionRun run, ExecutionSolution solution) {
        if (solution.getRetryCount() < solution.getMaxRetries()) {
            solution.setRetryCount(solution.getRetryCount() + 1);
            solution.setStatus("QUEUED");
            solution.setFailurePhase(null);
            solution.setFailureReason(null);
            solutionRepo.save(solution);
            jiraSync.updateTicket(run, solution,
                    "V2 returned PARTIAL — retrying (attempt "
                            + solution.getRetryCount() + "/" + solution.getMaxRetries() + ")",
                    null);
            // Re-enter the pipeline on the same virtual thread.
            executeSolution(solution, run);
            return;
        }
        handleFailure(run, solution, "VALIDATING",
                "V2 returned PARTIAL after " + solution.getRetryCount() + " retries");
    }

    private void onV2Failed(ExecutionRun run, ExecutionSolution solution, String terminal) {
        handleFailure(run, solution, "RUNNING", "V2 run terminal=" + terminal);
    }

    public void handleFailure(ExecutionRun run, ExecutionSolution solution,
                              String phase, String reason) {
        solution.setStatus("FAILED");
        solution.setFailurePhase(phase);
        solution.setFailureReason(reason);
        solution.setCompletedAt(Instant.now());
        solutionRepo.save(solution);

        jiraSync.updateTicket(run, solution,
                "Solution failed (" + phase + "): " + reason, "Blocked");

        run.setStatus("PAUSED");
        runRepo.save(run);
        emit(run.getId(), ExecutionEventTypes.EXEC_SOLUTION_FAILED, solution.getId());
        emit(run.getId(), ExecutionEventTypes.EXEC_PAUSED_FOR_DECISION, solution.getId());
    }

    public void processDecision(ExecutionSolution solution, String action) {
        if (action == null) return;
        ExecutionRun run = solution.getExecutionRun();
        switch (action.toUpperCase()) {
            case ExecutionDecision.SKIP -> {
                solution.setStatus("SKIPPED");
                solutionRepo.save(solution);
                jiraSync.updateTicket(run, solution,
                        "User skipped the solution.", "Done");
                emit(run.getId(), ExecutionEventTypes.EXEC_SOLUTION_SKIPPED, solution.getId());
                resume(run);
            }
            case ExecutionDecision.RETRY -> {
                solution.setRetryCount(solution.getRetryCount() + 1);
                solution.setStatus("QUEUED");
                solution.setFailurePhase(null);
                solution.setFailureReason(null);
                solutionRepo.save(solution);
                jiraSync.updateTicket(run, solution,
                        "User triggered a retry.", null);
                emit(run.getId(), ExecutionEventTypes.EXEC_SOLUTION_QUEUED, solution.getId());
                resume(run);
            }
            case ExecutionDecision.FIX -> {
                // Wait for manual-ready signal; no resume yet.
                jiraSync.updateTicket(run, solution,
                        "User chose to fix manually — awaiting signal.", null);
            }
            case ExecutionDecision.ATTEMPT_ANYWAY -> {
                solution.setStatus("SKIPPED");
                solutionRepo.save(solution);
                jiraSync.updateTicket(run, solution,
                        "User chose to proceed without this solution.", "Done");
                resume(run);
            }
            default -> log.warn("Unknown decision action {}", action);
        }
    }

    public void signalManualFixDone(String solutionId) {
        ExecutionSolution s = solutionRepo.findById(solutionId).orElse(null);
        if (s == null) return;
        s.setStatus("DONE");
        s.setCompletedAt(Instant.now());
        solutionRepo.save(s);
        ExecutionRun run = s.getExecutionRun();
        if (run != null) {
            jiraSync.updateTicket(run, s, "Manual fix signalled as complete.", "Done");
            emit(run.getId(), ExecutionEventTypes.EXEC_SOLUTION_DONE, s.getId());
            resume(run);
        }
    }

    private void resume(ExecutionRun run) {
        if (run == null) return;
        run.setStatus("EXECUTING");
        runRepo.save(run);
        emit(run.getId(), ExecutionEventTypes.EXEC_RESUMED, null);
    }

    private void emit(String executionRunId, String type, String payload) {
        broadcaster.emit("exec:" + executionRunId,
                new RunEvent(type, executionRunId, payload, null, Instant.now()));
    }

    private void recordAttemptTerminal(ExecutionSolutionRun attempt, String terminal, String pipelineRunId) {
        if (attempt == null) return;
        attempt.setTerminalStatus(terminal);
        attempt.setCompletedAt(Instant.now());
        if (pipelineRunId != null) {
            pipelineRunRepo.findById(pipelineRunId).ifPresent(pr -> {
                attempt.setInputTokens(pr.getTotalInputTokens() == null ? 0L : pr.getTotalInputTokens().longValue());
                attempt.setOutputTokens(pr.getTotalOutputTokens() == null ? 0L : pr.getTotalOutputTokens().longValue());
                if (pr.getActualCostUsd() != null) {
                    attempt.setCostUsd(java.math.BigDecimal.valueOf(pr.getActualCostUsd()));
                }
            });
        }
        solutionRunRepo.save(attempt);
    }

    private String shortMsg(Throwable e) {
        String msg = e == null ? "" : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
    }
}
