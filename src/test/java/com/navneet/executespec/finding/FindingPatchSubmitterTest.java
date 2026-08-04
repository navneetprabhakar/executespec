package com.navneet.executespec.finding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.state.RunMode;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FindingPatchSubmitterTest {

  private final AppUserRepository userRepo = mock(AppUserRepository.class);
  private final ProjectRepository projectRepo = mock(ProjectRepository.class);
  private final ProjectRepoService projectRepoService = mock(ProjectRepoService.class);
  private final GitHubRepoService gitHubRepoService = mock(GitHubRepoService.class);
  private final PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
  private final PipelineRun submitted = PipelineRun.builder().runId("run-1").build();
  private final List<InvocationOnMock> submissions = new ArrayList<>();
  private RunOrchestrator orchestrator;
  private FindingPatchSubmitter submitter;

  @BeforeEach
  void setUp() {
    orchestrator =
        mock(
            RunOrchestrator.class,
            invocation -> {
              if ("submitRunWithLocalExecution".equals(invocation.getMethod().getName())
                  && invocation.getArguments().length == 25) {
                submissions.add(invocation);
                return submitted;
              }
              return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            });
    submitter =
        new FindingPatchSubmitter(
            orchestrator, runRepo, userRepo, projectRepo, projectRepoService, gitHubRepoService);
    when(runRepo.findActiveByTriggeredByFinding(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of());
    when(runRepo.findById("run-1")).thenReturn(Optional.of(submitted));
  }

  @ParameterizedTest
  @EnumSource(FindingType.class)
  void externalCollaboratorUsesHostTenantAndActorBillingOrgForEveryFindingType(FindingType type) {
    allow("actor-org", "host-org");
    Finding finding = finding(type);
    if (finding.branchOverride() != null) allowPublication(finding);

    FindingPatchSubmitter.SubmitResult result = submitter.submit(finding);

    assertThat(result.outcome()).isEqualTo(FindingPatchSubmitter.SubmitResult.Outcome.DISPATCHED);
    assertThat(submissions).hasSize(1);
    InvocationOnMock invocation = submissions.get(0);
    assertThat(invocation.getArgument(1, String.class)).isEqualTo("finding:" + type.name());
    assertThat(invocation.getArgument(4, String.class)).isEqualTo("ghp_actor");
    assertThat(invocation.getArgument(11, String.class)).isEqualTo("actor-org");
    assertThat(invocation.getArgument(17, String.class)).isEqualTo(finding.branchOverride());
    assertThat(invocation.getArgument(19, String.class)).isEqualTo(RunMode.PATCH);
    assertThat(invocation.getArgument(23, Boolean.class)).isFalse();
    RunOrchestrator.SubmitPolicy policy = invocation.getArgument(24);
    assertThat(policy.tenantOrgId()).isEqualTo("host-org");
    assertThat(policy.billingOrgId()).isEqualTo("actor-org");
    assertThat(policy.triggeredByFinding()).isEqualTo(type.name() + ":finding-1");
    assertThat(policy.publicationRepoFullName()).isEqualTo(finding.publicationRepoFullName());
    assertThat(policy.publicationPrNumber()).isEqualTo(finding.publicationPrNumber());
    assertThat(policy.publicationHeadSha()).isEqualTo(finding.publicationHeadSha());
  }

  @Test
  void sameOrgFindingKeepsTenantAndBillingOrgEqual() {
    allow("shared-org", "shared-org");

    submitter.submit(finding(FindingType.TECH_DEBT));

    RunOrchestrator.SubmitPolicy policy = submissions.get(0).getArgument(24);
    assertThat(policy.tenantOrgId()).isEqualTo("shared-org");
    assertThat(policy.billingOrgId()).isEqualTo("shared-org");
  }

  @Test
  void missingActorFailsBeforeProjectLookupDedupeOrDispatch() {
    when(userRepo.findById("actor-1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> submitter.submit(finding(FindingType.CI_FAILURE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("actor does not exist");

    verify(projectRepo, never()).findById(org.mockito.ArgumentMatchers.anyString());
    verify(runRepo, never())
        .findActiveByTriggeredByFinding(org.mockito.ArgumentMatchers.anyString());
    assertThat(submissions).isEmpty();
  }

  @Test
  void missingProjectFailsBeforeDedupeOrDispatch() {
    when(userRepo.findById("actor-1")).thenReturn(Optional.of(activeUser("actor-1", "actor-org")));
    when(projectRepo.findById("project-1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> submitter.submit(finding(FindingType.CODE_REVIEW_ISSUE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("project does not exist");

    verify(runRepo, never())
        .findActiveByTriggeredByFinding(org.mockito.ArgumentMatchers.anyString());
    assertThat(submissions).isEmpty();
  }

  @Test
  void inactiveProjectFailsClosed() {
    when(userRepo.findById("actor-1")).thenReturn(Optional.of(activeUser("actor-1", "actor-org")));
    when(projectRepo.findById("project-1"))
        .thenReturn(
            Optional.of(
                Project.builder()
                    .id("project-1")
                    .orgId("host-org")
                    .name("project")
                    .createdBy("owner")
                    .status("ARCHIVED")
                    .build()));

    assertThatThrownBy(() -> submitter.submit(finding(FindingType.PR_REVIEW)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("project is not active");

    assertThat(submissions).isEmpty();
  }

  @Test
  void activeDedupeStillRequiresValidTenantBindingsAndDoesNotRedispatch() {
    allow("actor-org", "host-org");
    Finding finding = finding(FindingType.PR_REVIEW);
    PipelineRun active =
        PipelineRun.builder()
            .runId("existing-run")
            .status("WORKING")
            .triggeredByFinding(finding.findingKey())
            .createdByUserId(finding.userId())
            .projectId(finding.projectId())
            .orgId("host-org")
            .billingOrgId("actor-org")
            .selectedRepoIds("[\"" + finding.selectedRepoIds().get(0) + "\"]")
            .checkoutBranch(finding.branchOverride())
            .branchOverride(finding.branchOverride())
            .publicationRepoFullName(finding.publicationRepoFullName())
            .publicationPrNumber(finding.publicationPrNumber())
            .publicationHeadSha(finding.publicationHeadSha())
            .build();
    when(runRepo.findActiveByTriggeredByFinding("PR_REVIEW:finding-1")).thenReturn(List.of(active));

    FindingPatchSubmitter.SubmitResult result = submitter.submit(finding);

    assertThat(result.outcome())
        .isEqualTo(FindingPatchSubmitter.SubmitResult.Outcome.REUSED_ACTIVE);
    assertThat(result.runId()).isEqualTo("existing-run");
    assertThat(submissions).isEmpty();
    verify(projectRepoService, never())
        .resolveForRunStrict(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void legacyActiveRunWithoutExactPublicationAuthorityFailsClosed() {
    allow("actor-org", "host-org");
    PipelineRun legacy = PipelineRun.builder().runId("legacy-run").status("WORKING").build();
    when(runRepo.findActiveByTriggeredByFinding("CI_FAILURE:finding-1"))
        .thenReturn(List.of(legacy));

    assertThatThrownBy(() -> submitter.submit(finding(FindingType.CI_FAILURE)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    assertThat(submissions).isEmpty();
  }

  @Test
  void closedOrReplacedPullRequestFailsBeforePatchDispatchOrProviderSpend() {
    allow("actor-org", "host-org");
    Finding finding = finding(FindingType.CI_FAILURE);
    ProjectRepo repository = publicationRepository(finding);
    when(projectRepoService.resolveForRunStrict(finding.projectId(), finding.selectedRepoIds()))
        .thenReturn(List.of(repository));
    when(gitHubRepoService.findOpenPullRequestForHead(
            finding.publicationRepoFullName(), finding.branchOverride(), "ghp_actor"))
        .thenReturn(GitHubRepoService.OpenPullRequestLookup.none());

    assertThatThrownBy(() -> submitter.submit(finding))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));

    assertThat(submissions).isEmpty();
  }

  @Test
  void pullRequestAgainstChangedBaseFailsBeforePatchDispatch() {
    allow("actor-org", "host-org");
    Finding finding = finding(FindingType.PR_REVIEW);
    ProjectRepo repository = publicationRepository(finding);
    when(projectRepoService.resolveForRunStrict(finding.projectId(), finding.selectedRepoIds()))
        .thenReturn(List.of(repository));
    when(gitHubRepoService.findOpenPullRequestForHead(
            finding.publicationRepoFullName(), finding.branchOverride(), "ghp_actor"))
        .thenReturn(
            GitHubRepoService.OpenPullRequestLookup.found(
                "https://github.com/acme/repo/pull/12", 12, "release", "a".repeat(40)));

    assertThatThrownBy(() -> submitter.submit(finding))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    assertThat(submissions).isEmpty();
  }

  @Test
  void staleCiHeadFailsBeforePatchDispatch() {
    allow("actor-org", "host-org");
    Finding finding = finding(FindingType.CI_FAILURE);
    when(projectRepoService.resolveForRunStrict(finding.projectId(), finding.selectedRepoIds()))
        .thenReturn(List.of(publicationRepository(finding)));
    when(gitHubRepoService.findOpenPullRequestForHead(
            finding.publicationRepoFullName(), finding.branchOverride(), "ghp_actor"))
        .thenReturn(
            GitHubRepoService.OpenPullRequestLookup.found(
                "https://github.com/acme/repo/pull/12", 12, "main", "b".repeat(40)));

    assertThatThrownBy(() -> submitter.submit(finding))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    assertThat(submissions).isEmpty();
  }

  private void allow(String actorOrgId, String projectOrgId) {
    when(userRepo.findById("actor-1")).thenReturn(Optional.of(activeUser("actor-1", actorOrgId)));
    when(projectRepo.findById("project-1"))
        .thenReturn(
            Optional.of(
                Project.builder()
                    .id("project-1")
                    .orgId(projectOrgId)
                    .name("project")
                    .createdBy("owner")
                    .targetBranch("main")
                    .status("ACTIVE")
                    .build()));
  }

  private static AppUser activeUser(String id, String orgId) {
    return AppUser.builder()
        .id(id)
        .email(id + "@example.test")
        .firstName("Actor")
        .status("ACTIVE")
        .orgId(orgId)
        .githubPat("ghp_actor")
        .build();
  }

  private void allowPublication(Finding finding) {
    ProjectRepo repository = publicationRepository(finding);
    when(projectRepoService.resolveForRunStrict(finding.projectId(), finding.selectedRepoIds()))
        .thenReturn(List.of(repository));
    when(gitHubRepoService.findOpenPullRequestForHead(
            finding.publicationRepoFullName(), finding.branchOverride(), "ghp_actor"))
        .thenReturn(
            GitHubRepoService.OpenPullRequestLookup.found(
                "https://github.com/acme/repo/pull/12", 12, "main", "a".repeat(40)));
  }

  private static ProjectRepo publicationRepository(Finding finding) {
    return ProjectRepo.builder()
        .id(finding.selectedRepoIds().get(0))
        .githubRepoFullName(finding.publicationRepoFullName())
        .scmProvider("GITHUB")
        .defaultBranch("main")
        .build();
  }

  private static Finding finding(FindingType type) {
    boolean prShaped = type == FindingType.PR_REVIEW || type == FindingType.CI_FAILURE;
    return new Finding(
        type,
        "finding-1",
        "project-1",
        List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")),
        "Fix the finding",
        prShaped ? "feature/fix" : null,
        "actor-1",
        "ANTHROPIC",
        prShaped ? "acme/repo" : null,
        prShaped ? 12 : null,
        prShaped ? "a".repeat(40) : null);
  }
}
