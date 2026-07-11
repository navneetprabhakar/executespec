package com.navneet.executespec.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;

import org.junit.jupiter.api.Test;

class WorkerTestControllerTest {

  @Test
  void usesCallerSuppliedRunIdForProviderBudgetAuthorizationScope() throws Exception {
    WorkerAgent worker = mock(WorkerAgent.class);
    when(worker.run(any(WorkerTaskRequest.class)))
        .thenAnswer(
            invocation -> {
              WorkerTaskRequest request = invocation.getArgument(0);
              assertThat(request.context().runId()).isEqualTo("probe-run-42");
              return WorkerResult.builder()
                  .outcome(WorkerResult.Outcome.COMPLETED)
                  .iterations(1)
                  .toolCallsDispatched(0)
                  .completionSummary("ok")
                  .finalAssistantText(null)
                  .repeatedAssistantTextCount(0)
                  .toolCallLog(java.util.List.of())
                  .totalLatencyMs(1)
                  .build();
            });

    var response =
        new WorkerTestController(worker)
            .testWorker("deepseek", "deepseek-v4-flash-native", "probe-run-42");

    assertThat(response).containsEntry("ok", false);
    assertThat(response).containsEntry("sumCorrect", false);
  }
}
