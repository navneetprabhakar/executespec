package com.navneet.executespec.codereview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class CodeReviewAgentRouteTest {

  private final LlmRouter router = mock(LlmRouter.class);
  private final ModelResolver resolver = mock(ModelResolver.class);
  private final LlmExecutionPolicyFactory policies = mock(LlmExecutionPolicyFactory.class);
  private final CodeReviewAgent agent =
      new CodeReviewAgent(router, resolver, new ObjectMapper(), policies);
  private final LlmExecutionReference reference =
      new LlmExecutionReference("ctx-code-review", LlmExecutionScope.customer("org-1"));

  @BeforeEach
  void setUp() {
    when(router.call(any()))
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        AssistantMessage.builder()
                            .content("")
                            .toolCalls(
                                List.of(
                                    new AssistantMessage.ToolCall(
                                        "call-1",
                                        "function",
                                        "submitCodeReview",
                                        "{\"summary\":\"ok\",\"issues\":[]}")))
                            .build()))));
  }

  @Test
  void confirmedRequestDispatchesExactModelWithExistingBudgetReference() {
    when(resolver.resolve("CodeReviewAgent", LlmProvider.DEEPSEEK))
        .thenReturn(resolved("deepseek-v4-pro-native"));

    CodeReviewAgent.AgentResult result = agent.review(request("deepseek-v4-pro-native", reference));

    ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
    verify(router).call(request.capture());
    assertThat(request.getValue().provider()).isEqualTo("deepseek");
    assertThat(request.getValue().model()).isEqualTo("deepseek-v4-pro-native");
    assertThat(request.getValue().llmExecutionContextId()).isEqualTo("ctx-code-review");
    assertThat(request.getValue().llmExecutionScope()).isEqualTo(reference.scope());
    assertThat(result.model()).isEqualTo("deepseek-v4-pro-native");
    verify(policies, never()).forProject(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void providerOrModelDriftFailsBeforeContextCreationOrDispatch() {
    when(resolver.resolve("CodeReviewAgent", LlmProvider.DEEPSEEK))
        .thenReturn(resolved("different-model"));

    assertThatThrownBy(() -> agent.review(request("deepseek-v4-pro-native", reference)))
        .isInstanceOf(CodeReviewAgent.CodeReviewAgentException.class)
        .hasMessageContaining("route changed");
    verifyNoInteractions(policies);
    verify(router, never()).call(any());
  }

  private static ResolvedModel resolved(String model) {
    return ResolvedModel.builder()
        .requestedProvider(LlmProvider.DEEPSEEK)
        .actualProvider(LlmProvider.DEEPSEEK)
        .model(model)
        .agentTier(AgentTier.PLANNING)
        .build();
  }

  private static CodeReviewAgent.AgentRequest request(
      String model, LlmExecutionReference reference) {
    return CodeReviewAgent.AgentRequest.builder()
        .scanId("scan-1")
        .projectId("project-1")
        .repoFullName("org/repo")
        .scannedSha("a".repeat(40))
        .languages(List.of("java"))
        .fileCount(1)
        .totalLoc(10)
        .staticFindings(List.of())
        .fileSamples(List.of())
        .provider("deepseek")
        .model(model)
        .actorUserId("developer-1")
        .executionReference(reference)
        .build();
  }
}
