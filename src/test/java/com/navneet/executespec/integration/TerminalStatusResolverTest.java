package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.reviewer.ReviewerResult;

import org.junit.jupiter.api.Test;

class TerminalStatusResolverTest {

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
