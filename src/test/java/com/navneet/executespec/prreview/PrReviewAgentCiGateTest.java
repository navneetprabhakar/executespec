package com.navneet.executespec.prreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Pins that the CI gate is applied POST-model inside {@link PrReviewAgent#review} — a model that
 * says APPROVE over failing CI cannot keep the approval — and that CI status is injected into the
 * user prompt as untrusted evidence.
 */
class PrReviewAgentCiGateTest {

  private static final String SHA = "a".repeat(40);

  private final LlmRouter router = mock(LlmRouter.class);
  private final ModelResolver resolver = mock(ModelResolver.class);
  private final LlmExecutionPolicyFactory policies = mock(LlmExecutionPolicyFactory.class);
  private final PrReviewAgent agent =
      new PrReviewAgent(router, resolver, new ObjectMapper(), policies);
  private final LlmExecutionReference reference =
      new LlmExecutionReference("ctx-pr-review", LlmExecutionScope.customer("org-1"));

  @BeforeEach
  void setUp() {
    when(resolver.resolve("PrReviewAgent", LlmProvider.DEEPSEEK))
        .thenReturn(
            ResolvedModel.builder()
                .requestedProvider(LlmProvider.DEEPSEEK)
                .actualProvider(LlmProvider.DEEPSEEK)
                .model("deepseek-v4-pro-native")
                .agentTier(AgentTier.PLANNING)
                .build());
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
                                        "submitReview",
                                        "{\"verdict\":\"APPROVE\",\"summary\":\"Looks good.\","
                                            + "\"comments\":[]}")))
                            .build()))));
  }

  private PrReviewAgent.AgentRequest request(String ciStatus, Integer ciFailingCount) {
    return PrReviewAgent.AgentRequest.builder()
        .prReviewId("review-1")
        .projectId("project-1")
        .repoFullName("org/repo")
        .prNumber(7)
        .headSha(SHA)
        .baseBranch("main")
        .headBranch("feature")
        .title("Change")
        .body("Body")
        .changedFiles(1)
        .additions(2)
        .deletions(1)
        .diff("diff --git a/A.java b/A.java")
        .provider("deepseek")
        .model("deepseek-v4-pro-native")
        .actorUserId("member-1")
        .executionReference(reference)
        .ciStatus(ciStatus)
        .ciFailingCount(ciFailingCount)
        .build();
  }

  @Test
  void modelApprovalOverRedCiIsDeterministicallyCappedAndPromptCarriesCiEvidence() {
    PrReviewAgent.AgentReview result = agent.review(request("FAILURE", 2));

    assertThat(result.verdict()).isEqualTo("REQUEST_CHANGES");
    assertThat(result.summary()).contains("CI failing on head").contains("Looks good.");

    ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(router).call(captor.capture());
    String userPrompt =
        captor.getValue().messages().stream()
            .filter(UserMessage.class::isInstance)
            .map(m -> ((UserMessage) m).getText())
            .findFirst()
            .orElseThrow();
    assertThat(userPrompt)
        .contains("PR_CI_STATUS")
        .contains("Check-run rollup at review time: FAILURE")
        .contains("Failing check-runs: 2");
  }

  @Test
  void unknownCiKeepsModelVerdictButLabelsCiUnverified() {
    PrReviewAgent.AgentReview result = agent.review(request("UNKNOWN", 0));

    assertThat(result.verdict()).isEqualTo("APPROVE");
    assertThat(result.summary()).contains("unverified").contains("Looks good.");
  }

  @Test
  void greenCiLeavesTheModelReviewUnchanged() {
    PrReviewAgent.AgentReview result = agent.review(request("SUCCESS", 0));

    assertThat(result.verdict()).isEqualTo("APPROVE");
    assertThat(result.summary()).isEqualTo("Looks good.");
  }
}
