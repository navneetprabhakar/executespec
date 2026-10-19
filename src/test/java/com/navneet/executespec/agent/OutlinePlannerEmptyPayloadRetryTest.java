package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.router.*;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class OutlinePlannerEmptyPayloadRetryTest {
  private ChatResponse response(String args) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall(
                                "call-plan", "function", "submitOutline", args)))
                    .build())));
  }

  private OutlinePlannerAgent agent(LlmRouter router) throws Exception {
    var resolver = mock(ModelResolver.class);
    when(resolver.resolve("OutlinePlannerAgent", LlmProvider.ANTHROPIC))
        .thenReturn(
            ResolvedModel.builder()
                .actualProvider(LlmProvider.ANTHROPIC)
                .model("claude-sonnet-5-5")
                .build());
    var prompts = mock(PromptAssembler.class);
    when(prompts.assemble(anyString())).thenReturn("Emit the complete outline schema.");
    return new OutlinePlannerAgent(
        router,
        new ObjectMapper(),
        mock(com.navneet.executespec.observability.Metrics.class),
        resolver,
        mock(com.navneet.executespec.config.featureflag.FeatureFlagService.class),
        prompts,
        mock(RunTitleSummarizer.class));
  }

  private PlannerRequest request() {
    return PlannerRequest.builder()
        .spec("Build a small Python library")
        .classification(Classification.MEDIUM)
        .planStrategy(PlanStrategy.SMALL)
        .provider("anthropic")
        .model("claude-sonnet-5-5")
        .context(new ExecutionContext("run", "plan", Path.of("/tmp/run")))
        .build();
  }

  @Test
  void zeroTaskNativePayloadGetsOneExactRouteSchemaRetryEvenWithGraphGateOff() throws Exception {
    var router = mock(LlmRouter.class);
    when(router.call(any(LlmRequest.class)))
        .thenReturn(
            response("{\"tasks\":[]}"),
            response(
                "{\"projectType\":\"python\",\"runtimeSurface\":\"LIBRARY\",\"tasks\":[{\"id\":\"T1\",\"title\":\"Implement"
                    + " greeting\",\"description\":\"Implement greeting with tests\"}]}"));
    Plan plan = agent(router).plan(request());
    assertThat(plan.tasks()).hasSize(1);
    var calls = ArgumentCaptor.forClass(LlmRequest.class);
    verify(router, times(2)).call(calls.capture());
    assertThat(calls.getAllValues())
        .allSatisfy(
            call -> {
              assertThat(call.provider()).isEqualTo("anthropic");
              assertThat(call.model()).isEqualTo("claude-sonnet-5-5");
            });
    assertThat(calls.getAllValues().get(1).stepId()).isEqualTo("planner.outline.schema-repair");
  }

  @Test
  void repeatedZeroTaskPayloadFailsWithoutExecutingOrSyntheticScopeCollapse() throws Exception {
    var router = mock(LlmRouter.class);
    when(router.call(any(LlmRequest.class))).thenReturn(response("{}"));
    assertThatThrownBy(() -> agent(router).plan(request())).hasMessageContaining("non-empty tasks");
    verify(router, times(2)).call(any(LlmRequest.class));
  }

  @Test
  void nullAndEmptyPlansAreSchemaFailuresButValidPlansRemainIdentical() {
    for (Plan plan : java.util.Arrays.asList(null, Plan.builder().tasks(List.of()).build())) {
      var failure = catchThrowable(() -> OutlinePlannerSchemaRepairPolicy.requireExecutable(plan));
      assertThat(OutlinePlannerSchemaRepairPolicy.isMalformedToolPayload(failure)).isTrue();
    }
    var plan =
        Plan.builder()
            .tasks(List.of(PlannedTask.builder().id("T1").title("Build").build()))
            .build();
    assertThat(OutlinePlannerSchemaRepairPolicy.requireExecutable(plan)).isSameAs(plan);
  }
}
