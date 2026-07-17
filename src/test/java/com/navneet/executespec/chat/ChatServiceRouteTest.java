package com.navneet.executespec.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ChatServiceRouteTest {

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
}
