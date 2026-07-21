package com.navneet.executespec.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RR-1689 — a late-completing Execution-View child that reaches push after its solution has been
 * retried or skipped must not publish. RR-531 closed this from the parent's side (cancel + fence on
 * RETRY); this closes it from the child's side, at the publication boundary.
 */
class RunPublicationFenceTest {

  private ExecutionSolutionRepository solutionRepo;
  private RunPublicationFence fence;

  @BeforeEach
  void setUp() {
    solutionRepo = mock(ExecutionSolutionRepository.class);
    fence = new RunPublicationFence(solutionRepo);
  }

  private static PipelineRun run(String source) {
    PipelineRun r = new PipelineRun();
    r.setRunId("run-1");
    r.setSourceType(source);
    return r;
  }

  private static ExecutionSolution solution(String status) {
    ExecutionSolution s = new ExecutionSolution();
    s.setId("sol-1");
    s.setStatus(status);
    return s;
  }

  @Test
  void ordinary_customer_run_is_never_fenced() {
    assertTrue(fence.mayPublish(run("WEB")));
    assertTrue(fence.mayPublish(run(null)));
    assertTrue(fence.mayPublish(run("finding:PR_REVIEW")));
  }

  @Test
  void current_attempt_may_publish() {
    when(solutionRepo.findFirstByPipelineRunId("run-1"))
        .thenReturn(Optional.of(solution("RUNNING")));

    assertTrue(fence.mayPublish(run("EXECUTION_VIEW_APPROVED")));
  }

  @Test
  void superseded_attempt_may_not_publish() {
    // A RETRY overwrote pipelineRunId, so no solution names this run any more.
    when(solutionRepo.findFirstByPipelineRunId("run-1")).thenReturn(Optional.empty());

    assertFalse(fence.mayPublish(run("EXECUTION_VIEW_APPROVED")));
  }

  @Test
  void attempt_whose_solution_an_operator_skipped_may_not_publish() {
    when(solutionRepo.findFirstByPipelineRunId("run-1"))
        .thenReturn(Optional.of(solution("SKIPPED")));

    assertFalse(fence.mayPublish(run("EXECUTION_VIEW_APPROVED")));
  }

  @Test
  void repository_failure_fails_open_rather_than_stranding_legitimate_work() {
    when(solutionRepo.findFirstByPipelineRunId(anyString()))
        .thenThrow(new RuntimeException("db down"));

    assertTrue(fence.mayPublish(run("EXECUTION_VIEW_APPROVED")));
  }

  @Test
  void a_null_run_never_publishes() {
    assertFalse(fence.mayPublish(null));
  }
}
