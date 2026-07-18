package com.navneet.executespec.codereview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codereview.entity.CodeReviewScan;
import com.navneet.executespec.codereview.repository.CodeReviewIssueRepository;
import com.navneet.executespec.codereview.repository.CodeReviewScanRepository;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.reviewjob.ReviewBackgroundJobStore;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ProjectProviderResolver;
import com.navneet.executespec.router.RunModelOverrides;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeReviewConfirmedRouteTest {

  @TempDir Path workspace;

  @Test
  void confirmedRouteIsPersistedAndRestoredByDurableWorker() throws Exception {
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
    when(scans.findByProjectIdAndRepoIdAndScannedSha(anyString(), any(), anyString()))
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

    AtomicReference<CodeReviewAgent.AgentRequest> confirmedRequest = new AtomicReference<>();
    AtomicReference<Map<String, String>> confirmedRoute = new AtomicReference<>();
    when(agent.review(any()))
        .thenAnswer(
            invocation -> {
              CodeReviewAgent.AgentRequest request = invocation.getArgument(0);
              confirmedRequest.set(request);
              confirmedRoute.set(RunModelOverrides.capture());
              throw new CodeReviewAgent.CodeReviewAgentException("stop after route capture");
            });

    ReviewBackgroundJobStore jobs = mock(ReviewBackgroundJobStore.class);
    AtomicReference<ReviewBackgroundJobStore.Job> persistedJob = new AtomicReference<>();
    when(jobs.createOrGet(any()))
        .thenAnswer(
            invocation -> {
              ReviewBackgroundJobStore.Job job = invocation.getArgument(0);
              persistedJob.set(job);
              return job;
            });
    when(jobs.updateCodeReviewStatus(any(), anyString())).thenReturn(true);
    ProjectRepository projects = mock(ProjectRepository.class);
    when(projects.findById("project-1"))
        .thenReturn(
            Optional.of(
                Project.builder()
                    .id("project-1")
                    .orgId("org-1")
                    .createdBy("developer-1")
                    .name("p")
                    .build()));
    AppUserRepository users = mock(AppUserRepository.class);
    when(users.findById("developer-1")).thenReturn(Optional.of(user));

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
            providers,
            jobs,
            mock(LlmExecutionPolicyFactory.class),
            mock(ModelResolver.class),
            projects,
            users,
            new com.fasterxml.jackson.databind.ObjectMapper());
    LlmExecutionReference reference =
        new LlmExecutionReference("ctx-confirmed", LlmExecutionScope.customer("org-1"));
    CodeReviewScan scan =
        service.submitConfirmedScan(
            "project-1",
            repoId,
            "org/repo",
            user,
            LlmProvider.DEEPSEEK,
            "deepseek-v4-pro-native",
            reference);
    assertThat(persistedJob.get().subjectId()).isEqualTo(scan.getId());
    assertThat(persistedJob.get().provider()).isEqualTo("DEEPSEEK");
    assertThat(persistedJob.get().model()).isEqualTo("deepseek-v4-pro-native");
    assertThat(persistedJob.get().executionContextId()).isEqualTo("ctx-confirmed");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                service.runDurableJob(
                    new ReviewBackgroundJobStore.Lease(
                        persistedJob.get(), "worker", 1, java.time.Instant.now().plusSeconds(60)),
                    "org/repo",
                    "a".repeat(40),
                    () -> {}))
        .isInstanceOf(IllegalStateException.class);

    assertThat(confirmedRequest.get().provider()).isEqualTo("deepseek");
    assertThat(confirmedRequest.get().model()).isEqualTo("deepseek-v4-pro-native");
    assertThat(confirmedRequest.get().actorUserId()).isEqualTo("developer-1");
    assertThat(confirmedRequest.get().executionReference()).isEqualTo(reference);
    for (AgentTier tier : AgentTier.values()) {
      assertThat(confirmedRoute.get().get(RunModelOverrides.key(LlmProvider.DEEPSEEK, tier)))
          .isEqualTo("deepseek-v4-pro-native");
    }

    var lease =
        new ReviewBackgroundJobStore.Lease(
            persistedJob.get(), "stale-worker", 1, java.time.Instant.now().minusSeconds(1));
    doThrow(new ReviewBackgroundJobStore.LeaseFencedException())
        .when(jobs)
        .requireLiveLeaseForUpdate(lease);
    var prepared =
        new CodeReviewService.DurableCodeReviewResult(
            new SemgrepRunner.ScanResult(false, List.of(), 0, "test", 1),
            new CodeReviewAgent.AgentResult("ok", List.of(), List.of(), 1, "deepseek", "model"),
            0,
            0,
            1);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.publishDurableResult(lease, prepared))
        .isInstanceOf(ReviewBackgroundJobStore.LeaseFencedException.class);
    verify(scans, times(1)).save(any(CodeReviewScan.class));
  }
}
