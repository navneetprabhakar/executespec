package com.navneet.executespec.codereview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ProjectProviderResolver;

import org.junit.jupiter.api.Test;

class CodeReviewServiceProviderRoutingTest {

  @Test
  void usesProjectProviderInsteadOfAnthropicConstant() {
    ProjectProviderResolver providerResolver = mock(ProjectProviderResolver.class);
    when(providerResolver.resolve("project-deepseek")).thenReturn(LlmProvider.DEEPSEEK);
    CodeReviewService service =
        new CodeReviewService(
            mock(com.navneet.executespec.codereview.repository.CodeReviewScanRepository.class),
            mock(com.navneet.executespec.codereview.repository.CodeReviewIssueRepository.class),
            mock(SemgrepRunner.class),
            mock(CodeReviewAgent.class),
            mock(com.navneet.executespec.harness.WorkspaceInitService.class),
            mock(com.navneet.executespec.cost.RunCostTracker.class),
            mock(com.navneet.executespec.sse.SseBroadcaster.class),
            mock(com.navneet.executespec.project.repository.ProjectRepoRepository.class),
            mock(com.navneet.executespec.repo.GitHubRepoService.class),
            mock(com.navneet.executespec.auth.repository.TierConfigRepository.class),
            providerResolver);

    assertEquals("deepseek", service.resolveProvider("project-deepseek"));
    service.shutdown();
  }
}
