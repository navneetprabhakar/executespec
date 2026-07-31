package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.project.pr.PullRequestRecordService;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.BitbucketRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.saga.SideEffectOutbox;
import com.navneet.executespec.security.ArtifactPublicationGuard;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;
import com.navneet.executespec.tool.GitTool;
import com.navneet.executespec.tool.GitToolResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/** Regression coverage for the existing-branch apply publication boundary. */
@ExtendWith(MockitoExtension.class)
class ScmPushServiceApplyPublicationAdmissionTest {

  @Mock private GitTool gitTool;
  @Mock private GitHubRepoService gitHubRepoService;
  @Mock private BitbucketRepoService bitbucketRepoService;
  @Mock private AgentProperties props;
  @Mock private StateStore stateStore;
  @Mock private SseBroadcaster broadcaster;
  @Mock private AppUserRepository appUserRepository;
  @Mock private ProjectRepository projectRepository;
  @Mock private PullRequestRecordService pullRequestRecordService;
  @Mock private PullRequestRecordRepository pullRequestRecordRepository;
  @Mock private ProjectRepoRepository projectRepoRepository;
  @Mock private ArtifactPublicationGuard publicationGuard;
  @Mock private BrownfieldHttpContractGuard brownfieldHttpContractGuard;
  @Mock private PublicationAdmissionService publicationAdmissionService;
  @Mock private SideEffectOutbox sideEffectOutbox;
  @Mock private RunRepoSnapshotRepository runRepoSnapshotRepository;
  @Spy private ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks private ScmPushService service;

  @Test
  void staleEvidenceBlocksApplyBeforeCredentialLookupOrScmCall() {
    PipelineRun run = existingRun();
    PublicationAdmissionService.Eligibility stale =
        new PublicationAdmissionService.Eligibility(
            false,
            "BLOCKED",
            "PUBLICATION_EVIDENCE_STALE",
            "EVIDENCE_FRESHNESS",
            "Workspace content changed after validation",
            true,
            "Regenerate deterministic evidence on the current workspace.",
            "old-generation",
            "new-generation");
    when(publicationAdmissionService.assertEligible(run))
        .thenThrow(new PublicationAdmissionService.PublicationBlockedException(stale));

    ScmPushService.ApplyPushResult result = service.pushApplyChanges(run, "fix: preserve endpoint");

    assertThat(result.mode()).isEqualTo("SKIPPED");
    assertThat(result.code()).isEqualTo("PUBLICATION_EVIDENCE_STALE");
    assertThat(result.blockingGate()).isEqualTo("EVIDENCE_FRESHNESS");
    assertThat(result.publicationEligible()).isFalse();
    assertThat(result.retryable()).isTrue();
    assertThat(result.remediation()).contains("Regenerate deterministic evidence");
    assertThat(result.skippedReason())
        .contains("Workspace content changed")
        .contains("Regenerate deterministic evidence");
    verifyNoInteractions(
        appUserRepository,
        brownfieldHttpContractGuard,
        publicationGuard,
        gitTool,
        gitHubRepoService,
        bitbucketRepoService);
  }

  @Test
  void missingRunHasStableNonRetryableDiagnosticBeforeAnyAdmissionOrScmCall() {
    ScmPushService.ApplyPushResult result = service.pushApplyChanges(null, "fix: endpoint");

    assertThat(result.code()).isEqualTo("SCM_APPLY_RUN_MISSING");
    assertThat(result.blockingGate()).isEqualTo("RUN");
    assertThat(result.retryable()).isFalse();
    assertThat(result.remediation()).contains("Reload the run");
    verifyNoInteractions(publicationAdmissionService, appUserRepository, publicationGuard, gitTool);
  }

  @Test
  void missingGithubCredentialHasStableRetryableDiagnosticBeforeScmCall() {
    PipelineRun run = existingRun();
    AppUser user = new AppUser();
    user.setId("user-1");
    when(appUserRepository.findById("user-1")).thenReturn(Optional.of(user));

    ScmPushService.ApplyPushResult result = service.pushApplyChanges(run, "fix: endpoint");

    assertThat(result.code()).isEqualTo("SCM_APPLY_CREDENTIAL_MISSING");
    assertThat(result.blockingGate()).isEqualTo("AUTHENTICATION");
    assertThat(result.retryable()).isTrue();
    assertThat(result.remediation()).contains("Connect GitHub");
    verifyNoInteractions(publicationGuard, gitTool, gitHubRepoService, bitbucketRepoService);
  }

  @Test
  void removedProtectedRouteBlocksApplyBeforeCredentialLookupOrScmCall() {
    PipelineRun run = existingRun();
    when(brownfieldHttpContractGuard.assertCompatible(run.getRunId()))
        .thenThrow(
            new BrownfieldHttpContractGuard.ContractCompatibilityException(
                "BROWNFIELD_HTTP_CONTRACT_REMOVED",
                "Publication blocked because POST /simulation/casino-game was removed"));

    ScmPushService.ApplyPushResult result = service.pushApplyChanges(run, "fix: replace endpoint");

    assertThat(result.code()).isEqualTo("BROWNFIELD_HTTP_CONTRACT_REMOVED");
    assertThat(result.blockingGate()).isEqualTo("PROTECTED_CONTRACTS");
    assertThat(result.publicationEligible()).isFalse();
    assertThat(result.retryable()).isFalse();
    assertThat(result.remediation()).contains("Restore the removed HTTP route");
    assertThat(result.skippedReason()).contains("POST /simulation/casino-game");
    verify(publicationAdmissionService).assertEligible(run);
    verify(brownfieldHttpContractGuard).assertCompatible(run.getRunId());
    verifyNoInteractions(
        appUserRepository, publicationGuard, gitTool, gitHubRepoService, bitbucketRepoService);
  }

  @Test
  void eligibleApplyPersistsContractAdmissionBeforeExistingBranchPush() throws Exception {
    PipelineRun run = existingRun();
    AppUser user = new AppUser();
    user.setId("user-1");
    user.setGithubPat("ghp_test");
    when(appUserRepository.findById("user-1")).thenReturn(Optional.of(user));
    when(pullRequestRecordRepository.findByRunIdOrderByCreatedAtAsc(run.getRunId()))
        .thenReturn(List.of());
    when(gitTool.pushToExistingBranch(
            eq(run.getRunId()),
            eq("."),
            eq(run.getRepoUrl()),
            eq("ghp_test"),
            anyString(),
            eq("fix: preserve endpoint")))
        .thenReturn(GitToolResult.ok("executespec/run-appl"));

    ScmPushService.ApplyPushResult result = service.pushApplyChanges(run, "fix: preserve endpoint");

    assertThat(result.mode()).isEqualTo("SINGLE_REPO");
    assertThat(result.publicationEligible()).isTrue();
    assertThat(result.perRepo()).hasSize(1);
    assertThat(result.perRepo().get(0).ok()).isTrue();
    assertThat(result.code()).isEqualTo("SCM_APPLY_PUSH_SUCCEEDED");

    InOrder order =
        inOrder(
            publicationAdmissionService, brownfieldHttpContractGuard, publicationGuard, gitTool);
    order.verify(publicationAdmissionService).assertEligible(run);
    order.verify(brownfieldHttpContractGuard).assertCompatible(run.getRunId());
    order.verify(publicationGuard).assertGitWorkspaceSafe(run.getRunId());
    order
        .verify(gitTool)
        .pushToExistingBranch(
            eq(run.getRunId()),
            eq("."),
            eq(run.getRepoUrl()),
            eq("ghp_test"),
            anyString(),
            eq("fix: preserve endpoint"));
    verify(gitTool, never())
        .pushToBranch(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void failedExistingBranchPushCarriesPerRepoAndAggregateDiagnostics() throws Exception {
    PipelineRun run = existingRun();
    AppUser user = new AppUser();
    user.setId("user-1");
    user.setGithubPat("ghp_test");
    when(appUserRepository.findById("user-1")).thenReturn(Optional.of(user));
    when(pullRequestRecordRepository.findByRunIdOrderByCreatedAtAsc(run.getRunId()))
        .thenReturn(List.of());
    when(gitTool.pushToExistingBranch(
            eq(run.getRunId()),
            eq("."),
            eq(run.getRepoUrl()),
            eq("ghp_test"),
            anyString(),
            eq("fix: preserve endpoint")))
        .thenReturn(GitToolResult.err("push failed: rejected (non-fast-forward)"));

    ScmPushService.ApplyPushResult result = service.pushApplyChanges(run, "fix: preserve endpoint");

    assertThat(result.code()).isEqualTo("SCM_APPLY_PUSH_FAILED");
    assertThat(result.blockingGate()).isEqualTo("GIT_PUSH");
    assertThat(result.publicationEligible()).isFalse();
    assertThat(result.retryable()).isTrue();
    assertThat(result.perRepo())
        .singleElement()
        .satisfies(
            repo -> {
              assertThat(repo.code()).isEqualTo("SCM_APPLY_GIT_PUSH_FAILED");
              assertThat(repo.blockingGate()).isEqualTo("GIT_PUSH");
              assertThat(repo.retryable()).isTrue();
              assertThat(repo.remediation()).contains("non-fast-forward");
            });
  }

  @Test
  void missingSelectedRepositoryProducesPartialFailureInsteadOfFalseAggregateSuccess()
      throws Exception {
    PipelineRun run = existingRun();
    java.util.UUID goodId = java.util.UUID.randomUUID();
    java.util.UUID missingId = java.util.UUID.randomUUID();
    run.setSelectedRepoIds("[\"" + goodId + "\",\"" + missingId + "\"]");
    AppUser user = new AppUser();
    user.setId("user-1");
    user.setGithubPat("ghp_test");
    when(appUserRepository.findById("user-1")).thenReturn(Optional.of(user));
    when(projectRepoRepository.findById(goodId))
        .thenReturn(
            Optional.of(
                ProjectRepo.builder().id(goodId).githubRepoFullName("acme/customer-repo").build()));
    when(projectRepoRepository.findById(missingId)).thenReturn(Optional.empty());
    when(pullRequestRecordRepository.findByRunIdOrderByCreatedAtAsc(run.getRunId()))
        .thenReturn(List.of());
    when(gitTool.pushToExistingBranch(
            eq(run.getRunId()),
            eq("repos/customer-repo"),
            eq("https://github.com/acme/customer-repo"),
            eq("ghp_test"),
            anyString(),
            eq("fix: endpoint")))
        .thenReturn(GitToolResult.ok("executespec/run-appl"));

    ScmPushService.ApplyPushResult result = service.pushApplyChanges(run, "fix: endpoint");

    assertThat(result.code()).isEqualTo("SCM_APPLY_PUSH_PARTIAL");
    assertThat(result.blockingGate()).isEqualTo("REPOSITORY_RESOLUTION");
    assertThat(result.publicationEligible()).isFalse();
    assertThat(result.perRepo()).hasSize(2);
    assertThat(result.perRepo())
        .anySatisfy(
            repo -> {
              assertThat(repo.code()).isEqualTo("SCM_APPLY_REPOSITORY_NOT_FOUND");
              assertThat(repo.repoFullName()).contains(missingId.toString());
            });
  }

  private static PipelineRun existingRun() {
    return PipelineRun.builder()
        .runId("run-apply-admission")
        .createdByUserId("user-1")
        .scmProvider("GITHUB")
        .repoMode("EXISTING")
        .repoUrl("https://github.com/acme/customer-repo")
        .build();
  }
}
