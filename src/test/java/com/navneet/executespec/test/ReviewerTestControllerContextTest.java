package com.navneet.executespec.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewerTestControllerContextTest {

  @TempDir Path workspace;

  @Test
  void givesEachSmokeInvocationAnIndependentDurableBudgetIdentity() throws Exception {
    var firstReference =
        new LlmExecutionReference("ctx-first", LlmExecutionScope.platformInternal());
    var secondReference =
        new LlmExecutionReference("ctx-second", LlmExecutionScope.platformInternal());
    var first = ReviewerTestController.freshExecutionContext(workspace, "first", firstReference);
    var second = ReviewerTestController.freshExecutionContext(workspace, "second", secondReference);

    assertThat(first.runId()).isEqualTo("reviewer-test-run-first").isNotEqualTo(second.runId());
    assertThat(first.taskId()).isEqualTo("reviewer-test-task-first").isNotEqualTo(second.taskId());
    assertThat(first.workspaceRoot()).isEqualTo(workspace.toRealPath());
    assertThat(second.workspaceRoot()).isEqualTo(workspace.toRealPath());
    assertThat(first.llmExecutionContextId()).isEqualTo("ctx-first");
    assertThat(second.llmExecutionContextId()).isEqualTo("ctx-second");
    assertThat(first.llmExecutionScope()).isEqualTo(LlmExecutionScope.platformInternal());
    assertThat(second.llmExecutionScope()).isEqualTo(LlmExecutionScope.platformInternal());
  }
}
