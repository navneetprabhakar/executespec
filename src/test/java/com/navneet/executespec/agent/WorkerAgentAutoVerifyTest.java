package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.Tool;
import com.navneet.executespec.tool.ToolRegistry;
import com.navneet.executespec.tool.ToolResult;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** Pins the schema-free edit-burst auto-verify completion boundary (V255, flag default OFF). */
class WorkerAgentAutoVerifyTest {

  private static AssistantMessage.ToolCall call(String name, String args) {
    return new AssistantMessage.ToolCall("call_" + UUID.randomUUID(), "function", name, args);
  }

  private static ChatResponse response(AssistantMessage.ToolCall... calls) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
  }

  private record Fixture(WorkerAgent worker, WorkspaceTestRunner runner) {}

  private static TestExecutionSummary summary(int total, int passed, int failed, int errors) {
    return TestExecutionSummary.builder()
        .totalTests(total)
        .passed(passed)
        .failed(failed)
        .errors(errors)
        .build();
  }

  private Fixture fixture(TestExecutionSummary summary, int maxIterations) {
    LlmRouter router = mock(LlmRouter.class);
    ToolRegistry registry = mock(ToolRegistry.class);
    ContextStore contextStore = mock(ContextStore.class);
    Metrics metrics = mock(Metrics.class);
    RunCostTracker costTracker = mock(RunCostTracker.class);
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    WorkspaceTestRunner runner = mock(WorkspaceTestRunner.class);
    Tool write = mock(Tool.class);
    PipelineRun run = new PipelineRun();
    run.setHardCapUsd(null);

    lenient().when(runRepo.findById(anyString())).thenReturn(Optional.of(run));
    lenient().when(registry.isRemoteRun(any())).thenReturn(false);
    when(write.name()).thenReturn("writeFile");
    when(write.inputSchema())
        .thenReturn(
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},"
                + "\"required\":[\"path\"],\"additionalProperties\":false}");
    when(write.execute(any(), any())).thenReturn(ToolResult.ok("wrote"));
    lenient().when(registry.find("writeFile")).thenReturn(write);
    lenient()
        .when(runner.runTestsDetailed(anyString(), any()))
        .thenReturn(
            Optional.of(
                new WorkspaceTestRunner.DetailedResult(
                    summary,
                    "FAILED test_contract — AssertionError",
                    "python3 -m pytest -q",
                    summary.getFailed() > 0 || summary.getErrors() > 0 ? 1 : 0)));
    when(router.call(any()))
        .thenReturn(
            response(call("writeFile", "{\"path\":\"src/app.py\"}")),
            response(call("completeTask", "{\"summary\":\"done\"}")),
            response(call("completeTask", "{\"summary\":\"done\"}")),
            response(call("completeTask", "{\"summary\":\"done\"}")),
            response(call("completeTask", "{\"summary\":\"done\"}")),
            response(call("completeTask", "{\"summary\":\"done\"}")));

    WorkerAgent worker =
        new WorkerAgent(
            router,
            registry,
            new ObjectMapper(),
            contextStore,
            metrics,
            costTracker,
            runRepo,
            mock(com.navneet.executespec.sse.SseBroadcaster.class),
            null,
            null,
            runner);
    return new Fixture(worker, runner);
  }

  private static WorkerTaskRequest request() {
    return WorkerTaskRequest.builder()
        .provider("deepseek")
        .model("deepseek-v4-flash")
        .systemPrompt("system")
        .userPrompt("fix")
        .context(new ExecutionContext("run-auto-verify", "T1", Path.of("/tmp/ws-auto-verify")))
        .maxIterations(20)
        .temperature(0.0)
        .maxTokensPerCall(4096)
        .stagnationWarnAfter(20)
        .stagnationForceAfter(20)
        .autoVerify(true)
        .build();
  }

  @Test
  void runs_suite_on_completion_intent_after_write_and_refuses_red_result() {
    Fixture f = fixture(summary(5, 3, 2, 0), 20);

    WorkerResult result = f.worker.run(request());

    assertThat(result.outcome()).isEqualTo(WorkerResult.Outcome.COMPLETED);
    assertThat(result.iterations()).isEqualTo(4);
    assertThat(result.toolCallLog()).anyMatch(line -> line.contains("AUTO_VERIFY"));
    verify(f.runner, org.mockito.Mockito.times(2)).runTestsDetailed(anyString(), any());
  }

  @Test
  void permits_green_completion_after_write() {
    Fixture f = fixture(summary(5, 5, 0, 0), 20);

    WorkerResult result = f.worker.run(request());

    assertThat(result.outcome()).isEqualTo(WorkerResult.Outcome.COMPLETED);
    assertThat(result.iterations()).isEqualTo(3);
    verify(f.runner).runTestsDetailed(anyString(), any());
  }
}
