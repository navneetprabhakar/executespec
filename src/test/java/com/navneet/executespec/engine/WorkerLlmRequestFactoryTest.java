package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

class WorkerLlmRequestFactoryTest {

  @Test
  void ordinaryWorkerAttributionRemainsUnchanged() {
    LlmRequest request = create(baseRequest(), "WorkerAgent", "worker.iteration-1");

    assertThat(request.agentRole()).isEqualTo("WorkerAgent");
    assertThat(request.stepId()).isEqualTo("worker.iteration-1");
  }

  @Test
  void specializedPassOverridesRoleAndRewritesWorkerStepPrefix() {
    WorkerTaskRequest reviewer =
        baseRequest().toBuilder().agentRole("ReviewerAgent").stepPrefix("reviewer.general").build();

    LlmRequest iteration = create(reviewer, "WorkerAgent", "worker.iteration-2");
    LlmRequest salvage = create(reviewer, "WorkerAgent", "worker.salvage-auto");

    assertThat(iteration.agentRole()).isEqualTo("ReviewerAgent");
    assertThat(iteration.stepId()).isEqualTo("reviewer.general.iteration-2");
    assertThat(salvage.agentRole()).isEqualTo("ReviewerAgent");
    assertThat(salvage.stepId()).isEqualTo("reviewer.general.salvage-auto");
  }

  private static LlmRequest create(
      WorkerTaskRequest request, String fallbackRole, String fallbackStep) {
    return WorkerLlmRequestFactory.create(
        request,
        List.of(new UserMessage("test")),
        fallbackRole,
        fallbackStep,
        0.0,
        100,
        List.of(),
        null,
        10);
  }

  private static WorkerTaskRequest baseRequest() {
    return WorkerTaskRequest.builder()
        .provider("deepseek")
        .model("deepseek-v4-pro-native")
        .userPrompt("review")
        .context(new ExecutionContext("run", "task", Path.of("/tmp/worker-attribution")))
        .build();
  }
}
