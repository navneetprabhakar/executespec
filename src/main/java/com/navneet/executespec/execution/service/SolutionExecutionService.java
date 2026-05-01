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
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.v2.integration.V2RunOrchestrator;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bridges a single {@link ExecutionSolution} into a V2 {@link PipelineRun}. Each solution is
 * dispatched via {@code V2RunOrchestrator.submitRun(...)} with {@code skipApproval=true}. The
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
  private final com.navneet.executespec.repo.GitHubRepoService gitHubRepoService;
  private final ExecutionRunCasUpdater runCas;

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
      // to ABORTED which V2RunOrchestrator.cancel already signalled, but
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
                // Prefer the SCM-agnostic scmPrUrl (v2.5.x); fall back to the
                // legacy githubPrUrl for runs that predate the SCM split.
                String prUrl = pr.getScmPrUrl();
                if (prUrl == null || prUrl.isBlank()) prUrl = pr.getGithubPrUrl();
                if (prUrl != null && !prUrl.isBlank() && solution.getPrUrl() == null) {
                  solution.setPrUrl(prUrl);
                  Integer num = parsePrNumber(prUrl);
                  if (num != null) solution.setPrNumber(num);
                  if (solution.getPrStatus() == null) solution.setPrStatus("OPEN");
                }
              });
    }
    solutionRepo.save(solution);

    // v2.5.7 UX-fix: bubble solution-level tokens + cost up to the ExecutionRun
    // so dashboards and run cards show accurate aggregates. Without this, the
    // ExecutionRun totals stay at 0 even when solutions burned millions of tokens.
    accumulateExecutionRunTotals(run, solution);

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
    String currentSignature =
        pipelineRunRepo
            .findById(solution.getPipelineRunId())
            .map(PipelineRun::getStatusReason)
            .orElse(null);
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

  /**
   * v2.5.7 UX-fix — aggregate a completing solution's tokens + cost into the parent ExecutionRun's
   * totals. Re-reads the run row to avoid stomping on concurrent solution completions (parallel
   * layers). Runs in its own tiny transaction so the totals are visible even if the caller crashes
   * later.
   *
   * <p>2026-04-26 hotfix — covered by ExecutionRunCasUpdater. The previous implementation did its
   * own findById + save dance and silently dropped the increment when @Version moved on between the
   * two; parallel sibling layers completing within the same SSE tick lost their token + cost
   * accumulation. The CAS bean's mutator runs the increment on every retry fetch so concurrent
   * completers all converge.
   */
  protected void accumulateExecutionRunTotals(ExecutionRun run, ExecutionSolution solution) {
    try {
      final long solutionTokens =
          solution.getActualTokens() == null ? 0L : solution.getActualTokens();
      final java.math.BigDecimal solutionCost =
          solution.getCostUsd() == null ? java.math.BigDecimal.ZERO : solution.getCostUsd();
      if (solutionTokens == 0L && solutionCost.signum() == 0) return;

      boolean ok =
          runCas.casUpdate(
              run.getId(),
              r -> {
                long prevTokens = r.getTotalActualTokens() == null ? 0L : r.getTotalActualTokens();
                java.math.BigDecimal prevCost =
                    r.getTotalCostUsd() == null ? java.math.BigDecimal.ZERO : r.getTotalCostUsd();
                r.setTotalActualTokens(prevTokens + solutionTokens);
                r.setTotalCostUsd(prevCost.add(solutionCost));
              });
      if (!ok) {
        log.warn(
            "Failed to accumulate totals for exec {} solution {} (CAS exhausted)",
            run.getId(),
            solution.getId());
      }
    } catch (Exception e) {
      log.warn(
          "Failed to accumulate totals for exec {} solution {}: {}",
          run.getId(),
          solution.getId(),
          e.getMessage());
    }
  }

  private String shortMsg(Throwable e) {
    String msg =
        e == null ? "" : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
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
