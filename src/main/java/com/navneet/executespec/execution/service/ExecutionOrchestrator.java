package com.navneet.executespec.execution.service;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.execution.dto.CreateExecutionRequest;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.entity.ExecutionSolutionRepo;
import com.navneet.executespec.execution.event.ExecutionEventTypes;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * End-to-end orchestration for an {@link ExecutionRun} — setup → analysis →
 * arch review → plan gen → plan approval → execution → completion.
 *
 * <p>Each async phase is a {@code @Async} virtual-thread call that emits SSE
 * milestones on the execution-scoped channel {@code exec:{runId}} and mutates
 * the status column so the frontend can drive its phased stepper.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionOrchestrator {

    private final ExecutionRunRepository runRepo;
    private final ExecutionSolutionRepository solutionRepo;
    private final ProjectArchitectureService archService;
    private final ProjectRepoRepository projectRepoRepo;
    private final SolutionDependencyResolver depResolver;
    private final SolutionExecutionService solutionExecService;
    private final ExecutionBranchService branchService;
    private final SseBroadcaster broadcaster;

    // ── Creation ───────────────────────────────────────────────────────────

    public ExecutionRun createExecution(Project project, AppUser user, CreateExecutionRequest req) {
        ExecutionRun run = ExecutionRun.builder()
                .projectId(project.getId())
                .orgId(project.getOrgId())
                .createdBy(user.getId())
                .status("SETUP")
                .requirement(req.requirement())
                .jiraEnabled(Boolean.TRUE.equals(req.jiraEnabled()))
                .build();
        return runRepo.save(run);
    }

    // ── Phase 2: analysis ─────────────────────────────────────────────────

    @Async
    public void triggerAnalysis(String executionRunId) {
        ExecutionRun run = runRepo.findById(executionRunId).orElse(null);
        if (run == null) return;
        run.setStatus("ANALYSING");
        runRepo.save(run);
        emit(run.getId(), ExecutionEventTypes.EXEC_UNIFIED_ARCH_STARTED, null);

        try {
            // Delegate to the pilot v3.0 architecture service which already
            // handles per-repo codemap generation + unified doc synthesis.
            ProjectArchitectureDoc doc = archService.generate(run.getProjectId());
            run.setUnifiedArchDoc(doc == null ? null : doc.getContent());
            run.setStatus("ARCH_REVIEW");
            runRepo.save(run);
            emit(run.getId(), ExecutionEventTypes.EXEC_ANALYSIS_COMPLETE, null);
        } catch (Exception e) {
            log.error("analysis failed for exec {}: {}", run.getId(), e.getMessage(), e);
            run.setStatus("ABORTED");
            runRepo.save(run);
            emit(run.getId(), ExecutionEventTypes.EXEC_ABORTED, e.getMessage());
        }
    }

    // ── Phase 3: arch approval ────────────────────────────────────────────

    public void approveArchDoc(ExecutionRun run, boolean approved, String annotations) {
        if (!approved) return;
        run.setArchApprovedAt(Instant.now());
        if (annotations != null && !annotations.isBlank()) {
            String existing = run.getUnifiedArchDoc() == null ? "" : run.getUnifiedArchDoc();
            run.setUnifiedArchDoc(existing + "\n\n---\n## User annotations\n" + annotations);
        }
        runRepo.save(run);
    }

    // ── Phase 5: plan approval + execute ──────────────────────────────────

    public void approvePlanAndExecute(String executionRunId) {
        ExecutionRun run = runRepo.findById(executionRunId).orElse(null);
        if (run == null) return;
        run.setPlanApprovedAt(Instant.now());
        runRepo.save(run);
        executeApprovedPlan(executionRunId);
    }

    @Async
    public void executeApprovedPlan(String executionRunId) {
        ExecutionRun run = runRepo.findById(executionRunId).orElse(null);
        if (run == null) return;
        run.setStatus("EXECUTING");
        runRepo.save(run);
        emit(run.getId(), ExecutionEventTypes.EXEC_STARTED, null);

        List<ExecutionSolution> all = solutionRepo.findByExecutionRun_IdOrderBySequenceNumberAsc(executionRunId);
        List<List<ExecutionSolution>> layers;
        try {
            layers = depResolver.resolveExecutionOrder(all);
        } catch (IllegalStateException e) {
            log.error("exec {} has cyclic deps: {}", executionRunId, e.getMessage());
            run.setStatus("ABORTED");
            runRepo.save(run);
            emit(run.getId(), ExecutionEventTypes.EXEC_ABORTED, e.getMessage());
            return;
        }

        for (List<ExecutionSolution> layer : layers) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (ExecutionSolution s : layer) {
                // Re-read to catch status changes (RETRY / SKIP decisions):
                ExecutionSolution fresh = solutionRepo.findById(s.getId()).orElse(s);
                if (!"QUEUED".equals(fresh.getStatus())) continue;
                futures.add(CompletableFuture.runAsync(
                        () -> solutionExecService.executeSolution(fresh, run)));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // If something paused the run during this layer, bail out —
            // resumption happens via SolutionExecutionService.processDecision.
            ExecutionRun latest = runRepo.findById(executionRunId).orElse(run);
            if ("PAUSED".equals(latest.getStatus()) || "ABORTED".equals(latest.getStatus())) {
                return;
            }
        }

        // All layers complete → open final PRs per repo.
        String githubPat = null; // best-effort; branch service tolerates null
        Set<UUID> distinctRepos = new LinkedHashSet<>();
        for (ExecutionSolution s : all) {
            if (s.getRepoMappings() == null) continue;
            for (ExecutionSolutionRepo m : s.getRepoMappings()) {
                if (m.getRepoId() != null) distinctRepos.add(m.getRepoId());
            }
        }
        for (UUID repoId : distinctRepos) {
            projectRepoRepo.findById(repoId).ifPresent(pr ->
                    branchService.createFinalPr(
                            pr.getGithubRepoFullName(), pr.getId(),
                            executionRunId, buildReportMarkdown(run, all), githubPat));
            emit(run.getId(), ExecutionEventTypes.EXEC_FINAL_PR_CREATED, repoId.toString());
        }

        run.setStatus("COMPLETE");
        runRepo.save(run);
        emit(run.getId(), ExecutionEventTypes.EXEC_COMPLETE, null);
    }

    public void abortExecution(ExecutionRun run) {
        run.setStatus("ABORTED");
        runRepo.save(run);
        emit(run.getId(), ExecutionEventTypes.EXEC_ABORTED, null);
    }

    public Map<String, Object> buildReport(ExecutionRun run) {
        List<ExecutionSolution> solutions = solutionRepo.findByExecutionRun_IdOrderBySequenceNumberAsc(run.getId());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", run.getId());
        report.put("status", run.getStatus());
        report.put("requirement", run.getRequirement());
        report.put("totalActualTokens", run.getTotalActualTokens());
        report.put("totalCostUsd", run.getTotalCostUsd());
        report.put("markdown", buildReportMarkdown(run, solutions));
        report.put("solutionCount", solutions.size());
        return report;
    }

    private String buildReportMarkdown(ExecutionRun run, List<ExecutionSolution> solutions) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Execution ").append(run.getId().substring(0, 8)).append("\n\n");
        sb.append("**Status:** ").append(run.getStatus()).append("  \n");
        sb.append("**Project:** ").append(run.getProjectId()).append("  \n");
        if (run.isJiraEnabled()) sb.append("**JIRA sync:** enabled  \n");
        sb.append("\n**Requirement**\n\n> ").append(run.getRequirement().replace("\n", "\n> ")).append("\n\n");

        long done = solutions.stream().filter(s -> "DONE".equals(s.getStatus())).count();
        long failed = solutions.stream().filter(s -> "FAILED".equals(s.getStatus())).count();
        long skipped = solutions.stream().filter(s -> "SKIPPED".equals(s.getStatus())).count();
        long totalTokens = solutions.stream()
                .mapToLong(s -> s.getActualTokens() == null ? 0L : s.getActualTokens()).sum();

        sb.append("**Summary:** ")
          .append(done).append(" done · ")
          .append(failed).append(" failed · ")
          .append(skipped).append(" skipped · ~")
          .append(totalTokens).append(" tokens\n\n");

        sb.append("## Solutions\n\n");
        sb.append("| # | Title | Status | Tokens | PR | JIRA |\n");
        sb.append("|---|-------|--------|--------|----|------|\n");
        for (ExecutionSolution s : solutions) {
            sb.append("| S").append(s.getSequenceNumber()).append(" | ")
              .append(escape(s.getTitle())).append(" | ")
              .append(s.getStatus()).append(" | ")
              .append(s.getActualTokens() == null ? "—" : s.getActualTokens()).append(" | ")
              .append(s.getPrUrl() == null ? "—" : "[PR](" + s.getPrUrl() + ")").append(" | ")
              .append(s.getJiraTicketId() == null ? "—" : s.getJiraTicketId())
              .append(" |\n");
        }

        // Failed-solution call-outs so the PR reviewer doesn't have to chase the UI.
        boolean anyFailed = solutions.stream().anyMatch(s -> s.getFailureReason() != null);
        if (anyFailed) {
            sb.append("\n## Failures\n\n");
            for (ExecutionSolution s : solutions) {
                if (s.getFailureReason() == null) continue;
                sb.append("- **S").append(s.getSequenceNumber()).append(" · ").append(s.getTitle())
                  .append("** (phase: ").append(s.getFailurePhase()).append("): ")
                  .append(s.getFailureReason()).append("\n");
            }
        }

        sb.append("\n_Generated by ExecuteSpec Execution View._\n");
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ");
    }

    private void emit(String executionRunId, String type, String payload) {
        broadcaster.emit("exec:" + executionRunId,
                new RunEvent(type, executionRunId, payload, null, Instant.now()));
    }
}
