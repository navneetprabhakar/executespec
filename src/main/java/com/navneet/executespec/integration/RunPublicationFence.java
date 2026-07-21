package com.navneet.executespec.integration;

import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RR-1689 (remainder of RR-531) — bind publication authority to the still-current solution attempt.
 *
 * <p>RR-531 made a timed-out Execution-View child run cancellable and made a RETRY fence the
 * superseded child before claiming. That closes the window from the parent's side. It does not
 * close it from the child's side: a late-completing child that reaches auto-push before the
 * cancellation fence lands still publishes. The parent has already moved on — the operator retried,
 * skipped, or paused — so the PR that appears belongs to an attempt nobody is waiting for, opened
 * against the customer's repository, potentially alongside the replacement attempt's own PR.
 *
 * <p>The attempt generation is not a counter here; it is the identity the solution row currently
 * points at. {@code ExecutionSolution.pipelineRunId} names exactly one attempt at a time, and a
 * RETRY overwrites it. So "am I still the current attempt?" is answerable with one read: the
 * solution that names this run id. If nothing names it, this run has been superseded.
 *
 * <p>Deliberately narrow: it fences only runs dispatched by the Execution-View solution path
 * ({@code EXECUTION_VIEW_APPROVED}). Ordinary customer runs have no parent attempt to be superseded
 * by and are unaffected. It also fails OPEN on a repository error — a database blip must not stop a
 * legitimate run from publishing its work, and the paths this protects are already the exception.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunPublicationFence {

  /** Source token {@code SolutionExecutionService} stamps on the child runs it dispatches. */
  private static final String EXECUTION_VIEW_SOURCE = "EXECUTION_VIEW_APPROVED";

  private static final String SKIPPED = "SKIPPED";

  private final ExecutionSolutionRepository solutionRepo;

  /**
   * @return true when this run may still publish; false when it is a superseded or abandoned
   *     Execution-View attempt
   */
  public boolean mayPublish(PipelineRun run) {
    if (run == null) return false;
    if (!EXECUTION_VIEW_SOURCE.equalsIgnoreCase(String.valueOf(run.getSourceType()))) {
      return true;
    }
    ExecutionSolution solution;
    try {
      solution = solutionRepo.findFirstByPipelineRunId(run.getRunId()).orElse(null);
    } catch (Exception e) {
      log.warn(
          "run={} publication fence unavailable, allowing push: {}", run.getRunId(), e.toString());
      return true;
    }
    if (solution == null) {
      log.warn(
          "run={} publication refused — no Execution-View solution still names this attempt"
              + " (superseded by a retry, or the solution row is gone)",
          run.getRunId());
      return false;
    }
    if (SKIPPED.equalsIgnoreCase(String.valueOf(solution.getStatus()))) {
      log.warn(
          "run={} publication refused — solution {} was skipped by an operator decision",
          run.getRunId(),
          solution.getId());
      return false;
    }
    return true;
  }
}
