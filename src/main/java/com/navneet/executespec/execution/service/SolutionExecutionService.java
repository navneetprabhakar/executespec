package com.navneet.executespec.execution.service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bridges a single {@link ExecutionSolution} into a V2 {@link PipelineRun}. Each solution is
 * dispatched via {@code RunOrchestrator.submitRun(...)} with {@code skipApproval=true}. The
 * caller's virtual thread then polls the V2 run's status until it terminates and maps that back to
 * the solution's lifecycle:
 *
 * <ul>
 *   <li>V2 {@code COMPLETE} → solution {@code DONE}, record handover contracts, optionally
 *       transition the JIRA ticket to {@code Done}.
 *   <li>V2 {@code PARTIAL} → retry if {@code retryCount < maxRetries}, else treat as failure + JIRA
 *       comment + pause for user decision.
 *   <li>V2 {@code FAILED}/{@code CANCELLED} → solution {@code FAILED}, transition JIRA to {@code
 *       Blocked}, pause for user decision.
 *   <li>Poll timeout (default 2h) → solution {@code FAILED} with a timeout reason.
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SolutionExecutionService {

  private static final Set<String> V2_TERMINAL =
      Set.of("COMPLETE", "PARTIAL", "FAILED", "CANCELLED", "ABORTED");
  private static final long POLL_INTERVAL_MS = 10_000L;
  private static final long POLL_TIMEOUT_MS = 2L * 60L * 60L * 1000L; // 2h

  /**
   * 2026-05-13 audit — statusReason prefixes that indicate "the worker did its job; what failed was
   * post-worker validation". When BOTH this AND the worker-task-outcome is COMPLETE, an auto-retry
   * just re-runs a deterministic task and produces identical code. Each such retry costs
   * $0.10-$0.30 on a SMALL plan and never improves the outcome. Pause for user decision instead so
   * the customer can SKIP (accept the partial) / RETRY (override) / FIX (manual).
   *
   * <p>{@code TESTS_FAILED} carries a counter suffix ({@code TESTS_FAILED:5_OF_12}); match by
   * prefix via {@code startsWith}.
   */
  private static final Set<String> NON_RETRYABLE_WHEN_WORKER_CLEAN =
      Set.of("FINAL_BUILD_FAILED", "TESTS_FAILED");

  private final RunOrchestrator v2Orchestrator;
  private final CrossRepoContextService contextService;
  private final ExecutionBranchService branchService;
  private final ExecutionJiraSync jiraSync;
  private final ExecutionRunRepository runRepo;
  private final ExecutionSolutionRepository solutionRepo;
  private final ExecutionSolutionRunRepository solutionRunRepo;
  private final ProjectRepoRepository projectRepoRepo;
  private final PipelineRunRepository pipelineRunRepo;
  private final com.navneet.executespec.state.repository.AgentTaskRepository agentTaskRepo;
  private final AppUserRepository appUserRepo;
  private final SseBroadcaster broadcaster;
  private final com.navneet.executespec.repo.GitHubRepoService gitHubRepoService;
  private final ExecutionRunCasUpdater runCas;
  private final ExecutionCostAggregator costAggregator;

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
      attempt =
          ExecutionSolutionRun.builder()
              .solutionId(solution.getId())
              .pipelineRunId(pipelineRunId)
              .attemptNumber(attemptNumber)
              .build();
      attempt = solutionRunRepo.save(attempt);
    } catch (Exception e) {
      handleFailure(run, solution, "RUNNING", "V2 submit failed: " + shortMsg(e));
      return;
    }

    // Poll V2 terminal status. Passing the ExecutionRun id lets the loop
    // short-circuit on abort (otherwise it sleeps for up to 2h).
    String terminal = pollUntilTerminal(pipelineRunId, run.getId());
    if (terminal == null) {
      recordAttemptTerminal(attempt, "TIMEOUT", null);
      handleFailure(run, solution, "RUNNING", "V2 run timed out after 2h");
      return;
    }
    recordAttemptTerminal(attempt, terminal, pipelineRunId);

    switch (terminal) {
      case "COMPLETE" -> onV2Complete(run, solution);
      case "PARTIAL" -> onV2Partial(run, solution);
      default -> onV2Failed(run, solution, terminal);
    }
  }

  private String submitV2Run(ExecutionSolution solution, ExecutionRun run) {
    String handover = contextService.buildHandoverContext(solution);
    String fullRequest =
        solution.getDescription() == null ? solution.getTitle() : solution.getDescription();
    if (!handover.isBlank()) fullRequest = fullRequest + "\n\n" + handover;

    // Continue-on-PR: inject review comments into the worker's request so the
    // planner/worker knows what to fix.
    if (run.getContinuePrNumber() != null && run.getContinuePrRepo() != null) {
      try {
        String pat =
            appUserRepo.findById(run.getCreatedBy()).map(u -> u.getGithubPat()).orElse(null);
        String comments =
            gitHubRepoService.fetchPrReviewComments(
                run.getContinuePrRepo(), run.getContinuePrNumber(), pat);
        if (comments != null && !comments.isBlank()) {
          fullRequest =
              fullRequest
                  + "\n\n--- PR REVIEW COMMENTS (address these) ---\n"
                  + comments
                  + "\n--- END PR REVIEW COMMENTS ---\n";
        }
      } catch (Exception e) {
        log.warn("Failed to fetch PR review comments for exec {}: {}", run.getId(), e.getMessage());
      }
    }

    ExecutionSolutionRepo mapping =
        solution.getRepoMappings() == null || solution.getRepoMappings().isEmpty()
            ? null
            : solution.getRepoMappings().iterator().next();
    UUID repoId = mapping == null ? null : mapping.getRepoId();
    String repoUrl = null;
    if (repoId != null) {
      repoUrl =
          projectRepoRepo
              .findById(repoId)
              .map(r -> "https://github.com/" + r.getGithubRepoFullName())
              .orElse(null);
    }
    String githubPat =
        appUserRepo.findById(run.getCreatedBy()).map(u -> u.getGithubPat()).orElse(null);

    // Use the Launch's aiProvider so the worker runs on the provider the user
    // picked (project default or explicit override). Without this the
    // orchestrator normalises null -> openai + gpt-5.4-mini, silently
    // overriding the Anthropic choice on the project.
    PipelineRun pipelineRun =
        v2Orchestrator.submitRun(
            fullRequest,
            "EXECUTION_VIEW",
            repoUrl,
            repoUrl != null ? "EXISTING" : "NEW",
            githubPat,
            null,
            true,
            true,
            null,
            run.getAiProvider(),
            run.getCreatedBy(),
            run.getOrgId(),
            run.getProjectId(),
            "PRODUCTION",
            repoId == null ? List.of() : List.of(repoId),
            null,
            false,
            run.getBranch());
    return pipelineRun.getRunId();
  }

  /**
   * Blocks (on a virtual thread) until the V2 run reaches a terminal status, or returns {@code
   * null} on timeout. Short-circuits with CANCELLED when the parent ExecutionRun is aborted by the
   * user.
   */
  private String pollUntilTerminal(String pipelineRunId, String executionRunId) {
    long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(POLL_INTERVAL_MS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return null;
      }
      // Bail out if the user hit Abort — the ExecutionRun status flips
      // to ABORTED which RunOrchestrator.cancel already signalled, but
      // we stop waiting here so the solution records CANCELLED promptly.
      if (executionRunId != null) {
        String execStatus =
            runRepo.findById(executionRunId).map(ExecutionRun::getStatus).orElse(null);
        if (execStatus != null) {
          String up = execStatus.toUpperCase();
          if ("ABORTED".equals(up) || "CANCELLED".equals(up)) {
            return "CANCELLED";
          }
        }
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
    // v2.5.12 — also copy the PR URL + parsed PR number from the child run
    // so the Launches detail view (which reads solution.prUrl) surfaces the
    // PR that ScmPushService already raised. Previously solution.prUrl stayed
    // null even when a PR was OPEN on GitHub, because SolutionExecutionService
    // never synced back from the child PipelineRun. Found live on 2026-04-17
    // running a 2-solution project launch (PRs #6 + #10 on GitHub, null on
    // both solution rows).
    if (solution.getPipelineRunId() != null) {
      // 2026-05-13 audit — fix sync-back race observed on exec 85778e63 / S6: V2
      // returned terminal=COMPLETE and we entered onV2Complete, but ScmPushService
      // hadn't yet written scmPrUrl onto the pipeline_run row — net effect: the
      // solution row recorded DONE with prUrl=null even though PR #35 was already
      // OPEN on GitHub. Poll briefly with a tight backoff so the ScmPushService
      // write has time to flush before we give up.
      String syncedPrUrl = readPrUrlWithBriefRetry(solution.getPipelineRunId());
      pipelineRunRepo
          .findById(solution.getPipelineRunId())
          .ifPresent(
              pr -> {
                long tokens =
                    (pr.getTotalInputTokens() == null ? 0 : pr.getTotalInputTokens())
                        + (pr.getTotalOutputTokens() == null ? 0 : pr.getTotalOutputTokens());
                solution.setActualTokens(tokens);
                if (pr.getActualCostUsd() != null) {
                  solution.setCostUsd(java.math.BigDecimal.valueOf(pr.getActualCostUsd()));
                }
              });
      if (syncedPrUrl != null && !syncedPrUrl.isBlank() && solution.getPrUrl() == null) {
        solution.setPrUrl(syncedPrUrl);
        Integer num = parsePrNumber(syncedPrUrl);
        if (num != null) solution.setPrNumber(num);
        if (solution.getPrStatus() == null) solution.setPrStatus("OPEN");
      } else if (solution.getPrUrl() == null) {
        log.warn(
            "[exec={} sol={}] PR URL still null after retry — pipeline_run {} may not have"
                + " pushed yet; manual sync via /api/runs/{}/push-github still works.",
            run.getId(),
            solution.getId(),
            solution.getPipelineRunId(),
            solution.getPipelineRunId());
      }
    }
    solutionRepo.save(solution);

    // v2.5.7 UX-fix: bubble solution-level tokens + cost up to the ExecutionRun
    // so dashboards and run cards show accurate aggregates. Without this, the
    // ExecutionRun totals stay at 0 even when solutions burned millions of tokens.
    // PR-A (2026-05-13) — replaces the legacy per-Solution incrementer with a recompute from
    // the source-of-truth pipeline_run rows. The old accumulator only counted COMPLETE attempts
    // (skipping PARTIAL + FAILED that ran on the customer's dime) and dropped contributions on
    // CAS conflict. Live repro on exec 85778e63: rollup $0.81, real spend $5.55 (6.8× under).
    costAggregator.recomputeAndPersist(run.getId());

    // Write handover contracts to every direct dependent solution.
    // Use the @EntityGraph-eager dependencies fetch — even though the
    // ManyToMany is annotated EAGER on the entity, the audit (2026-04-25)
    // showed Hibernate occasionally lazy-defers it when paged. Now that
    // OSIV is off the deferral would throw on the stream below; the
    // explicit graph guarantees the dependencies set is hydrated here.
    List<ExecutionSolution> dependents =
        solutionRepo
            .findWithDependenciesByExecutionRun_IdOrderBySequenceNumberAsc(run.getId())
            .stream()
            .filter(
                s ->
                    s.getDependencies() != null
                        && s.getDependencies().stream()
                            .anyMatch(d -> d.getId().equals(solution.getId())))
            .toList();
    contextService.recordContracts(solution, dependents);

    jiraSync.updateTicket(
        run, solution, "Solution completed. Token usage ~" + solution.getActualTokens(), "Done");

    emit(run.getId(), ExecutionEventTypes.EXEC_SOLUTION_DONE, solution.getId());
  }

  private void onV2Partial(ExecutionRun run, ExecutionSolution solution) {
    // v2.5.12 — short-circuit the retry loop when the same failure
    // signature repeats. The V107 statusReason column on the child V2
    // PipelineRun carries values like FINAL_BUILD_FAILED or
    // TESTS_FAILED:5_OF_12 — if consecutive retries land on the same
    // signature, the failure is deterministic and further retries just
    // burn budget. Different signatures still get the full retry budget.
    PipelineRun pipelineRun = pipelineRunRepo.findById(solution.getPipelineRunId()).orElse(null);
    String currentSignature = pipelineRun == null ? null : pipelineRun.getStatusReason();

    // 2026-05-13 audit — gate auto-retry on the failure shape. When the V2
    // run hit a post-worker validation issue (FINAL_BUILD_FAILED, TESTS_FAILED)
    // BUT the worker's per-task outcome was clean, retry just re-runs a
    // deterministic task and produces identical code. Pause for user
    // decision instead — they can SKIP (accept the partial PR), RETRY
    // (override with intent), or FIX (manual). Saves the $0.10-$0.30 per
    // wasted auto-retry observed across exec 85778e63.
    if (pipelineRun != null
        && currentSignature != null
        && isNonRetryableReason(currentSignature)
        && isWorkerOutcomeClean(pipelineRun.getRunId())) {
      log.info(
          "[exec={} sol={}] PARTIAL with statusReason={} but worker outcome clean — skipping"
              + " auto-retry; pausing for user decision",
          run.getId(),
          solution.getId(),
          currentSignature);
      handleFailure(
          run,
          solution,
          "VALIDATING",
          "Worker completed but post-worker validation failed ("
              + currentSignature
              + "). Retry would produce identical code — review the PR or pick SKIP/FIX.");
      return;
    }

    if (currentSignature != null && currentSignature.equals(solution.getLastFailureSignature())) {
      int newStreak = solution.getConsecutiveFailureCount() + 1;
      solution.setConsecutiveFailureCount(newStreak);
      if (newStreak >= 2) {
        // Two consecutive attempts with the same statusReason — stop.
        solutionRepo.save(solution);
        handleFailure(
            run,
            solution,
            "VALIDATING",
            "Same failure signature '"
                + currentSignature
                + "' on "
                + newStreak
                + " consecutive attempts; aborting retries");
        return;
      }
    } else {
      solution.setLastFailureSignature(currentSignature);
      solution.setConsecutiveFailureCount(currentSignature != null ? 1 : 0);
    }

    if (solution.getRetryCount() < solution.getMaxRetries()) {
      solution.setRetryCount(solution.getRetryCount() + 1);
      solution.setStatus("QUEUED");
      solution.setFailurePhase(null);
      solution.setFailureReason(null);
      solutionRepo.save(solution);
      jiraSync.updateTicket(
          run,
          solution,
          "V2 returned PARTIAL — retrying (attempt "
              + solution.getRetryCount()
              + "/"
              + solution.getMaxRetries()
              + (currentSignature != null ? "; reason=" + currentSignature : "")
              + ")",
          null);
      // Re-enter the pipeline on the same virtual thread.
      executeSolution(solution, run);
      return;
    }
    handleFailure(
        run,
        solution,
        "VALIDATING",
        "V2 returned PARTIAL after "
            + solution.getRetryCount()
            + " retries"
            + (currentSignature != null ? " (last reason: " + currentSignature + ")" : ""));
  }

  private void onV2Failed(ExecutionRun run, ExecutionSolution solution, String terminal) {
    handleFailure(run, solution, "RUNNING", "V2 run terminal=" + terminal);
  }

  public void handleFailure(
      ExecutionRun run, ExecutionSolution solution, String phase, String reason) {
    // Re-read the run so a concurrent abort (ABORTED/CANCELLED) isn't
    // overwritten with PAUSED. Without this, aborting a running solution
    // flips the ExecutionRun back to PAUSED because handleFailure fires
    // AFTER abortExecution's setStatus("ABORTED").
    ExecutionRun fresh = runRepo.findById(run.getId()).orElse(run);
    String currentStatus = fresh.getStatus() == null ? "" : fresh.getStatus().toUpperCase();
    boolean alreadyTerminal =
        "ABORTED".equals(currentStatus)
            || "CANCELLED".equals(currentStatus)
            || "COMPLETE".equals(currentStatus)
            || "FAILED".equals(currentStatus);

    solution.setStatus("FAILED");
    solution.setFailurePhase(phase);
    solution.setFailureReason(reason);
    solution.setCompletedAt(Instant.now());
    solutionRepo.save(solution);

    // PR-A (2026-05-13) — even on failure, the V2 attempts burned tokens. Recompute totals so the
    // execution's cost reflects what was actually spent (legacy incrementer skipped this entirely).
    costAggregator.recomputeAndPersist(run.getId());

    if (alreadyTerminal) {
      log.info(
          "handleFailure skipping PAUSED flip — exec {} already {}", run.getId(), currentStatus);
      emit(run.getId(), ExecutionEventTypes.EXEC_SOLUTION_FAILED, solution.getId());
      return;
    }

    jiraSync.updateTicket(run, solution, "Solution failed (" + phase + "): " + reason, "Blocked");

    // 2026-04-26 hotfix — covered by ExecutionRunCasUpdater. The PAUSED
    // save races with parallel solution completions in sibling layers
    // (executeApprovedPlan dispatches each layer in parallel). The
    // mutator re-checks the terminal-state guard so an abort that
    // landed between findById and save still wins.
    runCas.casUpdate(
        run.getId(),
        r -> {
          String s = r.getStatus() == null ? "" : r.getStatus().toUpperCase();
          if ("ABORTED".equals(s)
              || "CANCELLED".equals(s)
              || "COMPLETE".equals(s)
              || "FAILED".equals(s)) {
            return; // honour the terminal write that beat us here
          }
          r.setStatus("PAUSED");
        });
    emit(run.getId(), ExecutionEventTypes.EXEC_SOLUTION_FAILED, solution.getId());
    emit(run.getId(), ExecutionEventTypes.EXEC_PAUSED_FOR_DECISION, solution.getId());
  }

  @org.springframework.transaction.annotation.Transactional
  public void processDecision(ExecutionSolution solution, String action) {
    if (action == null) return;
    // Re-attach within the new transaction so lazy associations (executionRun)
    // resolve cleanly instead of throwing LazyInitializationException.
    solution = solutionRepo.findById(solution.getId()).orElse(solution);
    ExecutionRun run = solution.getExecutionRun();
    if (run != null) run.getId(); // force-init proxy
    switch (action.toUpperCase()) {
      case ExecutionDecision.SKIP -> {
        solution.setStatus("SKIPPED");
        solutionRepo.save(solution);
        jiraSync.updateTicket(run, solution, "User skipped the solution.", "Done");
        emit(run.getId(), ExecutionEventTypes.EXEC_SOLUTION_SKIPPED, solution.getId());
        resume(run);
      }
      case ExecutionDecision.RETRY -> {
        solution.setRetryCount(solution.getRetryCount() + 1);
        solution.setStatus("QUEUED");
        solution.setFailurePhase(null);
        solution.setFailureReason(null);
        solutionRepo.save(solution);
        jiraSync.updateTicket(run, solution, "User triggered a retry.", null);
        emit(run.getId(), ExecutionEventTypes.EXEC_SOLUTION_QUEUED, solution.getId());
        resume(run);
      }
      case ExecutionDecision.FIX -> {
        // Wait for manual-ready signal; no resume yet.
        jiraSync.updateTicket(run, solution, "User chose to fix manually — awaiting signal.", null);
      }
      case ExecutionDecision.ATTEMPT_ANYWAY -> {
        solution.setStatus("SKIPPED");
        solutionRepo.save(solution);
        jiraSync.updateTicket(
            run, solution, "User chose to proceed without this solution.", "Done");
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
    // 2026-04-26 hotfix — covered by ExecutionRunCasUpdater. Caller
    // (processDecision / signalManualFixDone) passes a `run` reference
    // they loaded from the DB before applying their own JIRA sync calls;
    // by the time this resume hits, @Version may have moved on.
    runCas.casUpdate(run.getId(), r -> r.setStatus("EXECUTING"));
    emit(run.getId(), ExecutionEventTypes.EXEC_RESUMED, null);
  }

  private void emit(String executionRunId, String type, String payload) {
    broadcaster.emit(
        "exec:" + executionRunId, new RunEvent(type, executionRunId, payload, null, Instant.now()));
  }

  private void recordAttemptTerminal(
      ExecutionSolutionRun attempt, String terminal, String pipelineRunId) {
    if (attempt == null) return;
    attempt.setTerminalStatus(terminal);
    attempt.setCompletedAt(Instant.now());
    if (pipelineRunId != null) {
      pipelineRunRepo
          .findById(pipelineRunId)
          .ifPresent(
              pr -> {
                attempt.setInputTokens(
                    pr.getTotalInputTokens() == null ? 0L : pr.getTotalInputTokens().longValue());
                attempt.setOutputTokens(
                    pr.getTotalOutputTokens() == null ? 0L : pr.getTotalOutputTokens().longValue());
                if (pr.getActualCostUsd() != null) {
                  attempt.setCostUsd(java.math.BigDecimal.valueOf(pr.getActualCostUsd()));
                }
              });
    }
    solutionRunRepo.save(attempt);
  }

  // 2026-05-13 PR-A — accumulateExecutionRunTotals(...) deleted; replaced by
  // ExecutionCostAggregator
  // which recomputes totals from source-of-truth pipeline_run rows rather than incrementing
  // per-Solution (the old approach dropped contributions on CAS conflict + skipped non-COMPLETE
  // attempts entirely, causing the $0.81-vs-$5.55 under-report observed on exec 85778e63).

  private String shortMsg(Throwable e) {
    String msg =
        e == null ? "" : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
  }

  /**
   * 2026-05-13 audit — true when the {@code statusReason} (e.g. {@code FINAL_BUILD_FAILED} or
   * {@code TESTS_FAILED:5_OF_12}) indicates post-worker validation failed rather than a worker-side
   * problem retry could fix. Prefix match handles the counter-suffix variants.
   */
  private static boolean isNonRetryableReason(String statusReason) {
    if (statusReason == null) return false;
    for (String prefix : NON_RETRYABLE_WHEN_WORKER_CLEAN) {
      if (statusReason.equals(prefix) || statusReason.startsWith(prefix + ":")) return true;
    }
    return false;
  }

  /**
   * 2026-05-13 audit — true when every agent_task on the pipeline_run ended in {@code COMPLETE}
   * status. Used to decide whether the worker actually did its job: when the answer is yes and the
   * run still PARTIAL'd, the issue is post-worker validation (build / test gate), not the worker
   * itself — retry can't help.
   *
   * <p>Conservative: returns false if the task list is unexpectedly empty (treat as worker didn't
   * record progress; allow the retry).
   */
  private boolean isWorkerOutcomeClean(String pipelineRunId) {
    var tasks = agentTaskRepo.findByRunId(pipelineRunId);
    if (tasks == null || tasks.isEmpty()) return false;
    for (var t : tasks) {
      if (!"COMPLETE".equals(t.getStatus())) return false;
    }
    return true;
  }

  /**
   * 2026-05-13 audit — read the V2 pipeline_run's scmPrUrl / githubPrUrl, polling briefly when
   * still null. Closes the sync-back race observed on exec 85778e63 / S6 where {@code onV2Complete}
   * fired before {@code ScmPushService.push} flushed the prUrl write — leaving solution rows with
   * status=DONE but prUrl=null even though the PR was OPEN on GitHub.
   *
   * <p>Total wall-clock cap ~6s (6 polls × 1s). Beyond that the caller logs + moves on; the user
   * can use the manual "Open PR" path on the run detail page.
   */
  private String readPrUrlWithBriefRetry(String pipelineRunId) {
    if (pipelineRunId == null) return null;
    for (int i = 0; i < 6; i++) {
      var pr = pipelineRunRepo.findById(pipelineRunId).orElse(null);
      if (pr == null) return null;
      String prUrl = pr.getScmPrUrl();
      if (prUrl == null || prUrl.isBlank()) prUrl = pr.getGithubPrUrl();
      if (prUrl != null && !prUrl.isBlank()) return prUrl;
      try {
        Thread.sleep(1000);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
  }

  /**
   * v2.5.12 — parse the PR number from a GitHub or Bitbucket PR URL. Patterns supported:
   * https://github.com/org/repo/pull/123 https://bitbucket.org/org/repo/pull-requests/123 Returns
   * null when the URL doesn't match either shape.
   */
  private static Integer parsePrNumber(String prUrl) {
    if (prUrl == null) return null;
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("/(?:pull|pull-requests)/(\\d+)").matcher(prUrl);
    if (!m.find()) return null;
    try {
      return Integer.parseInt(m.group(1));
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
