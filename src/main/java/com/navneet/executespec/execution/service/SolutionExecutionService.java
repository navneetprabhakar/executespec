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
   * RR-531 — how long we wait for a cancelled child V2 run to reach a terminal status before we
   * report that we could not prove it stopped. {@code RunLifecycleService.cancel} writes CANCELLED
   * synchronously, so this bound is for the case where the row is being written concurrently.
   */
  private static final long FENCE_ACK_TIMEOUT_MS = 30_000L;

  private static final long FENCE_ACK_POLL_MS = 1_000L;

  /** A dead terminal-projection owner may be taken over after this durable quiet period. */
  static final java.time.Duration TERMINAL_RECONCILIATION_LEASE = java.time.Duration.ofMinutes(5);

  /**
   * RR-530 — the single legal prior state for a human execution decision. {@link #handleFailure}
   * writes FAILED and pauses the launch; that pause is what the decision endpoints answer.
   */
  private static final String AWAITING_DECISION = "FAILED";

  /**
   * RR-530 — the state a FIX decision moves the solution into. Manual-ready is legal only from
   * here, which is what makes it impossible to replay against a solution nobody chose to fix by
   * hand.
   */
  static final String AWAITING_MANUAL_FIX = "AWAITING_MANUAL_FIX";

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

  private final ExecutionPipelineSubmissionService pipelineSubmissionService;
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

  /** RR-531 — used to actually cancel an abandoned child run, not merely record it as failed. */
  private final com.navneet.executespec.integration.RunLifecycleService runLifecycleService;

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
      ExecutionPipelineSubmissionService.ApprovedSolutionSubmission submission =
          submitV2Run(solution, run);
      pipelineRunId = submission.run().getRunId();
      attempt = submission.attempt();
      solution.setPipelineRunId(pipelineRunId);
      solutionRepo.save(solution);
    } catch (Exception e) {
      handleFailure(run, solution, "RUNNING", "V2 submit failed: " + shortMsg(e));
      return;
    }

    observeCanonicalAttempt(solution, run, attempt, pipelineRunId, false);
  }

  /**
   * Observe and project an already-created canonical child without submitting another run.
   *
   * <p>The terminal projection is protected by a conditional RUNNING -> RECONCILING claim. A normal
   * poller and a restart observer may both notice the terminal child, but exactly one may apply
   * solution/attempt/JIRA/cost side effects. Recovery may take over a RECONCILING row only after
   * its lease timestamp is stale, covering a crash immediately after that claim.
   */
  public void observeCanonicalAttempt(
      ExecutionSolution solution,
      ExecutionRun run,
      ExecutionSolutionRun attempt,
      String pipelineRunId,
      boolean recovery) {
    if (solution == null || run == null || attempt == null || pipelineRunId == null) {
      throw new IllegalArgumentException(
          "solution, execution, attempt and pipelineRunId are required");
    }
    if (!java.util.Objects.equals(attempt.getSolutionId(), solution.getId())
        || !java.util.Objects.equals(attempt.getPipelineRunId(), pipelineRunId)) {
      throw new IllegalArgumentException("attempt is not the canonical child for this solution");
    }

    // Poll V2 terminal status. Passing the ExecutionRun id lets the loop short-circuit on abort
    // (otherwise it sleeps for up to 2h).
    String terminal = pollUntilTerminal(pipelineRunId, run.getId());
    if (terminal == null) {
      // RR-531 — the child was previously abandoned here: the solution recorded TIMEOUT and the
      // launch paused, but the V2 run kept executing with skipApproval=true and could still spend,
      // mutate the workspace and open/push a customer PR long after Execution View called it
      // failed. A concurrent retry could then publish a second, conflicting result. Fence it: ask
      // for idempotent cancellation and wait a bounded time for a terminal acknowledgement before
      // the solution is allowed to fail (and therefore become retryable/skippable).
      String fenced = fenceChildRun(pipelineRunId, "solution poll timeout after 2h");
      if (fenced != null && !"CANCELLED".equals(fenced)) {
        // Reconcile a late completion rather than reporting a run that actually finished as a
        // timeout — the poll loop simply did not observe the terminal write in time.
        log.warn(
            "[exec={} sol={}] poll timed out but child run {} was already terminal={} —"
                + " reconciling",
            run.getId(),
            solution.getId(),
            pipelineRunId,
            fenced);
        reconcileObservedTerminal(run, solution, attempt, pipelineRunId, fenced, recovery);
        return;
      }
      reconcileObservedTerminal(run, solution, attempt, pipelineRunId, "TIMEOUT", recovery);
      return;
    }
    reconcileObservedTerminal(run, solution, attempt, pipelineRunId, terminal, recovery);
  }

  private void reconcileObservedTerminal(
      ExecutionRun run,
      ExecutionSolution solution,
      ExecutionSolutionRun attempt,
      String pipelineRunId,
      String terminal,
      boolean recovery) {
    Instant claimedAt = Instant.now();
    int claimed =
        solutionRepo.claimTerminalReconciliation(
            solution.getId(), run.getId(), pipelineRunId, claimedAt);
    if (claimed == 0 && recovery) {
      claimed =
          solutionRepo.claimStaleTerminalReconciliation(
              solution.getId(),
              run.getId(),
              pipelineRunId,
              claimedAt,
              claimedAt.minus(TERMINAL_RECONCILIATION_LEASE));
    }
    if (claimed == 0) {
      log.info(
          "[exec={} sol={}] terminal child {}={} already has a live reconciliation owner;"
              + " skipping duplicate projection",
          run.getId(),
          solution.getId(),
          pipelineRunId,
          terminal);
      return;
    }

    // Re-read after the bulk conditional update cleared the persistence context. Handlers mutate
    // this fresh row, replacing RECONCILING with its real lifecycle outcome.
    ExecutionSolution claimedSolution =
        solutionRepo.findByIdAndExecutionRun_Id(solution.getId(), run.getId()).orElse(solution);
    recordAttemptTerminal(attempt, terminal, pipelineRunId);

    switch (terminal) {
      case "COMPLETE" -> onV2Complete(run, claimedSolution);
      case "PARTIAL" -> onV2Partial(run, claimedSolution);
      case "TIMEOUT" ->
          handleFailure(
              run,
              claimedSolution,
              "RUNNING",
              "V2 run timed out after 2h; child cancellation could not be proven cleanly");
      default -> onV2Failed(run, claimedSolution, terminal);
    }
  }

  private ExecutionPipelineSubmissionService.ApprovedSolutionSubmission submitV2Run(
      ExecutionSolution solution, ExecutionRun run) {
    String handover = contextService.buildHandoverContext(solution);
    String fullRequest =
        solution.getDescription() == null ? solution.getTitle() : solution.getDescription();
    // RR-995 — the handover text is planner/model-derived upstream-solution title/description/PR
    // content. Appended raw, an injected or malformed upstream string could rewrite the downstream
    // planner/worker's instructions and repository targets. Delimit it as untrusted evidence via
    // PromptTrustBoundary so it is treated as data, not as authoritative instructions.
    if (!handover.isBlank()) {
      fullRequest =
          fullRequest
              + "\n\n"
              + com.navneet.executespec.agent.PromptTrustBoundary.wrap(
                  "UPSTREAM_SOLUTION_CONTRACTS", handover);
    }

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

    // RR-529 — a solution's repo model is plural (cross-repo handover is a built feature), but this
    // used to take only iterator().next() and silently drop every other mapping, so a planned
    // multi-repo solution executed against one arbitrary set-order repository while the required
    // changes in the others never ran — and the launch could still report success. Pass EVERY
    // mapped
    // repository, in a deterministic order, into the canonical multi-repo V2 request (repoUrl=null
    // +
    // EXISTING + selectedRepoIds), the same shape the unified dispatch path uses. An unknown mapped
    // repo id is corruption/drift and fails closed rather than silently narrowing the scope.
    List<UUID> mappedRepoIds =
        (solution.getRepoMappings() == null
                ? java.util.stream.Stream.<ExecutionSolutionRepo>empty()
                : solution.getRepoMappings().stream())
            .map(ExecutionSolutionRepo::getRepoId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted(java.util.Comparator.comparing(UUID::toString))
            .toList();
    for (UUID id : mappedRepoIds) {
      if (projectRepoRepo.findById(id).isEmpty()) {
        throw new IllegalStateException(
            "solution "
                + solution.getId()
                + " maps unknown repository "
                + id
                + " — refusing to execute against an unresolved target");
      }
    }
    // repoUrl stays null for the multi-repo/EXISTING path; the selected repo ids drive per-repo
    // cloning. Only a solution with zero mapped repos falls back to a NEW repository.
    // The approved-solution route keeps its distinct server-authorized bypass; the adapter owns
    // that exception so this lifecycle service cannot accidentally drift into customer admission.
    return pipelineSubmissionService.submitApprovedSolution(
        solution, run, fullRequest, mappedRepoIds, attemptGeneration(solution.getRetryCount()));
  }

  static int attemptGeneration(int retryCount) {
    if (retryCount < 0) throw new IllegalArgumentException("retryCount cannot be negative");
    return Math.addExact(retryCount, 1);
  }

  /**
   * Blocks (on a virtual thread) until the V2 run reaches a terminal status, or returns {@code
   * null} on timeout. Short-circuits with CANCELLED when the parent ExecutionRun is aborted by the
   * user.
   */
  private String pollUntilTerminal(String pipelineRunId, String executionRunId) {
    long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
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
      try {
        Thread.sleep(POLL_INTERVAL_MS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
  }

  /**
   * RR-531 — request idempotent cancellation of a child V2 run and wait, bounded, for a terminal
   * acknowledgement.
   *
   * <p>Returns the child's observed terminal status: {@code "CANCELLED"} when this call fenced it,
   * the run's own terminal status when it had already finished (a late completion the poll loop
   * missed), or {@code null} when no terminal state could be confirmed within the bound. A {@code
   * null} return is the honest "we could not prove this child stopped" answer and callers must not
   * present it as a clean stop.
   */
  private String fenceChildRun(String pipelineRunId, String why) {
    if (pipelineRunId == null) return null;
    try {
      boolean cancelled = runLifecycleService.cancel(pipelineRunId);
      log.info(
          "RR-531 fencing child run {} ({}): cancel accepted={}", pipelineRunId, why, cancelled);
    } catch (Exception e) {
      log.warn("RR-531 fencing child run {} failed: {}", pipelineRunId, shortMsg(e));
    }
    long deadline = System.currentTimeMillis() + FENCE_ACK_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      PipelineRun child = pipelineRunRepo.findById(pipelineRunId).orElse(null);
      if (child == null) return null;
      String status = child.getStatus();
      if (status != null && V2_TERMINAL.contains(status.toUpperCase())) {
        return status.toUpperCase();
      }
      try {
        Thread.sleep(FENCE_ACK_POLL_MS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    log.error(
        "RR-531 child run {} did not reach a terminal state within {}ms after cancellation — it may"
            + " still be spending or publishing",
        pipelineRunId,
        FENCE_ACK_TIMEOUT_MS);
    return null;
  }

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

  /**
   * Outcome of a human execution decision. RR-530 — the caller needs to distinguish "applied" from
   * "you were not allowed to do that here", because the previous void signature meant an illegal or
   * replayed decision was reported to the operator as accepted.
   */
  public enum DecisionOutcome {
    /** This caller won the conditional transition. */
    APPLIED,
    /**
     * The solution is not in the one legal prior state for this action (replay, race, stale UI).
     */
    ILLEGAL_STATE,
    /** The action token is not a member of {@link ExecutionDecision}. */
    UNKNOWN_ACTION,
    /** The solution does not exist under this execution run. */
    NOT_FOUND
  }

  /**
   * RR-530 — apply a human decision to a paused solution.
   *
   * <p>Every action is a conditional transition out of exactly one prior state. The awaiting-
   * decision state is {@code FAILED} (what {@link #handleFailure} writes before pausing the
   * launch), so SKIP / RETRY / FIX / ATTEMPT_ANYWAY are legal only from there, and manual-ready is
   * legal only from the {@code AWAITING_MANUAL_FIX} state a prior FIX decision creates. The
   * transition is a single atomic UPDATE that also stamps the deciding actor, so:
   *
   * <ul>
   *   <li>a stale or replayed call cannot skip QUEUED/RUNNING work or mark it DONE,
   *   <li>a second RETRY cannot bump the retry counter and launch an overlapping paid child run,
   *   <li>manual-ready cannot complete a solution the operator never chose to fix by hand,
   *   <li>an unknown action is refused rather than silently logged and treated as success.
   * </ul>
   *
   * <p>Only the caller that won the transition performs the side effects (JIRA sync, SSE, resume).
   */
  @org.springframework.transaction.annotation.Transactional
  public DecisionOutcome processDecision(
      ExecutionSolution solution, String action, String actorUserId) {
    if (solution == null) return DecisionOutcome.NOT_FOUND;
    if (action == null || action.isBlank()) return DecisionOutcome.UNKNOWN_ACTION;

    // Re-attach within the new transaction so lazy associations (executionRun)
    // resolve cleanly instead of throwing LazyInitializationException.
    ExecutionSolution attached = solutionRepo.findById(solution.getId()).orElse(null);
    if (attached == null) return DecisionOutcome.NOT_FOUND;
    ExecutionRun run = attached.getExecutionRun();
    if (run == null) return DecisionOutcome.NOT_FOUND;
    String executionRunId = run.getId(); // force-init proxy

    String normalized = action.toUpperCase(java.util.Locale.ROOT);
    String nextStatus;
    int retryDelta;
    switch (normalized) {
      case ExecutionDecision.SKIP, ExecutionDecision.ATTEMPT_ANYWAY -> {
        nextStatus = "SKIPPED";
        retryDelta = 0;
      }
      case ExecutionDecision.RETRY -> {
        nextStatus = "QUEUED";
        retryDelta = 1;
      }
      case ExecutionDecision.FIX -> {
        nextStatus = AWAITING_MANUAL_FIX;
        retryDelta = 0;
      }
      default -> {
        log.warn(
            "Rejected unknown decision action {} for exec {} sol {}",
            action,
            executionRunId,
            attached.getId());
        return DecisionOutcome.UNKNOWN_ACTION;
      }
    }

    // RR-531 — a RETRY submits a fresh child run and overwrites pipelineRunId. Fence any child
    // that is somehow still active first, so the abandoned attempt cannot publish alongside the
    // new one. Normal flow has already terminalised it; a crashed JVM or a lost poll has not.
    if (ExecutionDecision.RETRY.equals(normalized) && attached.getPipelineRunId() != null) {
      fenceChildRun(attached.getPipelineRunId(), "retry decision supersedes previous attempt");
    }

    int claimed =
        solutionRepo.claimDecision(
            attached.getId(),
            executionRunId,
            AWAITING_DECISION,
            nextStatus,
            normalized,
            actorUserId,
            retryDelta,
            Instant.now());
    if (claimed == 0) {
      log.warn(
          "Rejected decision {} for exec {} sol {} — solution is {} not {} (replay, race or stale"
              + " client)",
          normalized,
          executionRunId,
          attached.getId(),
          attached.getStatus(),
          AWAITING_DECISION);
      return DecisionOutcome.ILLEGAL_STATE;
    }

    ExecutionSolution fresh = solutionRepo.findById(attached.getId()).orElse(attached);
    switch (normalized) {
      case ExecutionDecision.SKIP -> {
        jiraSync.updateTicket(run, fresh, "User skipped the solution.", "Done");
        emit(executionRunId, ExecutionEventTypes.EXEC_SOLUTION_SKIPPED, fresh.getId());
        resume(run);
      }
      case ExecutionDecision.ATTEMPT_ANYWAY -> {
        jiraSync.updateTicket(run, fresh, "User chose to proceed without this solution.", "Done");
        emit(executionRunId, ExecutionEventTypes.EXEC_SOLUTION_SKIPPED, fresh.getId());
        resume(run);
      }
      case ExecutionDecision.RETRY -> {
        jiraSync.updateTicket(run, fresh, "User triggered a retry.", null);
        emit(executionRunId, ExecutionEventTypes.EXEC_SOLUTION_QUEUED, fresh.getId());
        resume(run);
      }
      case ExecutionDecision.FIX ->
          // Wait for the manual-ready signal; no resume yet. The solution now sits in
          // AWAITING_MANUAL_FIX, which is what makes signalManualFixDone legal.
          jiraSync.updateTicket(run, fresh, "User chose to fix manually — awaiting signal.", null);
      default -> {
        /* unreachable — validated above */
      }
    }
    return DecisionOutcome.APPLIED;
  }

  /**
   * RR-530 — complete a manual fix. Legal only when a prior FIX decision put the solution into
   * {@code AWAITING_MANUAL_FIX}; previously this marked ANY matching solution DONE and resumed the
   * launch, so a QUEUED, RUNNING or already-FAILED solution could be declared complete without the
   * human decision state the gate represents.
   *
   * @return {@link DecisionOutcome#APPLIED} when this caller won the transition
   */
  @org.springframework.transaction.annotation.Transactional
  public DecisionOutcome signalManualFixDone(
      String executionRunId, String solutionId, String actorUserId) {
    ExecutionSolution s =
        solutionRepo.findByIdAndExecutionRun_Id(solutionId, executionRunId).orElse(null);
    if (s == null) return DecisionOutcome.NOT_FOUND;

    int claimed =
        solutionRepo.claimManualFixDone(solutionId, executionRunId, actorUserId, Instant.now());
    if (claimed == 0) {
      log.warn(
          "Rejected manual-ready for exec {} sol {} — solution is {} not {} (no prior FIX decision,"
              + " replay or race)",
          executionRunId,
          solutionId,
          s.getStatus(),
          AWAITING_MANUAL_FIX);
      return DecisionOutcome.ILLEGAL_STATE;
    }

    ExecutionSolution fresh = solutionRepo.findById(solutionId).orElse(s);
    ExecutionRun run = fresh.getExecutionRun();
    if (run != null) {
      jiraSync.updateTicket(run, fresh, "Manual fix signalled as complete.", "Done");
      emit(run.getId(), ExecutionEventTypes.EXEC_SOLUTION_DONE, fresh.getId());
      resume(run);
    }
    return DecisionOutcome.APPLIED;
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
