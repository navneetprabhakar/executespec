package com.navneet.executespec.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ChatServiceRouteTest {

  @Test
  void answersDeterministicPlannerRejectionWithoutProviderDispatchOrFalseOutage() {
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    LlmRouter router = mock(LlmRouter.class);
    ModelResolver resolver = mock(ModelResolver.class);
    AgentProperties props = new AgentProperties();
    props.setWorkspace(System.getProperty("java.io.tmpdir"));
    ValidationReportRepository reports = mock(ValidationReportRepository.class);
    RunChatTools tools = mock(RunChatTools.class);
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-plan-rejected")
            .status("FAILED")
            .statusReason("PLANNER_UNAVAILABLE")
            .runMode("BUILD")
            .aiProvider("DEEPSEEK")
            .requestedProvider("DEEPSEEK")
            .plannerProvider("DEEPSEEK")
            .plannerActualModel("deepseek-v4-flash-native")
            .expansionOutputTokens(1200L)
            .planOutlineJson("{\"tasks\":[]}")
            .failureReason(
                "Plan validation rejected: T4: POST /api/leases/{id}/release has no producer")
            .build();
    when(runs.findById("run-plan-rejected")).thenReturn(Optional.of(run));

    ChatService.SendResult result =
        new ChatService(runs, router, resolver, props, reports, tools)
            .send(
                "run-plan-rejected",
                "Did worker code generation start, and was the provider unavailable?");

    assertThat(result.reply())
        .contains("Worker code generation did not start")
        .contains("POST /api/leases/{id}/release has no producer")
        .contains("Provider availability was not the recorded cause")
        .contains("does not mean a provider outage")
        .doesNotContain("unreachable", "returned no response");
    assertThat(run.getChatHistoryJson()).contains("POST /api/leases/{id}/release has no producer");
    verify(resolver, never()).resolve(anyString(), any(), any());
    verify(router, never()).call(any());
    verify(runs).save(run);
  }

  @Test
  void projectedPlanValidationReasonAlsoAnswersWithoutProviderDispatch() {
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    LlmRouter router = mock(LlmRouter.class);
    ModelResolver resolver = mock(ModelResolver.class);
    AgentProperties props = new AgentProperties();
    props.setWorkspace(System.getProperty("java.io.tmpdir"));
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-plan-validation-rejected")
            .status("FAILED")
            .statusReason("PLANNER_UNAVAILABLE")
            .runMode("BUILD")
            .failureReason(
                "Plan validation rejected: T4: POST /api/orders/{id}/reserve has no producer")
            .planOutlineJson("{\"tasks\":[{\"id\":\"T4\"}]}")
            .build();
    when(runs.findById(run.getRunId())).thenReturn(Optional.of(run));

    ChatService.SendResult result =
        new ChatService(
                runs,
                router,
                resolver,
                props,
                mock(ValidationReportRepository.class),
                mock(RunChatTools.class))
            .send(run.getRunId(), "Why did planning fail?");

    assertThat(result.reply())
        .contains("Worker code generation did not start")
        .contains("PLAN_VALIDATION_REJECTED")
        .contains("does not mean a provider outage")
        .doesNotContain("PLANNER_UNAVAILABLE");
    verify(resolver, never()).resolve(anyString(), any(), any());
    verify(router, never()).call(any());
  }

  @Test
  void wrappedCoherenceProviderFailureDoesNotUseDeterministicNoProviderAnswer() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-provider-failed")
            .status("FAILED")
            .statusReason("PLANNER_UNAVAILABLE")
            .runMode("BUILD")
            .failureReason("planning could not produce a safe executable plan")
            .build();

    assertThat(
            RunChatDeterministicAnswer.answer(
                run, "Did planning fail because the provider was unavailable?"))
        .isEmpty();
  }

  @Test
  void dispatchesResolvedFallbackProviderWithItsModel() {
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    LlmRouter router = mock(LlmRouter.class);
    ModelResolver resolver = mock(ModelResolver.class);
    AgentProperties props = new AgentProperties();
    props.setWorkspace(System.getProperty("java.io.tmpdir"));
    ValidationReportRepository reports = mock(ValidationReportRepository.class);
    RunChatTools tools = mock(RunChatTools.class);
    PipelineRun run =
        PipelineRun.builder().runId("run-1").status("COMPLETE").aiProvider("ANTHROPIC").build();
    when(runs.findById("run-1")).thenReturn(Optional.of(run));
    when(reports.findByRunId("run-1")).thenReturn(Optional.empty());
    when(tools.bind(any())).thenReturn(List.of());
    when(resolver.resolve("ChatService", LlmProvider.ANTHROPIC, AgentTier.LIGHTWEIGHT))
        .thenReturn(
            ResolvedModel.builder()
                .requestedProvider(LlmProvider.ANTHROPIC)
                .actualProvider(LlmProvider.OPENAI)
                .model("gpt-fallback")
                .agentTier(AgentTier.LIGHTWEIGHT)
                .fallback(true)
                .build());
    when(router.call(any()))
        .thenReturn(
            ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("answer"))))
                .build());

    new ChatService(runs, router, resolver, props, reports, tools).send("run-1", "question");

    ArgumentCaptor<LlmRequest> dispatched = ArgumentCaptor.forClass(LlmRequest.class);
    verify(router).call(dispatched.capture());
    assertThat(dispatched.getValue().provider()).isEqualTo("openai");
    assertThat(dispatched.getValue().model()).isEqualTo("gpt-fallback");
  }

  /**
   * Phase-6 F1 regression: each chat turn must build a fresh per-turn execution context via {@code
   * forRunChatTurn} (unique turn id), never {@code forRun(runId)} — which reused the run's own
   * terminal pipeline context and threw an idempotency-collision 500 on every turn.
   */
  @Test
  void runChatUsesUniquePerTurnContextNotThePipelineContext() {
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    LlmRouter router = mock(LlmRouter.class);
    ModelResolver resolver = mock(ModelResolver.class);
    AgentProperties props = new AgentProperties();
    props.setWorkspace(System.getProperty("java.io.tmpdir"));
    ValidationReportRepository reports = mock(ValidationReportRepository.class);
    RunChatTools tools = mock(RunChatTools.class);
    LlmExecutionPolicyFactory factory = mock(LlmExecutionPolicyFactory.class);
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-1")
            .status("COMPLETE")
            .aiProvider("ANTHROPIC")
            .createdByUserId("user-1")
            .orgId("org-1")
            .build();
    when(runs.findById("run-1")).thenReturn(Optional.of(run));
    when(reports.findByRunId("run-1")).thenReturn(Optional.empty());
    when(tools.bind(any())).thenReturn(List.of());
    when(resolver.resolve("ChatService", LlmProvider.ANTHROPIC, AgentTier.LIGHTWEIGHT))
        .thenReturn(
            ResolvedModel.builder()
                .requestedProvider(LlmProvider.ANTHROPIC)
                .actualProvider(LlmProvider.ANTHROPIC)
                .model("claude-haiku")
                .agentTier(AgentTier.LIGHTWEIGHT)
                .fallback(false)
                .build());
    when(factory.forRunChatTurn(eq("run-1"), anyString()))
        .thenReturn(new LlmExecutionReference("ctx", LlmExecutionScope.customer("org-1")));
    when(router.call(any()))
        .thenReturn(
            ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("answer"))))
                .build());

    ChatService svc =
        new ChatService(
            runs, router, resolver, props, reports, tools, factory, new ContextSanitizer());
    svc.send("run-1", "first question");
    svc.send("run-1", "second question");

    ArgumentCaptor<String> turnIds = ArgumentCaptor.forClass(String.class);
    verify(factory, times(2)).forRunChatTurn(eq("run-1"), turnIds.capture());
    assertThat(turnIds.getAllValues()).doesNotHaveDuplicates();
    verify(factory, never()).forRun(any());
    ArgumentCaptor<LlmRequest> requests = ArgumentCaptor.forClass(LlmRequest.class);
    verify(router, times(2)).call(requests.capture());
    assertThat(requests.getAllValues())
        .allSatisfy(
            request -> {
              assertThat(request.llmExecutionContextId()).isEqualTo("ctx");
              assertThat(request.llmExecutionScope())
                  .isEqualTo(LlmExecutionScope.customer("org-1"));
            });
  }

  @Test
  void redactsSecretPatternsBeforePersistingOrReturningTheReply() {
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    LlmRouter router = mock(LlmRouter.class);
    ModelResolver resolver = mock(ModelResolver.class);
    AgentProperties props = new AgentProperties();
    props.setWorkspace(System.getProperty("java.io.tmpdir"));
    ValidationReportRepository reports = mock(ValidationReportRepository.class);
    RunChatTools tools = mock(RunChatTools.class);
    PipelineRun run =
        PipelineRun.builder().runId("run-1").status("COMPLETE").aiProvider("DEEPSEEK").build();
    when(runs.findById("run-1")).thenReturn(Optional.of(run));
    when(reports.findByRunId("run-1")).thenReturn(Optional.empty());
    when(tools.bind(any())).thenReturn(List.of());
    when(resolver.resolve("ChatService", LlmProvider.DEEPSEEK, AgentTier.LIGHTWEIGHT))
        .thenReturn(
            ResolvedModel.builder()
                .requestedProvider(LlmProvider.DEEPSEEK)
                .actualProvider(LlmProvider.DEEPSEEK)
                .model("deepseek-v4-flash-native")
                .agentTier(AgentTier.LIGHTWEIGHT)
                .fallback(false)
                .build());
    when(router.call(any()))
        .thenReturn(
            ChatResponse.builder()
                .generations(
                    List.of(
                        new Generation(new AssistantMessage("Example key AKIA1234567890123456"))))
                .build());

    ChatService.SendResult result =
        new ChatService(runs, router, resolver, props, reports, tools)
            .send("run-1", "inspect AKIA1111111111111111");

    assertThat(result.reply()).isEqualTo("Example key [REDACTED]");
    assertThat(run.getChatHistoryJson())
        .contains("[REDACTED]")
        .doesNotContain("AKIA1111111111111111", "AKIA1234567890123456");
  }
}
