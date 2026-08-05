package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.reviewer.ReviewerResult;

import org.junit.jupiter.api.Test;

class TerminalStatusResolverTest {

  @Test
  void abortedExecutionPreservesWorkerBudgetOutcome() {
    WorkerResult worker =
        WorkerResult.builder().outcome(WorkerResult.Outcome.BUDGET_EXCEEDED).build();
    TaskOutcome task =
        TaskOutcome.builder()
            .taskId("T1")
            .status(TaskOutcome.Status.WORKER_FAILED)
            .workerOutcome(worker)
            .build();
    ExecutionResult execution =
        ExecutionResult.builder()
            .overall(ExecutionResult.Overall.ABORTED)
            .perTask(List.of(task))
            .build();

    var result = TerminalStatusResolver.resolve(execution, null);

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.reason()).isEqualTo("WORKER_BUDGET_EXCEEDED");
  }

  @Test
  void cleanReviewerCannotEraseFinalTaskWarning() {
    ExecutionResult execution =
        ExecutionResult.builder()
            .overall(ExecutionResult.Overall.PARTIAL_WITH_WARNINGS)
            .tasksExecuted(1)
            .tasksWithWarning(1)
            .perTask(List.of())
            .finalTaskStatus(TaskOutcome.Status.COMPLETED_WITH_WARNING)
            .build();
    ReviewerResult reviewer =
        ReviewerResult.builder()
            .overall(ReviewerResult.Overall.CLEAN)
            .finalBuildPassed(true)
            .build();

    var result = TerminalStatusResolver.resolve(execution, reviewer);

    assertThat(result.status()).isEqualTo("PARTIAL");
    assertThat(result.reason()).isEqualTo("FINAL_TASK_WARNING");
  }
}
