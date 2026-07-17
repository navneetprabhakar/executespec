package com.navneet.executespec.prreview;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.Test;

class PrReviewAgentAttributionTest {

  @Test
  void initiatingProjectMemberIsUsageActorWhileProjectPolicyOwnsCredentials() {
    LlmRouter router = mock(LlmRouter.class);
    ModelResolver modelResolver = mock(ModelResolver.class);
    LlmExecutionPolicyFactory policyFactory = mock(LlmExecutionPolicyFactory.class);
    when(modelResolver.resolve("PrReviewAgent", LlmProvider.DEEPSEEK))
        .thenReturn(
            ResolvedModel.builder()
                .actualProvider(LlmProvider.DEEPSEEK)
                .model("deepseek-v4")
                .build());
    when(router.call(any())).thenThrow(new IllegalStateException("stop after request capture"));
    PrReviewAgent agent =
        new PrReviewAgent(router, modelResolver, new ObjectMapper(), policyFactory);

    PrReviewAgent.AgentRequest request =
        PrReviewAgent.AgentRequest.builder()
            .prReviewId("review-1")
            .projectId("project-1")
            .repoFullName("org/repo")
            .prNumber(42)
            .headSha("a".repeat(40))
            .baseBranch("main")
            .headBranch("feature")
            .title("Change")
            .body("Body")
            .changedFiles(1)
            .additions(2)
            .deletions(1)
            .diff("diff --git a/A.java b/A.java")
            .provider("deepseek")
            .actorUserId("member-1")
            .build();

    assertThatThrownBy(() -> agent.review(request))
        .isInstanceOf(PrReviewAgent.PrReviewAgentException.class);
    verify(policyFactory)
        .forProject(
            "project-1",
            "pr-review",
            "PR_REVIEW",
            "review-1",
            "review-1",
            "deepseek",
            null,
            "member-1");
  }
}
