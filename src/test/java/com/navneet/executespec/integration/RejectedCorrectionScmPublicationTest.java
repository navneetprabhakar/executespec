package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.CompatibilityReport;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.integration.scm.PublicationAdmissionService;
import com.navneet.executespec.integration.scm.ScmPushService;
import com.navneet.executespec.lint.LintPass;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.project.pr.PullRequestRecordService;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.BitbucketRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.saga.SideEffectOutbox;
import com.navneet.executespec.security.ArtifactPublicationGuard;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;
import com.navneet.executespec.tool.GitTool;
import com.navneet.executespec.tool.GitToolResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** Cross-boundary proof that rejected correction bytes cannot reach the first SCM side effect. */
class RejectedCorrectionScmPublicationTest {

  private static final String RUN_ID = "run-rejected-publication";
  private static final String TASK_KEY = RUN_ID.substring(0, 8) + "_T1";

  @TempDir Path tempDir;

  @Test
  void rejectedCorrectionRestoresEvidenceAndBytesBeforeRealPublicationAdmissionAndScm()
      throws Exception {
    Path workspace = Files.createDirectories(tempDir.resolve(RUN_ID));
    git(workspace, "init", "-q");
    git(workspace, "config", "user.email", "test@executespec.invalid");
    git(workspace, "config", "user.name", "ExecuteSpec Test");
    Files.writeString(workspace.resolve("tracked.txt"), "accepted checkpoint\n");
    git(workspace, "add", "tracked.txt");
    git(workspace, "commit", "-q", "-m", "accepted checkpoint");

    CorrectionWorkspaceTransaction transaction = new CorrectionWorkspaceTransaction();
    CorrectionWorkspaceTransaction.Checkpoint seededWorkspaceCheckpoint =
        transaction.begin(workspace);
    String acceptedGeneration = seededWorkspaceCheckpoint.generation();

    ValidationReport original = greenReport(acceptedGeneration);
    ValidationReport rejected = rejectedReport(null);
    ValidationReportRepository reports = mock(ValidationReportRepository.class);
    AtomicReference<ValidationReport> persistedReport = new AtomicReference<>(original);
    when(reports.findByRunId(RUN_ID))
        .thenAnswer(ignored -> Optional.ofNullable(persistedReport.get()));
    when(reports.saveAndFlush(any(ValidationReport.class)))
        .thenAnswer(
            invocation -> {
              ValidationReport saved = invocation.getArgument(0, ValidationReport.class);
              persistedReport.set(saved);
              return saved;
            });

    AgentTask task = AgentTask.builder().taskId(TASK_KEY).runId(RUN_ID).status("COMPLETE").build();
    AgentTaskRepository tasks = mock(AgentTaskRepository.class);
    when(tasks.findById(TASK_KEY)).thenReturn(Optional.of(task));
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of(task));

    BrownfieldHttpContractGuard brownfieldGuard = mock(BrownfieldHttpContractGuard.class);
    when(brownfieldGuard.hasBaseline(RUN_ID)).thenReturn(false);
    DurableCorrectionLifecycle durableCorrections = mock(DurableCorrectionLifecycle.class);
    RunWorkspaceAuthorityService.Lease correctionLease =
        mock(RunWorkspaceAuthorityService.Lease.class);
    when(durableCorrections.prepare(RUN_ID, TASK_KEY, workspace))
        .thenReturn(
            new DurableCorrectionLifecycle.Prepared(
                java.util.UUID.fromString("33333333-3333-3333-3333-333333333333"),
                seededWorkspaceCheckpoint,
                correctionLease));
    AutomaticCorrectionLifecycle lifecycle =
        new AutomaticCorrectionLifecycle(
            transaction,
            tasks,
            new ObjectMapper(),
            mock(FeatureFlagService.class),
            mock(LintPass.class),
            mock(SseBroadcaster.class),
            mock(WorkflowConfigEvidenceInspector.class),
            reports,
            brownfieldGuard,
            durableCorrections);
    CorrectionWorkspaceTransaction.Checkpoint workspaceCheckpoint =
        lifecycle.begin(RUN_ID, TASK_KEY, workspace);

    AutomaticCorrectionLifecycle.TaskState previousTask = lifecycle.snapshotTask(task);
    AutomaticCorrectionLifecycle.EvidenceCheckpoint evidenceCheckpoint =
        lifecycle.invalidateEvidence(RUN_ID);
    task.setStatus("RUNNING");
    Files.writeString(workspace.resolve("tracked.txt"), "rejected correction bytes\n");
    Files.writeString(workspace.resolve("correction-only.txt"), "must never be published\n");
    CorrectionWorkspaceTransaction.DiffEvidence rejectedDiff =
        transaction.diffEvidence(workspaceCheckpoint);
    String rejectedGeneration = transaction.currentGeneration(workspaceCheckpoint);
    rejected.setWorkspaceGeneration(rejectedGeneration);
    persistedReport.set(rejected);
    assertThat(rejectedGeneration).isNotEqualTo(acceptedGeneration);

    boolean rolledBack =
        lifecycle.rollbackIfEvidenceRegressed(
            RUN_ID,
            "T1",
            TASK_KEY,
            workspaceCheckpoint,
            evidenceCheckpoint,
            rejectedDiff,
            previousTask);

    assertThat(rolledBack).isTrue();
    assertThat(persistedReport.get().getStatus()).isEqualTo("COMPLETE");
    assertThat(persistedReport.get().getWorkspaceGeneration()).isEqualTo(acceptedGeneration);
    assertRestored(workspace, transaction, workspaceCheckpoint, acceptedGeneration);
    assertThat(task.getStatus()).isEqualTo("COMPLETE");
    assertThat(task.getContinuationActionsJson())
        .contains("\"status\":\"REJECTED\"")
        .contains("CORRECTION_PROTECTED_EVIDENCE_REGRESSION")
        .contains("\"evidenceInvalidated\":true")
        .contains(acceptedGeneration)
        .contains(rejectedGeneration);

    ArgumentCaptor<ValidationReport> savedReports = ArgumentCaptor.forClass(ValidationReport.class);
    verify(reports, times(2)).saveAndFlush(savedReports.capture());
    assertThat(savedReports.getAllValues().get(0).getStatus()).isEqualTo("STALE");
    assertThat(savedReports.getAllValues().get(0).getWorkspaceGeneration()).isNull();
    assertThat(savedReports.getAllValues().get(1).getStatus()).isEqualTo("COMPLETE");
    assertThat(savedReports.getAllValues().get(1).getWorkspaceGeneration())
        .isEqualTo(acceptedGeneration);

    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(tempDir.toString());
    when(brownfieldGuard.inspectCompatibility(RUN_ID))
        .thenReturn(
            new CompatibilityReport(
                "baseline", acceptedGeneration, List.of(), List.of(), true, false));
    RunWorkspaceAuthorityService workspaceAuthorities = mock(RunWorkspaceAuthorityService.class);
    RunWorkspaceAuthorityService.Lease publicationLease =
        mock(RunWorkspaceAuthorityService.Lease.class);
    when(workspaceAuthorities.acquire(RUN_ID, RunWorkspaceAuthorityService.Authority.PUBLICATION))
        .thenReturn(publicationLease);
    PublicationAdmissionService admission =
        new PublicationAdmissionService(
            reports,
            tasks,
            transaction,
            properties,
            new ObjectMapper(),
            brownfieldGuard,
            mock(RunEffectiveRouteService.class),
            mock(DurableCorrectionLifecycle.class),
            workspaceAuthorities);

    GitTool gitTool = mock(GitTool.class);
    when(gitTool.pushToBranch(
            anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
        .thenAnswer(
            invocation -> {
              assertThat(invocation.getArgument(0, String.class)).isEqualTo(RUN_ID);
              assertThat(persistedReport.get().getWorkspaceGeneration())
                  .isEqualTo(acceptedGeneration);
              assertRestored(workspace, transaction, workspaceCheckpoint, acceptedGeneration);
              return GitToolResult.err("sentinel after restored-byte observation");
            });
    GitHubRepoService github = mock(GitHubRepoService.class);
    AppUserRepository users = mock(AppUserRepository.class);
    AppUser user = new AppUser();
    user.setId("user-1");
    user.setGithubPat("ghp_test_pat");
    when(users.findById("user-1")).thenReturn(Optional.of(user));
    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    when(snapshots.findByRunId(RUN_ID)).thenReturn(List.of());

    ScmPushService scm =
        new ScmPushService(
            gitTool,
            github,
            mock(BitbucketRepoService.class),
            properties,
            mock(StateStore.class),
            mock(SseBroadcaster.class),
            users,
            mock(ProjectRepository.class),
            mock(PullRequestRecordService.class),
            mock(PullRequestRecordRepository.class),
            mock(ProjectRepoRepository.class),
            new ObjectMapper(),
            mock(ArtifactPublicationGuard.class),
            brownfieldGuard,
            admission,
            mock(SideEffectOutbox.class),
            snapshots);

    PipelineRun run =
        PipelineRun.builder()
            .runId(RUN_ID)
            .createdByUserId("user-1")
            .scmProvider("GITHUB")
            .repoUrl("https://github.com/acme/customer-repo")
            .repoMode("EXISTING")
            .userRequest("preserve the existing endpoint")
            .build();
    ScmPushService.PushResult result = scm.push(run);

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.code()).isEqualTo("SCM_GIT_PUSH_FAILED");
    assertThat(result.blockingGate()).isEqualTo("GIT_PUSH");
    assertThat(result.publicationEligible()).isTrue();
    assertThat(result.attempted()).isEqualTo(1);
    verify(gitTool)
        .pushToBranch(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
    verify(gitTool, never()).pushToRemote(anyString(), anyString(), anyString());
    verify(github, never()).createRepo(any(), any(), anyString(), anyString(), any());
    verify(github, never())
        .createPullRequestDetailed(
            anyString(), anyString(), anyString(), any(), any(), anyString());
  }

  private static ValidationReport greenReport(String generation) {
    return ValidationReport.builder()
        .id("report-1")
        .runId(RUN_ID)
        .status("COMPLETE")
        .pipelineResult(PipelineResult.PASSED)
        .workspaceGeneration(generation)
        .finalBuildPassed(true)
        .testExecutionVerified(true)
        .testEvidenceScope("WORKSPACE_TEST_SUITE")
        .testsPassed(2)
        .testsTotal(2)
        .workflowConfigEvidenceJson(
            """
            {"status":"PASSED","files":[],"reason":"workflow syntax valid"}
            """)
        .build();
  }

  private static ValidationReport rejectedReport(String generation) {
    return ValidationReport.builder()
        .id("report-1")
        .runId(RUN_ID)
        .status("COMPLETE")
        .pipelineResult(PipelineResult.FAILED)
        .workspaceGeneration(generation)
        .finalBuildPassed(false)
        .testExecutionVerified(true)
        .testsPassed(1)
        .testsTotal(2)
        .workflowConfigEvidenceJson(
            """
            {"status":"FAILED","files":[],"reason":"regressive workflow"}
            """)
        .build();
  }

  private static void assertRestored(
      Path workspace,
      CorrectionWorkspaceTransaction transaction,
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      String acceptedGeneration)
      throws Exception {
    assertThat(transaction.currentGeneration(checkpoint)).isEqualTo(acceptedGeneration);
    assertThat(Files.readString(workspace.resolve("tracked.txt")))
        .isEqualTo("accepted checkpoint\n")
        .doesNotContain("rejected correction bytes");
    assertThat(workspace.resolve("correction-only.txt")).doesNotExist();
  }

  private static void git(Path repository, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process process = new ProcessBuilder(command).directory(repository.toFile()).start();
    assertThat(process.waitFor()).isZero();
  }
}
