package com.navneet.executespec.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.ToolProtocolLeakGuard;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ChatServiceToolLoopTest {

  @Test
  void priorHistoryIsBoundedWithoutDeletingThePersistedTranscript() {
    List<ChatService.ChatMessage> history = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      history.add(
          new ChatService.ChatMessage(i % 2 == 0 ? "user" : "assistant", "x".repeat(3_000)));
    }

    List<ChatService.ChatMessage> bounded = ChatService.boundedHistoryForPrompt(history);

    assertThat(bounded).hasSizeLessThanOrEqualTo(ChatService.MAX_HISTORY_MESSAGES_IN_PROMPT);
    assertThat(bounded.stream().mapToInt(m -> m.content().length()).sum())
        .isLessThanOrEqualTo(ChatService.MAX_HISTORY_CHARS_IN_PROMPT);
    assertThat(history).hasSize(30);
    assertThat(bounded.get(bounded.size() - 1).role()).isEqualTo("assistant");
  }

  @Test
  void promptRequiresFinalEvidencePrecedenceAndForbidsSpeculativeRootCause() {
    ChatService service =
        new ChatService(
            mock(PipelineRunRepository.class),
            mock(LlmRouter.class),
            mock(ModelResolver.class),
            new AgentProperties(),
            mock(ValidationReportRepository.class),
            mock(RunChatTools.class));

    assertThat(service.buildSystemPrompt())
        .contains("timestamped build/test result")
        .contains("evidence supersedes earlier evidence")
        .contains("Worker-authored tests are diagnostic, not independent proof")
        .contains("finalBuildPassed` proves only that the configured build command passed")
        .contains("any test process ran or exited successfully")
        .contains("it cannot call the behavior correct or satisfied")
        .contains("Separate facts from hypotheses")
        .contains("Never infer framework behavior from one stack trace")
        .contains("PLANNER_UNAVAILABLE")
        .contains("Never translate it to \"provider unavailable\"")
        .contains("task expansion, and coherence are all planning phases")
        .contains("it is not worker code", "generation ran")
        .contains("workerCodeGenerationState")
        .contains("empty workspace does not prove that no planning output was drafted");
  }

  @Test
  void toolRoundLimitForcesFinalSynthesisInsteadOfReturningPreparatoryNarration() {
    LlmRouter router = mock(LlmRouter.class);
    ChatResponse toolTurn =
        ChatResponse.builder()
            .generations(
                List.of(
                    new Generation(
                        AssistantMessage.builder()
                            .content("Let me inspect another file.")
                            .toolCalls(
                                List.of(
                                    new AssistantMessage.ToolCall(
                                        "call-1", "function", "read_run_file", "{}")))
                            .build())))
            .build();
    ChatResponse finalTurn =
        ChatResponse.builder()
            .generations(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "The run failed because its configured build command was dependency"
                                + " installation, not compilation."))))
            .build();
    when(router.call(org.mockito.ArgumentMatchers.any()))
        .thenReturn(toolTurn, toolTurn, toolTurn, toolTurn, finalTurn);
    ChatService service =
        new ChatService(
            mock(PipelineRunRepository.class),
            router,
            mock(ModelResolver.class),
            new AgentProperties(),
            mock(ValidationReportRepository.class),
            mock(RunChatTools.class));
    List<Message> seed = List.of(new UserMessage("Why did the run fail?"));

    ChatService.ChatAnswer answer =
        service.runToolLoop(
            "run-1",
            "deepseek",
            "deepseek-v4-flash-native",
            seed,
            new ExecutionContext("run-1", "chat", Path.of(".").toAbsolutePath().normalize()),
            List.of());

    assertThat(answer.text())
        .contains("configured build command")
        .doesNotContain("Let me inspect", "tool-call limit");
    assertThat(answer.complete()).isTrue();
    verify(router, times(5)).call(org.mockito.ArgumentMatchers.any());
    ArgumentCaptor<LlmRequest> requests = ArgumentCaptor.forClass(LlmRequest.class);
    verify(router, times(5)).call(requests.capture());
    assertThat(requests.getAllValues().subList(0, 4))
        .allSatisfy(request -> assertThat(request.maxTokens()).isEqualTo(3000));
    LlmRequest synthesis = requests.getAllValues().get(4);
    assertThat(synthesis.maxTokens()).isEqualTo(3000);
    assertThat(synthesis.toolChoice()).isEqualTo("none");
    assertThat(synthesis.toolsOverride()).isEmpty();
  }

  @Test
  void textualProviderToolProtocolIsSuppressedAndRetriedWithoutTools() {
    LlmRouter router = mock(LlmRouter.class);
    ChatResponse leakedTurn =
        ChatResponse.builder()
            .generations(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "<｜｜DSML｜｜tool_calls>\n<｜｜DSML｜｜invoke name=\"read_run_file\">"))))
            .build();
    ChatResponse finalTurn =
        ChatResponse.builder()
            .generations(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "Fact: the deterministic test result failed 1 of 10 tests. "
                                + "The root cause is not proven."))))
            .build();
    when(router.call(org.mockito.ArgumentMatchers.any())).thenReturn(leakedTurn, finalTurn);
    ChatService service =
        new ChatService(
            mock(PipelineRunRepository.class),
            router,
            mock(ModelResolver.class),
            new AgentProperties(),
            mock(ValidationReportRepository.class),
            mock(RunChatTools.class));

    ChatService.ChatAnswer answer =
        service.runToolLoop(
            "run-1",
            "deepseek",
            "deepseek-v4-flash-native",
            List.of(new UserMessage("Did it pass?")),
            new ExecutionContext("run-1", "chat", Path.of(".").toAbsolutePath().normalize()),
            List.of());

    assertThat(answer.text())
        .contains("failed 1 of 10")
        .doesNotContain("DSML", "tool_calls", "read_run_file");
    assertThat(answer.complete()).isTrue();
    ArgumentCaptor<LlmRequest> requests = ArgumentCaptor.forClass(LlmRequest.class);
    verify(router, times(2)).call(requests.capture());
    assertThat(requests.getAllValues().get(1).toolsOverride()).isEmpty();
    assertThat(requests.getAllValues().get(1).toolChoice()).isEqualTo("none");
  }

  @Test
  void textualProviderToolProtocolCannotLeakFromFinalSynthesis() {
    assertThat(
            ToolProtocolLeakGuard.containsProtocolMarkup(
                "<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name=\"read_run_file\">"))
        .isTrue();
    assertThat(ToolProtocolLeakGuard.containsProtocolMarkup("The report mentions tool calls."))
        .isFalse();
  }

  @Test
  void directLengthLimitedAnswerIsTypedIncompleteAndActionable() {
    LlmRouter router = mock(LlmRouter.class);
    when(router.call(org.mockito.ArgumentMatchers.any()))
        .thenReturn(textResponse("Partial answer", "length"));
    ChatService service =
        new ChatService(
            mock(PipelineRunRepository.class),
            router,
            mock(ModelResolver.class),
            new AgentProperties(),
            mock(ValidationReportRepository.class),
            mock(RunChatTools.class));

    ChatService.ChatAnswer answer =
        service.runToolLoop(
            "run-1",
            "openai",
            "gpt-test",
            List.of(new UserMessage("Explain every file")),
            new ExecutionContext("run-1", "chat", Path.of(".").toAbsolutePath().normalize()),
            List.of());

    assertThat(answer.complete()).isFalse();
    assertThat(answer.incompleteReason()).isEqualTo(ChatService.OUTPUT_LIMIT_REASON);
    assertThat(answer.text())
        .contains("Partial answer", "answer is incomplete", "specific file or evidence");
  }

  @Test
  void truncatedTextualToolProtocolIsTypedIncompleteButNeverExposed() {
    LlmRouter router = mock(LlmRouter.class);
    when(router.call(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            textResponse(
                "<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name=\"read_run_file\">", "max_tokens"));
    ChatService service =
        new ChatService(
            mock(PipelineRunRepository.class),
            router,
            mock(ModelResolver.class),
            new AgentProperties(),
            mock(ValidationReportRepository.class),
            mock(RunChatTools.class));

    ChatService.ChatAnswer answer =
        service.runToolLoop(
            "run-1",
            "deepseek",
            "deepseek-test",
            List.of(new UserMessage("Explain every file")),
            new ExecutionContext("run-1", "chat", Path.of(".").toAbsolutePath().normalize()),
            List.of());

    assertThat(answer.complete()).isFalse();
    assertThat(answer.incompleteReason()).isEqualTo(ChatService.OUTPUT_LIMIT_REASON);
    assertThat(answer.text())
        .contains("Internal tool protocol was suppressed", "answer is incomplete")
        .doesNotContain("DSML", "tool_calls", "read_run_file");
    verify(router, times(1)).call(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void missingFinishMetadataIsUnknownAndDoesNotCrashAsk() {
    ChatResponse response =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Complete answer"), null)));

    assertThat(ChatService.responseWasTruncated(response)).isFalse();
  }

  @Test
  void finalSynthesisMaxTokensIsTypedIncomplete() {
    LlmRouter router = mock(LlmRouter.class);
    ChatResponse toolTurn =
        ChatResponse.builder()
            .generations(
                List.of(
                    new Generation(
                        AssistantMessage.builder()
                            .content("Inspecting.")
                            .toolCalls(
                                List.of(
                                    new AssistantMessage.ToolCall(
                                        "call-1", "function", "read_run_file", "{}")))
                            .build())))
            .build();
    when(router.call(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            toolTurn,
            toolTurn,
            toolTurn,
            toolTurn,
            textResponse("Evidence-based prefix", "MAX_TOKENS"));
    ChatService service =
        new ChatService(
            mock(PipelineRunRepository.class),
            router,
            mock(ModelResolver.class),
            new AgentProperties(),
            mock(ValidationReportRepository.class),
            mock(RunChatTools.class));

    ChatService.ChatAnswer answer =
        service.runToolLoop(
            "run-1",
            "gemini",
            "gemini-test",
            List.of(new UserMessage("Why?")),
            new ExecutionContext("run-1", "chat", Path.of(".").toAbsolutePath().normalize()),
            List.of());

    assertThat(answer.complete()).isFalse();
    assertThat(answer.incompleteReason()).isEqualTo(ChatService.OUTPUT_LIMIT_REASON);
    assertThat(answer.text()).contains("Evidence-based prefix", "output limit");
  }

  @Test
  void truncatedProtocolFromFinalSynthesisIsSuppressedAndRemainsTypedIncomplete() {
    LlmRouter router = mock(LlmRouter.class);
    ChatResponse toolTurn =
        ChatResponse.builder()
            .generations(
                List.of(
                    new Generation(
                        AssistantMessage.builder()
                            .content("Inspecting.")
                            .toolCalls(
                                List.of(
                                    new AssistantMessage.ToolCall(
                                        "call-1", "function", "read_run_file", "{}")))
                            .build())))
            .build();
    when(router.call(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            toolTurn,
            toolTurn,
            toolTurn,
            toolTurn,
            textResponse(
                "<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name=\"read_run_file\">", "MAX_TOKENS"));
    ChatService service =
        new ChatService(
            mock(PipelineRunRepository.class),
            router,
            mock(ModelResolver.class),
            new AgentProperties(),
            mock(ValidationReportRepository.class),
            mock(RunChatTools.class));

    ChatService.ChatAnswer answer =
        service.runToolLoop(
            "run-1",
            "gemini",
            "gemini-test",
            List.of(new UserMessage("Why?")),
            new ExecutionContext("run-1", "chat", Path.of(".").toAbsolutePath().normalize()),
            List.of());

    assertThat(answer.complete()).isFalse();
    assertThat(answer.incompleteReason()).isEqualTo(ChatService.OUTPUT_LIMIT_REASON);
    assertThat(answer.text())
        .contains("could not produce a grounded answer", "output limit")
        .doesNotContain("DSML", "tool_calls", "read_run_file");
  }

  private static ChatResponse textResponse(String text, String finishReason) {
    Generation generation =
        new Generation(
            new AssistantMessage(text),
            ChatGenerationMetadata.builder().finishReason(finishReason).build());
    return new ChatResponse(List.of(generation));
  }
}
