package com.navneet.executespec.execution.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.JwtUtil;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRunRepository;
import com.navneet.executespec.execution.service.ExecutionOrchestrator;
import com.navneet.executespec.execution.service.ExecutionPlannerService;
import com.navneet.executespec.execution.service.SolutionExecutionService;
import com.navneet.executespec.integration.PlanApprovalService;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ExecutionControllerAuthorizationTest {

  @Mock FeatureFlagService featureFlags;
  @Mock ExecutionRunRepository execRunRepo;
  @Mock ExecutionSolutionRepository solutionRepo;
  @Mock ExecutionSolutionRunRepository solutionRunRepo;
  @Mock ProjectAccessGuard projectAccessGuard;
  @Mock ProjectRepoService projectRepoService;
  @Mock OrgRepository orgRepo;
  @Mock TierConfigRepository tierConfigRepo;
  @Mock ExecutionOrchestrator orchestrator;
  @Mock PlanApprovalService planApprovalService;
  @Mock RunAccessGuard runAccessGuard;
  @Mock ExecutionPlannerService plannerService;
  @Mock SolutionExecutionService solutionExecService;
  @Mock SseBroadcaster broadcaster;
  @Mock JwtUtil jwtUtil;
  @Mock GitHubRepoService gitHubRepoService;
  @Mock AppUserRepository appUserRepo;
  @Mock AppUserDetails principal;

  @InjectMocks ExecutionController controller;

  private final AppUser user = AppUser.builder().id("user-1").tier("TEAM").build();
  private final ExecutionRun run =
      ExecutionRun.builder()
          .id("execution-1")
          .projectId("project-1")
          .orgId("org-1")
          .createdBy("owner-1")
          .status("PAUSED")
          .build();

  @BeforeEach
  void enableExecutionView() {
    TierConfig tier = new TierConfig();
    tier.setTier("TEAM");
    tier.setExecutionViewEnabled(true);
    when(principal.getUser()).thenReturn(user);
    when(featureFlags.isEnabled("execution.view.enabled")).thenReturn(true);
    when(tierConfigRepo.findById("TEAM")).thenReturn(Optional.of(tier));
  }

  @Test
  void mutationRequiresProjectWriteAuthorityBeforeOrchestratorSideEffect() {
    when(execRunRepo.findById("execution-1")).thenReturn(Optional.of(run));
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "viewer cannot write"))
        .when(projectAccessGuard)
        .assertCanWrite("project-1", principal);

    assertThatThrownBy(() -> controller.triggerAnalysis("execution-1", principal))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error ->
                org.assertj.core.api.Assertions.assertThat(error.getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    verify(orchestrator, never()).triggerAnalysis("execution-1");
  }

  @Test
  void readUsesProjectReadAuthorityAndDoesNotRequireWrite() {
    when(execRunRepo.findById("execution-1")).thenReturn(Optional.of(run));
    when(solutionRepo.findByExecutionRun_IdOrderBySequenceNumberAsc("execution-1"))
        .thenReturn(List.of());

    controller.getExecution("execution-1", principal);

    verify(projectAccessGuard).assertCanRead("project-1", principal);
    verify(projectAccessGuard, never()).assertCanWrite("project-1", principal);
  }

  @Test
  void unifiedPlanApprovalCompletesCanonicalAdmissionBeforeAsyncProjection() {
    ExecutionRun approvalRun =
        ExecutionRun.builder()
            .id("execution-1")
            .projectId("project-1")
            .orgId("org-1")
            .createdBy("owner-1")
            .status("PLANNING")
            .executionPlan("{\"tasks\":[]}")
            .pipelineRunId("run-1")
            .build();
    when(execRunRepo.findById("execution-1")).thenReturn(Optional.of(approvalRun));
    var request =
        new com.navneet.executespec.api.dto.ApprovalRequest(
            true, null, 3, "REALTIME", "fingerprint", "BREAKING", "digest");

    controller.approvePlan("execution-1", request, principal);

    verify(runAccessGuard).requireRunWriteAccess("run-1", principal);
    verify(planApprovalService)
        .decide(
            org.mockito.Mockito.eq("run-1"),
            org.mockito.Mockito.argThat(
                command ->
                    command.approved()
                        && Integer.valueOf(3).equals(command.creditsConfirmed())
                        && "fingerprint".equals(command.planFingerprint())
                        && "digest".equals(command.breakingChangeReportDigest())),
            org.mockito.Mockito.eq(principal),
            org.mockito.Mockito.eq(
                com.navneet.executespec.integration.PlanGateDecisionService.ResolutionSource
                    .EXECUTION_VIEW_API));
    verify(orchestrator).projectApprovedPlan("execution-1", "run-1");
  }

  @Test
  void canonicalAdmissionFailureIsReturnedBeforeProjectionStarts() {
    ExecutionRun approvalRun =
        ExecutionRun.builder()
            .id("execution-1")
            .projectId("project-1")
            .status("PLANNING")
            .executionPlan("{\"tasks\":[]}")
            .pipelineRunId("run-1")
            .build();
    when(execRunRepo.findById("execution-1")).thenReturn(Optional.of(approvalRun));
    org.mockito.Mockito.doThrow(
            new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "managed credits"))
        .when(planApprovalService)
        .decide(
            org.mockito.Mockito.eq("run-1"),
            org.mockito.ArgumentMatchers.any(PlanApprovalService.Command.class),
            org.mockito.Mockito.eq(principal),
            org.mockito.Mockito.eq(
                com.navneet.executespec.integration.PlanGateDecisionService.ResolutionSource
                    .EXECUTION_VIEW_API));

    assertThatThrownBy(() -> controller.approvePlan("execution-1", null, principal))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error ->
                org.assertj.core.api.Assertions.assertThat(error.getStatusCode())
                    .isEqualTo(HttpStatus.PAYMENT_REQUIRED));

    verify(orchestrator, never())
        .projectApprovedPlan(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void pipelineLessLegacyApprovalIsRetiredBeforeAdmission() {
    ExecutionRun approvalRun =
        ExecutionRun.builder()
            .id("execution-1")
            .projectId("project-1")
            .status("PLANNING")
            .executionPlan("{\"tasks\":[]}")
            .build();
    when(execRunRepo.findById("execution-1")).thenReturn(Optional.of(approvalRun));

    assertThatThrownBy(() -> controller.approvePlan("execution-1", null, principal))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error ->
                org.assertj.core.api.Assertions.assertThat(error.getStatusCode())
                    .isEqualTo(HttpStatus.GONE));

    verifyNoInteractions(planApprovalService);
    verify(orchestrator, never())
        .projectApprovedPlan(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void prCommentsUseCanonicalLinkedRepositoryInsteadOfCallerText() {
    UUID repoId = UUID.randomUUID();
    AppUser storedUser = AppUser.builder().id("user-1").githubPat("pat").build();
    ProjectRepo linkedRepo =
        ProjectRepo.builder()
            .id(repoId)
            .githubRepoFullName("trusted/repository")
            .scmProvider("GITHUB")
            .build();
    when(projectRepoService.resolveGitHubReviewRepoStrict(
            "project-1", repoId, "TRUSTED/REPOSITORY"))
        .thenReturn(linkedRepo);
    when(appUserRepo.findById("user-1")).thenReturn(Optional.of(storedUser));
    when(gitHubRepoService.fetchPrReviewComments("trusted/repository", 42, "pat"))
        .thenReturn("review");

    controller.prReviewComments("project-1", 42, repoId, "TRUSTED/REPOSITORY", principal);

    verify(projectAccessGuard).assertCanRead("project-1", principal);
    verify(gitHubRepoService).fetchPrReviewComments("trusted/repository", 42, "pat");
  }
}
