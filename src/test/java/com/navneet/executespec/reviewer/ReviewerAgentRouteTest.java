package com.navneet.executespec.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewerAgentRouteTest {

  @Test
  void dispatchesTheExactResolvedRouteFromReviewerRequest() {
    WorkerAgent worker = mock(WorkerAgent.class);
    when(worker.run(any()))
        .thenReturn(WorkerResult.builder().outcome(WorkerResult.Outcome.COMPLETED).build());
    ReviewerAgent agent = new ReviewerAgent(worker, null, null, null, null, null, null, null);
    ReviewerRequest request =
        ReviewerRequest.builder()
            .provider("openrouter")
            .model("glm-5.2")
            .strictProvider(true)
            .context(new ExecutionContext("run-1", "review", Path.of(".").toAbsolutePath()))
            .build();

    agent.runPass(
        request,
        3,
        "general",
        "system",
        10,
        null,
        new com.navneet.executespec.tool.ReviewerMutationScope.Lease());

    ArgumentCaptor<WorkerTaskRequest> dispatched = ArgumentCaptor.forClass(WorkerTaskRequest.class);
    verify(worker).run(dispatched.capture());
    assertThat(dispatched.getValue().provider()).isEqualTo("openrouter");
    assertThat(dispatched.getValue().model()).isEqualTo("glm-5.2");
    assertThat(dispatched.getValue().strictProvider()).isTrue();
  }
}
