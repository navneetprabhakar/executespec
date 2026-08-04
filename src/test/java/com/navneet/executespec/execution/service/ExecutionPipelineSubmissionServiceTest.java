package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.UserDetailsServiceImpl;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.entity.ExecutionSolutionRun;
import com.navneet.executespec.integration.RunLifecycleService;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.run.CustomerRunSubmissionService;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionPipelineSubmissionServiceTest {

  @Mock CustomerRunSubmissionService customerSubmission;
  @Mock UserDetailsServiceImpl userDetailsService;
  @Mock RunOrchestrator runOrchestrator;
  @Mock ExecutionSolutionDispatchRegistry dispatchRegistry;
  @Mock RunLifecycleService runLifecycleService;
  @Mock com.navneet.executespec.auth.repository.ProjectRepository projectRepository;
  @Mock com.navneet.executespec.project.service.ProjectRepoService projectRepoService;
  @Mock com.navneet.executespec.repo.GitHubRepoService gitHubRepoService;

  private ExecutionPipelineSubmissionService service;
  private AppUserDetails actor;

  @BeforeEach
  void setUp() {
    service =
        new ExecutionPipelineSubmissionService(
            customerSubmission,
            userDetailsService,
            runOrchestrator,
            dispatchRegistry,
            runLifecycleService,
            projectRepository,
            projectRepoService,
            gitHubRepoService);
    actor =
        new AppUserDetails(
            AppUser.builder()
                .id("user-1")
                .orgId("org-home")
                .githubPat("pat")
                .githubOwner("owner")
                .build(),
            null);
    org.mockito.Mockito.lenient().when(userDetailsService.loadUserById("user-1")).thenReturn(actor);
  }

  @Test
  void unifiedExecutionUsesCustomerAdmissionWithExactRepositoryBoundary() {
    UUID repoId = UUID.randomUUID();
    PipelineRun pipeline = PipelineRun.builder().runId("run-1").build();
    when(customerSubmission.submit(any(), any()))
        .thenReturn(new CustomerRunSubmissionService.Submission(pipeline, true, false));

    PipelineRun result = service.submitUnified(execution(), List.of(repoId));

    assertThat(result).isSameAs(pipeline);
    ArgumentCaptor<CustomerRunSubmissionService.Command> command =
        ArgumentCaptor.forClass(CustomerRunSubmissionService.Command.class);
    verify(customerSubmission).submit(command.capture(), any(AppUserDetails.class));
    assertThat(command.getValue().source()).isEqualTo("EXECUTION_VIEW");
    assertThat(command.getValue().projectId()).isEqualTo("project-1");
    assertThat(command.getValue().selectedRepoIds()).containsExactly(repoId);
    assertThat(command.getValue().checkoutBranch()).isEqualTo("feature/demo");
    assertThat(command.getValue().branchOverride()).isNull();
    assertThat(command.getValue().existingPrRepoFullName()).isNull();
    assertThat(command.getValue().existingPrNumber()).isNull();
    assertThat(command.getValue().skipApproval()).isFalse();
    assertThat(command.getValue().idempotencyKey()).isEqualTo("execution-view:exec-1");
  }

  @Test
  void continuePrCarriesCheckoutAndExactPublicationIdentity() {
    UUID repoId = UUID.randomUUID();
    ExecutionRun execution =
        ExecutionRun.builder()
            .id("exec-pr")
            .createdBy("user-1")
            .orgId("org-host")
            .projectId("project-1")
            .requirement("fix CI")
            .aiProvider("FIREWORKS")
            .branch("fix/pr16")
            .continuePrRepo("owner/repo")
            .continuePrNumber(16)
            .build();
    PipelineRun pipeline = PipelineRun.builder().runId("run-pr").build();
    when(customerSubmission.submit(any(), any()))
        .thenReturn(new CustomerRunSubmissionService.Submission(pipeline, true, false));

    service.submitUnified(execution, List.of(repoId));

    ArgumentCaptor<CustomerRunSubmissionService.Command> command =
        ArgumentCaptor.forClass(CustomerRunSubmissionService.Command.class);
    verify(customerSubmission).submit(command.capture(), any(AppUserDetails.class));
    assertThat(command.getValue().checkoutBranch()).isEqualTo("fix/pr16");
    assertThat(command.getValue().branchOverride()).isEqualTo("fix/pr16");
    assertThat(command.getValue().existingPrRepoFullName()).isEqualTo("owner/repo");
    assertThat(command.getValue().existingPrNumber()).isEqualTo(16);
  }

  @Test
  void branchIntentRejectsPartialOrMultiRepoContinuationAuthority() {
    ExecutionRun partial = execution();
    partial.setContinuePrNumber(16);
    assertThatThrownBy(
            () ->
                ExecutionPipelineSubmissionService.branchIntent(
                    partial, List.of(UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("both repository and PR number");

    ExecutionRun multi = execution();
    multi.setContinuePrNumber(16);
    multi.setContinuePrRepo("owner/repo");
    assertThatThrownBy(
            () ->
                ExecutionPipelineSubmissionService.branchIntent(
                    multi, List.of(UUID.randomUUID(), UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exactly one selected repository");
  }

  @Test
  void approvedSolutionKeepsDistinctServerAuthorizedBypass() {
    UUID repoId = UUID.randomUUID();
    String reservedRunId = UUID.randomUUID().toString();
    ExecutionSolutionRun attempt = attempt(reservedRunId, 1);
    var reservation = new ExecutionSolutionDispatchRegistry.Reservation(attempt, true);
    PipelineRun pipeline = approvedChild(reservedRunId);
    pipeline.setSelectedRepoIds("[\"" + repoId + "\"]");
    when(dispatchRegistry.reserve("solution-1", 1)).thenReturn(reservation);
    when(dispatchRegistry.findCanonicalRun(reservation)).thenReturn(java.util.Optional.empty());
    when(runOrchestrator.withReservedRunId(eq(reservedRunId), any())).thenReturn(pipeline);

    var result =
        service.submitApprovedSolution(
            ExecutionSolution.builder().id("solution-1").build(),
            execution(),
            "implement it",
            List.of(repoId),
            1);

    assertThat(result.run()).isSameAs(pipeline);
    assertThat(result.attempt()).isSameAs(attempt);
    verify(runOrchestrator).withReservedRunId(eq(reservedRunId), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void approvedOrdinaryExecutionUsesCheckoutOnlyAndFreshPrPublication() {
    UUID repoId = UUID.randomUUID();
    String reservedRunId = UUID.randomUUID().toString();
    ExecutionSolutionRun attempt = attempt(reservedRunId, 1);
    var reservation = new ExecutionSolutionDispatchRegistry.Reservation(attempt, true);
    AtomicReference<String> publicationBranch = new AtomicReference<>();
    AtomicReference<RunOrchestrator.SubmitPolicy> policy = new AtomicReference<>();
    when(dispatchRegistry.reserve("solution-1", 1)).thenReturn(reservation);
    when(dispatchRegistry.findCanonicalRun(reservation)).thenReturn(java.util.Optional.empty());
    when(runOrchestrator.withReservedRunId(eq(reservedRunId), any()))
        .thenAnswer(invocation -> ((Supplier<PipelineRun>) invocation.getArgument(1)).get());
    orchestratorSubmitStub()
        .thenAnswer(
            invocation -> {
              publicationBranch.set(invocation.getArgument(17));
              policy.set(invocation.getArgument(24));
              PipelineRun child = approvedChild(reservedRunId);
              child.setSelectedRepoIds("[\"" + repoId + "\"]");
              return child;
            });

    service.submitApprovedSolution(
        ExecutionSolution.builder().id("solution-1").build(),
        execution(),
        "implement it",
        List.of(repoId),
        1);

    assertThat(publicationBranch.get()).isNull();
    assertThat(policy.get().checkoutBranch()).isEqualTo("feature/demo");
    assertThat(policy.get().publicationRepoFullName()).isNull();
    assertThat(policy.get().publicationPrNumber()).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void approvedContinuePrRevalidatesAndSubmitsExactPublicationIdentity() {
    UUID repoId = UUID.randomUUID();
    String reservedRunId = UUID.randomUUID().toString();
    ExecutionSolutionRun attempt = attempt(reservedRunId, 1);
    var reservation = new ExecutionSolutionDispatchRegistry.Reservation(attempt, true);
    ExecutionRun execution = continueExecution();
    AtomicReference<String> publicationBranch = new AtomicReference<>();
    AtomicReference<RunOrchestrator.SubmitPolicy> policy = new AtomicReference<>();
    when(dispatchRegistry.reserve("solution-1", 1)).thenReturn(reservation);
    when(dispatchRegistry.findCanonicalRun(reservation)).thenReturn(java.util.Optional.empty());
    when(projectRepository.findById("project-1"))
        .thenReturn(
            java.util.Optional.of(
                com.navneet.executespec.auth.entity.Project.builder()
                    .id("project-1")
                    .orgId("org-host")
                    .targetBranch("main")
                    .build()));
    when(projectRepoService.resolveForRunStrict("project-1", List.of(repoId)))
        .thenReturn(
            List.of(
                com.navneet.executespec.project.entity.ProjectRepo.builder()
                    .id(repoId)
                    .githubRepoFullName("owner/repo")
                    .defaultBranch("main")
                    .build()));
    when(gitHubRepoService.findOpenPullRequestForHead("owner/repo", "fix/pr16", "pat"))
        .thenReturn(
            com.navneet.executespec.repo.GitHubRepoService.OpenPullRequestLookup.found(
                "https://github.com/owner/repo/pull/16", 16, "main", "a".repeat(40)));
    when(runOrchestrator.withReservedRunId(eq(reservedRunId), any()))
        .thenAnswer(invocation -> ((Supplier<PipelineRun>) invocation.getArgument(1)).get());
    orchestratorSubmitStub()
        .thenAnswer(
            invocation -> {
              publicationBranch.set(invocation.getArgument(17));
              policy.set(invocation.getArgument(24));
              PipelineRun child = approvedContinueChild(reservedRunId);
              child.setSelectedRepoIds("[\"" + repoId + "\"]");
              return child;
            });

    service.submitApprovedSolution(
        ExecutionSolution.builder().id("solution-1").build(),
        execution,
        "fix CI",
        List.of(repoId),
        1);

    assertThat(publicationBranch.get()).isEqualTo("fix/pr16");
    assertThat(policy.get().checkoutBranch()).isEqualTo("fix/pr16");
    assertThat(policy.get().publicationRepoFullName()).isEqualTo("owner/repo");
    assertThat(policy.get().publicationPrNumber()).isEqualTo(16);
    assertThat(policy.get().publicationHeadSha()).isEqualTo("a".repeat(40));
  }

  @Test
  void approvedContinuePrStaleLiveIdentityFailsBeforeOrchestratorDispatch() {
    UUID repoId = UUID.randomUUID();
    String reservedRunId = UUID.randomUUID().toString();
    ExecutionSolutionRun attempt = attempt(reservedRunId, 1);
    var reservation = new ExecutionSolutionDispatchRegistry.Reservation(attempt, true);
    when(dispatchRegistry.reserve("solution-1", 1)).thenReturn(reservation);
    when(dispatchRegistry.findCanonicalRun(reservation)).thenReturn(java.util.Optional.empty());
    when(projectRepository.findById("project-1"))
        .thenReturn(
            java.util.Optional.of(
                com.navneet.executespec.auth.entity.Project.builder()
                    .id("project-1")
                    .orgId("org-host")
                    .targetBranch("main")
                    .build()));
    when(projectRepoService.resolveForRunStrict("project-1", List.of(repoId)))
        .thenReturn(
            List.of(
                com.navneet.executespec.project.entity.ProjectRepo.builder()
                    .id(repoId)
                    .githubRepoFullName("owner/repo")
                    .defaultBranch("main")
                    .build()));
    when(gitHubRepoService.findOpenPullRequestForHead("owner/repo", "fix/pr16", "pat"))
        .thenReturn(com.navneet.executespec.repo.GitHubRepoService.OpenPullRequestLookup.none());

    assertThatThrownBy(
            () ->
                service.submitApprovedSolution(
                    ExecutionSolution.builder().id("solution-1").build(),
                    continueExecution(),
                    "fix CI",
                    List.of(repoId),
                    1))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("no longer an open pull request");
    verifyNoInteractions(runOrchestrator);
  }

  @Test
  void approvedSolutionCrashRecoveryReturnsAlreadyPersistedCanonicalChild() {
    String reservedRunId = UUID.randomUUID().toString();
    ExecutionSolutionRun attempt = attempt(reservedRunId, 2);
    var reservation = new ExecutionSolutionDispatchRegistry.Reservation(attempt, false);
    PipelineRun canonical = approvedChild(reservedRunId);
    when(dispatchRegistry.reserve("solution-1", 2)).thenReturn(reservation);
    when(dispatchRegistry.findCanonicalRun(reservation))
        .thenReturn(java.util.Optional.of(canonical));

    var result =
        service.submitApprovedSolution(
            ExecutionSolution.builder().id("solution-1").build(),
            execution(),
            "implement it",
            List.of(),
            2);

    assertThat(result.run()).isSameAs(canonical);
    assertThat(result.attempt()).isSameAs(attempt);
    verifyNoInteractions(runOrchestrator);
  }

  @Test
  void approvedSolutionCrashBeforePipelineInsertResubmitsTheReservedIdentity() {
    String reservedRunId = UUID.randomUUID().toString();
    ExecutionSolutionRun attempt = attempt(reservedRunId, 2);
    var reservation = new ExecutionSolutionDispatchRegistry.Reservation(attempt, false);
    PipelineRun recovered = approvedChild(reservedRunId);
    when(dispatchRegistry.reserve("solution-1", 2)).thenReturn(reservation);
    when(dispatchRegistry.findCanonicalRun(reservation)).thenReturn(java.util.Optional.empty());
    when(runOrchestrator.withReservedRunId(eq(reservedRunId), any())).thenReturn(recovered);

    var result =
        service.submitApprovedSolution(
            ExecutionSolution.builder().id("solution-1").build(),
            execution(),
            "implement it",
            List.of(),
            2);

    assertThat(result.run()).isSameAs(recovered);
    assertThat(result.attempt()).isSameAs(attempt);
    verify(runOrchestrator).withReservedRunId(eq(reservedRunId), any());
  }

  @Test
  void concurrentPipelineSubmitterReturnsTheCanonicalReservedChild() {
    String reservedRunId = UUID.randomUUID().toString();
    ExecutionSolutionRun attempt = attempt(reservedRunId, 1);
    var reservation = new ExecutionSolutionDispatchRegistry.Reservation(attempt, false);
    PipelineRun canonical = approvedChild(reservedRunId);
    when(dispatchRegistry.reserve("solution-1", 1)).thenReturn(reservation);
    when(dispatchRegistry.findCanonicalRun(reservation)).thenReturn(java.util.Optional.empty());
    // RunOrchestrator resolves a duplicate insert for this reserved UUID to the existing row.
    when(runOrchestrator.withReservedRunId(eq(reservedRunId), any())).thenReturn(canonical);

    var result =
        service.submitApprovedSolution(
            ExecutionSolution.builder().id("solution-1").build(),
            execution(),
            "implement it",
            List.of(),
            1);

    assertThat(result.run()).isSameAs(canonical);
    assertThat(result.attempt()).isSameAs(attempt);
  }

  @Test
  void rejectsCanonicalChildBoundToDifferentExecutionAuthority() {
    String reservedRunId = UUID.randomUUID().toString();
    ExecutionSolutionRun attempt = attempt(reservedRunId, 1);
    var reservation = new ExecutionSolutionDispatchRegistry.Reservation(attempt, false);
    PipelineRun mismatched =
        PipelineRun.builder()
            .runId(reservedRunId)
            .sourceType("EXECUTION_VIEW_APPROVED")
            .createdByUserId("another-user")
            .orgId("org-host")
            .billingOrgId("org-home")
            .projectId("project-1")
            .build();
    when(dispatchRegistry.reserve("solution-1", 1)).thenReturn(reservation);
    when(dispatchRegistry.findCanonicalRun(reservation))
        .thenReturn(java.util.Optional.of(mismatched));

    assertThatThrownBy(
            () ->
                service.submitApprovedSolution(
                    ExecutionSolution.builder().id("solution-1").build(),
                    execution(),
                    "implement it",
                    List.of(),
                    1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("different execution authority");

    verifyNoInteractions(runOrchestrator, runLifecycleService);
  }

  @Test
  void rejectsCanonicalChildWithDifferentBranchPublicationIdentity() {
    String reservedRunId = UUID.randomUUID().toString();
    ExecutionSolutionRun attempt = attempt(reservedRunId, 1);
    var reservation = new ExecutionSolutionDispatchRegistry.Reservation(attempt, false);
    PipelineRun mismatched = approvedContinueChild(reservedRunId);
    mismatched.setPublicationPrNumber(17);
    when(dispatchRegistry.reserve("solution-1", 1)).thenReturn(reservation);
    when(dispatchRegistry.findCanonicalRun(reservation))
        .thenReturn(java.util.Optional.of(mismatched));

    assertThatThrownBy(
            () ->
                service.submitApprovedSolution(
                    ExecutionSolution.builder().id("solution-1").build(),
                    continueExecution(),
                    "fix CI",
                    List.of(UUID.randomUUID()),
                    1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("different execution authority");
    verifyNoInteractions(runOrchestrator);
  }

  @Test
  void rejectsCanonicalChildWithDifferentSelectedRepositoryIdentity() {
    String reservedRunId = UUID.randomUUID().toString();
    UUID persistedRepo = UUID.randomUUID();
    UUID requestedRepo = UUID.randomUUID();
    ExecutionSolutionRun attempt = attempt(reservedRunId, 1);
    var reservation = new ExecutionSolutionDispatchRegistry.Reservation(attempt, false);
    PipelineRun mismatched = approvedChild(reservedRunId);
    mismatched.setSelectedRepoIds("[\"" + persistedRepo + "\"]");
    when(dispatchRegistry.reserve("solution-1", 1)).thenReturn(reservation);
    when(dispatchRegistry.findCanonicalRun(reservation))
        .thenReturn(java.util.Optional.of(mismatched));

    assertThatThrownBy(
            () ->
                service.submitApprovedSolution(
                    ExecutionSolution.builder().id("solution-1").build(),
                    execution(),
                    "implement it",
                    List.of(requestedRepo),
                    1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("different execution authority");
    verifyNoInteractions(runOrchestrator);
  }

  @Test
  void fencesPersistedChildWhenSubmissionFailsBeforeFutureRegistration() {
    String reservedRunId = UUID.randomUUID().toString();
    ExecutionSolutionRun attempt = attempt(reservedRunId, 1);
    var reservation = new ExecutionSolutionDispatchRegistry.Reservation(attempt, true);
    PipelineRun canonical = approvedChild(reservedRunId);
    RuntimeException failure = new RuntimeException("executor rejected");
    when(dispatchRegistry.reserve("solution-1", 1)).thenReturn(reservation);
    when(dispatchRegistry.findCanonicalRun(reservation))
        .thenReturn(java.util.Optional.empty(), java.util.Optional.of(canonical));
    when(runOrchestrator.withReservedRunId(eq(reservedRunId), any())).thenThrow(failure);
    when(runLifecycleService.cancel(reservedRunId)).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.submitApprovedSolution(
                    ExecutionSolution.builder().id("solution-1").build(),
                    execution(),
                    "implement it",
                    List.of(),
                    1))
        .isSameAs(failure);

    verify(runLifecycleService).cancel(reservedRunId);
    verifyNoMoreInteractions(runLifecycleService);
  }

  private static ExecutionSolutionRun attempt(String runId, int generation) {
    return ExecutionSolutionRun.builder()
        .id(UUID.randomUUID().toString())
        .solutionId("solution-1")
        .pipelineRunId(runId)
        .attemptNumber(generation)
        .build();
  }

  private static PipelineRun approvedChild(String runId) {
    return PipelineRun.builder()
        .runId(runId)
        .sourceType("EXECUTION_VIEW_APPROVED")
        .createdByUserId("user-1")
        .orgId("org-host")
        .billingOrgId("org-home")
        .projectId("project-1")
        .checkoutBranch("feature/demo")
        .build();
  }

  private static PipelineRun approvedContinueChild(String runId) {
    return PipelineRun.builder()
        .runId(runId)
        .sourceType("EXECUTION_VIEW_APPROVED")
        .createdByUserId("user-1")
        .orgId("org-host")
        .billingOrgId("org-home")
        .projectId("project-1")
        .checkoutBranch("fix/pr16")
        .branchOverride("fix/pr16")
        .publicationRepoFullName("owner/repo")
        .publicationPrNumber(16)
        .publicationHeadSha("a".repeat(40))
        .build();
  }

  private org.mockito.stubbing.OngoingStubbing<PipelineRun> orchestratorSubmitStub() {
    return when(
        runOrchestrator.submitRunWithLocalExecution(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            anyBoolean(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()));
  }

  private static ExecutionRun continueExecution() {
    return ExecutionRun.builder()
        .id("exec-pr")
        .createdBy("user-1")
        .orgId("org-host")
        .projectId("project-1")
        .requirement("fix CI")
        .aiProvider("FIREWORKS")
        .branch("fix/pr16")
        .continuePrRepo("owner/repo")
        .continuePrNumber(16)
        .build();
  }

  private static ExecutionRun execution() {
    return ExecutionRun.builder()
        .id("exec-1")
        .createdBy("user-1")
        .orgId("org-host")
        .projectId("project-1")
        .requirement("build the workflow")
        .aiProvider("ANTHROPIC")
        .figmaUrl("https://figma.example/file")
        .branch("feature/demo")
        .build();
  }
}
