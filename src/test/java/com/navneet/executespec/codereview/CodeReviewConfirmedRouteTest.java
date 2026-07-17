package com.navneet.executespec.codereview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.codereview.entity.CodeReviewScan;
import com.navneet.executespec.codereview.repository.CodeReviewIssueRepository;
import com.navneet.executespec.codereview.repository.CodeReviewScanRepository;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ProjectProviderResolver;
import com.navneet.executespec.router.RunModelOverrides;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeReviewConfirmedRouteTest {

  @TempDir Path workspace;

  @Test
  void confirmedRouteCrossesDispatchPoolAndDoesNotLeakIntoNextScan() throws Exception {
    CodeReviewScanRepository scans = mock(CodeReviewScanRepository.class);
    CodeReviewIssueRepository issues = mock(CodeReviewIssueRepository.class);
    SemgrepRunner semgrep = mock(SemgrepRunner.class);
    CodeReviewAgent agent = mock(CodeReviewAgent.class);
    WorkspaceInitService workspaces = mock(WorkspaceInitService.class);
    ProjectRepoRepository repos = mock(ProjectRepoRepository.class);
    GitHubRepoService github = mock(GitHubRepoService.class);
    ProjectProviderResolver providers = mock(ProjectProviderResolver.class);
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo = ProjectRepo.builder().id(repoId).githubRepoFullName("org/repo").build();
    AppUser user =
        AppUser.builder()
            .id("developer-1")
            .orgId("org-1")
            .email("developer@example.com")
            .globalRole("USER")
            .status("ACTIVE")
            .build();
    Map<UUID, CodeReviewScan> rows = new ConcurrentHashMap<>();
    when(github.getDefaultBranch(anyString(), any())).thenReturn("main");
    when(github.getBranchHeadSha(anyString(), anyString(), any())).thenReturn("a".repeat(40));
    when(scans.findFirstByProjectIdAndRepoFullNameAndStatusOrderByStartedAtDesc(
            anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(scans.save(any(CodeReviewScan.class)))
        .thenAnswer(
            invocation -> {
              CodeReviewScan row = invocation.getArgument(0);
              rows.put(row.getId(), row);
              return row;
            });
    when(scans.findById(any()))
        .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.getArgument(0))));
    when(repos.findById(repoId)).thenReturn(Optional.of(repo));
    doNothing().when(workspaces).cloneFromRepo(anyString(), anyString(), any());
    when(workspaces.runWorkspacePath(anyString())).thenReturn(workspace);
    when(semgrep.scan(workspace))
        .thenReturn(new SemgrepRunner.ScanResult(false, List.of(), 0, "test", 1));
    when(providers.resolve("project-1")).thenReturn(LlmProvider.ANTHROPIC);

    AtomicInteger calls = new AtomicInteger();
    AtomicReference<CodeReviewAgent.AgentRequest> confirmedRequest = new AtomicReference<>();
    AtomicReference<Map<String, String>> confirmedRoute = new AtomicReference<>();
    AtomicReference<CodeReviewAgent.AgentRequest> normalRequest = new AtomicReference<>();
    AtomicReference<Map<String, String>> normalRoute = new AtomicReference<>();
    CountDownLatch confirmedSeen = new CountDownLatch(1);
    CountDownLatch normalSeen = new CountDownLatch(1);
    when(agent.review(any()))
        .thenAnswer(
            invocation -> {
              CodeReviewAgent.AgentRequest request = invocation.getArgument(0);
              if (calls.getAndIncrement() == 0) {
                confirmedRequest.set(request);
                confirmedRoute.set(RunModelOverrides.capture());
                confirmedSeen.countDown();
              } else {
                normalRequest.set(request);
                normalRoute.set(RunModelOverrides.capture());
                normalSeen.countDown();
              }
              throw new CodeReviewAgent.CodeReviewAgentException("stop after route capture");
            });

    CodeReviewService service =
        new CodeReviewService(
            scans,
            issues,
            semgrep,
            agent,
            workspaces,
            mock(RunCostTracker.class),
            mock(SseBroadcaster.class),
            repos,
            github,
            mock(com.navneet.executespec.auth.repository.TierConfigRepository.class),
            providers);
    LlmExecutionReference reference =
        new LlmExecutionReference("ctx-confirmed", LlmExecutionScope.customer("org-1"));
    try {
      service.submitConfirmedScan(
          "project-1",
          repoId,
          "org/repo",
          user,
          LlmProvider.DEEPSEEK,
          "deepseek-v4-pro-native",
          reference);
      assertThat(confirmedSeen.await(5, TimeUnit.SECONDS)).isTrue();

      service.submitScan("project-1", repoId, "org/repo", user);
      assertThat(normalSeen.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      service.shutdown();
    }

    assertThat(confirmedRequest.get().provider()).isEqualTo("deepseek");
    assertThat(confirmedRequest.get().model()).isEqualTo("deepseek-v4-pro-native");
    assertThat(confirmedRequest.get().actorUserId()).isEqualTo("developer-1");
    assertThat(confirmedRequest.get().executionReference()).isEqualTo(reference);
    for (AgentTier tier : AgentTier.values()) {
      assertThat(confirmedRoute.get().get(RunModelOverrides.key(LlmProvider.DEEPSEEK, tier)))
          .isEqualTo("deepseek-v4-pro-native");
    }
    assertThat(normalRequest.get().provider()).isEqualTo("anthropic");
    assertThat(normalRequest.get().model()).isNull();
    assertThat(normalRequest.get().executionReference()).isNull();
    assertThat(normalRoute.get()).isEmpty();
  }
}
