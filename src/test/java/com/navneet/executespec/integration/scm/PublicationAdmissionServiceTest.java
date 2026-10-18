package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.CompatibilityReport;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.ContractCompatibilityException;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor.RouteKey;
import com.navneet.executespec.cost.EffectiveRouteProjection;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.DurableCorrectionLifecycle;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationRecoveryService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PublicationAdmissionServiceTest {

  private static final String RUN_ID = "run-publication";
  private static final String TASK_KEY = RUN_ID.substring(0, 8) + "_T1";
  private static final String GENERATION = "a".repeat(64);
  private static final String APPROVAL_ID = "11111111-1111-1111-1111-111111111111";
  private static final String SECOND_APPROVAL_ID = "22222222-2222-2222-2222-222222222222";

  private final ValidationReportRepository reports = mock(ValidationReportRepository.class);
  private final AgentTaskRepository tasks = mock(AgentTaskRepository.class);
  private final CorrectionWorkspaceTransaction generations =
      mock(CorrectionWorkspaceTransaction.class);
  private final AgentProperties properties = new AgentProperties();
  private final BrownfieldHttpContractGuard brownfieldGuard =
      mock(BrownfieldHttpContractGuard.class);
  private final RunEffectiveRouteService effectiveRoutes = mock(RunEffectiveRouteService.class);
  private final DurableCorrectionLifecycle durableCorrections =
      mock(DurableCorrectionLifecycle.class);
  private final RunWorkspaceAuthorityService workspaceAuthorities =
      mock(RunWorkspaceAuthorityService.class);
  private final RunWorkspaceAuthorityService.Lease publicationLease =
      mock(RunWorkspaceAuthorityService.Lease.class);
  private final WorkspaceGenerationRecoveryService workspaceGenerationRecovery =
      mock(WorkspaceGenerationRecoveryService.class);
  private final PublicationPreparationService publicationPreparations =
      mock(PublicationPreparationService.class);
  private final PublicationAdmissionService service =
      new PublicationAdmissionService(
          reports,
          tasks,
          generations,
          properties,
          new ObjectMapper(),
          brownfieldGuard,
          effectiveRoutes,
          durableCorrections,
          workspaceAuthorities,
          workspaceGenerationRecovery,
          publicationPreparations);

  @BeforeEach
  void setUp() {
    properties.setWorkspace("/tmp/executespec-publication-test");
    when(workspaceAuthorities.acquire(RUN_ID, RunWorkspaceAuthorityService.Authority.PUBLICATION))
        .thenReturn(publicationLease);
    when(reports.findByRunId(RUN_ID)).thenReturn(Optional.of(greenReport()));
    when(generations.captureGeneration(Path.of("/tmp/executespec-publication-test", RUN_ID)))
        .thenReturn(GENERATION);
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of());
    when(brownfieldGuard.hasBaseline(RUN_ID)).thenReturn(false);
    when(publicationPreparations.requirePrepared(any(PipelineRun.class)))
        .thenReturn(
            new PublicationPreparationService.RequiredPreparation(
                new PublicationPreparationStore.PreparedPublication(
                    java.util.UUID.randomUUID(),
                    RUN_ID,
                    GENERATION,
                    "report-1",
                    "c".repeat(64),
                    java.time.Instant.now()),
                List.of(
                    new PublicationPreparationStore.PreparedRepository(
                        java.util.UUID.randomUUID(),
                        "GITHUB:owner/repo",
                        "d".repeat(64),
                        ".",
                        "executespec/run",
                        "1".repeat(40),
                        "2".repeat(40),
                        "3".repeat(40),
                        true,
                        false,
                        "prepared commit",
                        java.time.Instant.parse("2026-08-12T12:00:00Z")))));
  }

  @Test
  void publicationAuthorityRechecksParentAfterAdmissionBeforeEachExternalEffect() {
    PipelineRun run = new PipelineRun();
    run.setRunId(RUN_ID);
    run.setSourceType("EXECUTION_VIEW_APPROVED");
    var fence = mock(com.navneet.executespec.integration.RunPublicationFence.class);
    when(fence.mayPublish(run)).thenReturn(true, false);
    var admission =
        new PublicationAdmissionService.Admission(
            GENERATION,
            "PUBLICATION_ELIGIBLE",
            publicationLease,
            null,
            PublicationAuthority.parentFence(run, fence));
    admission.assertOwned();
    assertThatThrownBy(admission::assertOwned)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PUBLICATION_PARENT_AUTHORITY_LOST");
  }

  @Test
  void admitsFreshBuildAndVerifiedTests() {
    assertThat(service.assertEligible(run()).workspaceGeneration()).isEqualTo(GENERATION);
    verify(workspaceGenerationRecovery, org.mockito.Mockito.times(2))
        .assertAcceptedFrontier(RUN_ID, GENERATION, "PUBLICATION");
  }

  @Test
  void unpinnedAndUnknownBuildHistoryCannotPublish() {
    PipelineRun unpinned = run();
    unpinned.setBuildProtocolVersion(null);
    PipelineRun unknown = run();
    unknown.setBuildProtocolVersion("LEGACY_UNKNOWN");
    unknown.setBuildProtocolDisposition("MISSING_EVIDENCE");

    assertThat(service.evaluate(unpinned).code())
        .isEqualTo("BUILD_PROTOCOL_HISTORICAL_EVIDENCE_INCOMPLETE");
    assertThat(service.evaluate(unknown).code())
        .isEqualTo("BUILD_PROTOCOL_HISTORICAL_EVIDENCE_INCOMPLETE");
    verifyNoInteractions(durableCorrections);
  }

  @Test
  void acceptedCorrectionSupersedesInitialBuildFrontierForPublication() {
    when(durableCorrections.latestAcceptedGeneration(RUN_ID)).thenReturn(Optional.of(GENERATION));

    assertThat(service.evaluate(run()).eligible()).isTrue();

    verify(workspaceGenerationRecovery, never())
        .assertAcceptedFrontier(anyString(), anyString(), anyString());
  }

  @Test
  void workspaceMustEqualLatestAcceptedCorrectionGeneration() {
    when(durableCorrections.latestAcceptedGeneration(RUN_ID))
        .thenReturn(Optional.of("b".repeat(64)));

    assertBlocked("CORRECTION_GENERATION_FRONTIER_MISMATCH");

    verify(workspaceGenerationRecovery, never())
        .assertAcceptedFrontier(anyString(), anyString(), anyString());
  }

  @Test
  void malformedAcceptedCorrectionFailsClosed() {
    when(durableCorrections.latestAcceptedGeneration(RUN_ID))
        .thenThrow(new IllegalStateException("CORRECTION_ACCEPTED_GENERATION_INVALID"));

    assertBlocked("CORRECTION_ACCEPTANCE_INVALID");

    verify(workspaceGenerationRecovery, never())
        .assertAcceptedFrontier(anyString(), anyString(), anyString());
  }

  @Test
  void admitsVerifiedGreenSuiteWithExplicitSkipsWithoutHidingRealFailures() {
    ValidationReport report = greenReport();
    report.setTestsPassed(82);
    report.setTestsSkipped(6);
    report.setTestsTotal(88);
    when(reports.findByRunId(RUN_ID)).thenReturn(Optional.of(report));

    assertThat(service.evaluate(run()))
        .extracting(
            PublicationAdmissionService.Eligibility::eligible,
            PublicationAdmissionService.Eligibility::code)
        .containsExactly(true, "PUBLICATION_ELIGIBLE");

    report.setTestsPassed(81); // 81 pass + 6 skip leaves one real failure/error.
    assertBlocked("PUBLICATION_TESTS_FAILED");

    report.setTestsPassed(0);
    report.setTestsSkipped(88);
    assertBlocked("PUBLICATION_TESTS_UNPROVEN");
  }

  @Test
  void unresolvedCorrectionBlocksBeforeWorkspaceOrEvidenceInspection() {
    org.mockito.Mockito.doThrow(new IllegalStateException("PREPARED"))
        .when(durableCorrections)
        .assertNoUnresolvedAttempt(RUN_ID);

    assertBlocked("CORRECTION_ATTEMPT_UNRESOLVED");

    verify(generations, never()).captureGeneration(any(Path.class));
    verify(reports, never()).findByRunId(RUN_ID);
  }

  @Test
  void admitsFreshCorrectionWorkspaceSuiteWithExactCommandEvidence() {
    ValidationReport report = greenReport();
    report.setTestEvidenceScope("WORKSPACE_TEST_SUITE");
    report.setTestCommand("./mvnw test");
    report.setTestWorkingDirectory("/tmp/executespec-publication-test/" + RUN_ID);
    report.setTestExitCode(0);
    report.setTestOutput("Tests run: 2, Failures: 0, Errors: 0");
    when(reports.findByRunId(RUN_ID)).thenReturn(Optional.of(report));

    assertThat(service.evaluate(run()))
        .extracting(
            PublicationAdmissionService.Eligibility::eligible,
            PublicationAdmissionService.Eligibility::code)
        .containsExactly(true, "PUBLICATION_ELIGIBLE");
  }

  @Test
  void staleWorkspaceEvidenceIsBlocked() {
    when(generations.captureGeneration(Path.of("/tmp/executespec-publication-test", RUN_ID)))
        .thenReturn("b".repeat(64));

    assertBlocked("PUBLICATION_EVIDENCE_STALE");
  }

  @Test
  void missingOrFailedTestsAreBlocked() {
    ValidationReport report = greenReport();
    report.setTestExecutionVerified(false);
    when(reports.findByRunId(RUN_ID)).thenReturn(Optional.of(report));

    assertBlocked("PUBLICATION_TESTS_UNPROVEN");

    report.setTestExecutionVerified(true);
    report.setTestsPassed(1);
    report.setTestsTotal(2);
    assertBlocked("PUBLICATION_TESTS_FAILED");
  }

  @Test
  void missingOrUnavailableTestEvidenceScopeIsBlocked() {
    ValidationReport report = greenReport();
    report.setTestEvidenceScope(null);
    when(reports.findByRunId(RUN_ID)).thenReturn(Optional.of(report));

    assertBlocked("PUBLICATION_TESTS_UNPROVEN");

    report.setTestEvidenceScope("UNAVAILABLE");
    assertBlocked("PUBLICATION_TESTS_UNPROVEN");

    report.setTestEvidenceScope("LIVE_PREVIEW_FUNCTIONAL");
    assertThat(service.evaluate(run()).eligible()).isTrue();
  }

  @Test
  void completedReportWithFailedDeterministicValidationIsBlocked() {
    ValidationReport report = greenReport();
    report.setPipelineResult(PipelineResult.FAILED);
    when(reports.findByRunId(RUN_ID)).thenReturn(Optional.of(report));

    assertThat(service.evaluate(run()))
        .extracting(
            PublicationAdmissionService.Eligibility::code,
            PublicationAdmissionService.Eligibility::blockingGate)
        .containsExactly("PUBLICATION_VALIDATION_FAILED", "VALIDATION");
  }

  @Test
  void approvedGrokCorrectionUsesFenceSpecificAttemptInsteadOfOldDeepSeekTaskHistory() {
    AgentTask task =
        AgentTask.builder()
            .taskId(TASK_KEY)
            .runId(RUN_ID)
            .provider("openrouter")
            .model("deepseek-v4-pro")
            .effectiveProvider("openrouter")
            .effectiveModel("deepseek-v4-pro")
            .effectiveRouteAttribution("LEDGER")
            .continuationActionsJson(
                """
                [{
                  "status":"APPROVED",
                  "decision":"CHANGE_PROVIDER",
                  "provider":"openrouter",
                  "model":"x-ai/grok-4.20",
                  "approvalId":"%s",
                  "proposalDigest":"%s",
                  "workerAttempt":{
                    "provider":"openrouter",
                    "model":"x-ai/grok-4.20",
                    "attribution":"LEDGER",
                    "callIndexFloor":4,
                    "firstCallIndex":5,
                    "lastCallIndex":6,
                    "physicalAttemptIds":["grok-worker-1","grok-worker-2"]
                  },
                  "reviewerAttempt":{
                    "provider":"openrouter",
                    "model":"x-ai/grok-4.20",
                    "attribution":"LEDGER",
                    "callIndexFloor":6,
                    "firstCallIndex":7,
                    "lastCallIndex":7,
                    "physicalAttemptIds":["grok-reviewer-1"]
                  }
                }]
                """
                    .formatted(APPROVAL_ID, "a".repeat(64)))
            .build();
    bindApprovalIdentity(task, "a".repeat(64), false);
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of(task));
    when(effectiveRoutes.forTaskPhysicalAttempts(
            RUN_ID, "T1", List.of("grok-worker-1", "grok-worker-2")))
        .thenReturn(exactAttempt("openrouter", "x-ai/grok-4.20", "grok-worker-1", "grok-worker-2"));
    when(effectiveRoutes.forReviewerPhysicalAttempts(RUN_ID, List.of("grok-reviewer-1")))
        .thenReturn(exactAttempt("openrouter", "x-ai/grok-4.20", 7, 7, "grok-reviewer-1"));

    assertThat(service.assertEligible(run()).code()).isEqualTo("PUBLICATION_ELIGIBLE");

    when(effectiveRoutes.forTaskPhysicalAttempts(
            RUN_ID, "T1", List.of("grok-worker-1", "grok-worker-2")))
        .thenReturn(
            exactAttempt("openrouter", "deepseek-v4-pro", "grok-worker-1", "grok-worker-2"));
    assertBlocked("PUBLICATION_APPROVED_ROUTE_MISMATCH");
  }

  @Test
  void legacyApprovalWithoutFenceSpecificPhysicalIdentityFailsClosed() {
    AgentTask task =
        AgentTask.builder()
            .taskId(TASK_KEY)
            .runId(RUN_ID)
            .continuationActionsJson(
                """
                [{"status":"APPROVED","provider":"openrouter","model":"x-ai/grok-4.20"}]
                """)
            .build();
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of(task));

    assertBlocked("PUBLICATION_ROUTE_EVIDENCE_INVALID");
  }

  @Test
  void durableApprovalIdentityWithoutAppendOnlyActionEvidenceFailsClosed() {
    AgentTask task = AgentTask.builder().taskId(TASK_KEY).runId(RUN_ID).build();
    bindApprovalIdentity(task, "a".repeat(64), false);
    task.setContinuationActionsJson(null);
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of(task));

    assertBlocked("PUBLICATION_ROUTE_EVIDENCE_INVALID");
    verify(effectiveRoutes, never()).forTaskPhysicalAttempts(any(), any(), any());
  }

  @Test
  void publicationPreparationMustPreserveAdmittedWorkspaceGeneration() {
    PublicationAdmissionService.Admission admission = service.assertEligible(run());
    when(generations.captureGeneration(Path.of("/tmp/executespec-publication-test", RUN_ID)))
        .thenReturn("b".repeat(64));

    assertThatThrownBy(() -> service.assertWorkspaceGenerationUnchanged(run(), admission))
        .isInstanceOf(PublicationAdmissionService.PublicationBlockedException.class)
        .satisfies(
            failure ->
                assertThat(
                        ((PublicationAdmissionService.PublicationBlockedException) failure).code())
                    .isEqualTo("PUBLICATION_EVIDENCE_STALE"));
    verify(publicationLease).assertOwned();
  }

  @Test
  void postReviewApprovalMustMatchPhysicalReviewerRoute() {
    AgentTask task =
        AgentTask.builder()
            .taskId(TASK_KEY)
            .runId(RUN_ID)
            .provider("openrouter")
            .model("x-ai/grok-4.20")
            .effectiveProvider("openrouter")
            .effectiveModel("x-ai/grok-4.20")
            .effectiveRouteAttribution("LEDGER")
            .continuationActionsJson(
                """
                [{
                  "status":"APPROVED",
                  "decision":"CHANGE_PROVIDER",
                  "provider":"openrouter",
                  "model":"x-ai/grok-4.20",
                  "postReview":true,
                  "approvalId":"%s",
                  "proposalDigest":"%s",
                  "workerAttempt":{
                    "provider":"openrouter",
                    "model":"x-ai/grok-4.20",
                    "attribution":"LEDGER",
                    "callIndexFloor":4,
                    "firstCallIndex":5,
                    "lastCallIndex":5,
                    "physicalAttemptIds":["worker-new"]
                  },
                  "reviewerAttempt":{
                    "provider":"openrouter",
                    "model":"x-ai/grok-4.20",
                    "attribution":"LEDGER",
                    "callIndexFloor":5,
                    "firstCallIndex":6,
                    "lastCallIndex":6,
                    "physicalAttemptIds":["reviewer-new"]
                  }
                }]
                """
                    .formatted(APPROVAL_ID, "b".repeat(64)))
            .build();
    bindApprovalIdentity(task, "b".repeat(64), true);
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of(task));
    when(effectiveRoutes.forTaskPhysicalAttempts(RUN_ID, "T1", List.of("worker-new")))
        .thenReturn(exactAttempt("openrouter", "x-ai/grok-4.20", 5, 5, "worker-new"));
    when(effectiveRoutes.forReviewerPhysicalAttempts(RUN_ID, List.of("reviewer-new")))
        .thenReturn(exactAttempt("anthropic", "claude-sonnet-4-6", 6, 6, "reviewer-new"));

    assertThat(service.evaluate(run()).code())
        .isEqualTo("PUBLICATION_APPROVED_REVIEWER_ROUTE_MISMATCH");

    when(effectiveRoutes.forReviewerPhysicalAttempts(RUN_ID, List.of("reviewer-new")))
        .thenReturn(exactAttempt("openrouter", "x-ai/grok-4.20", 6, 6, "reviewer-new"));
    PublicationAdmissionService.Eligibility eligible = service.evaluate(run());
    assertThat(eligible.eligible()).as(eligible.toString()).isTrue();
  }

  @Test
  void everyApprovedActionIsAttestedInDurableOrder() {
    AgentTask task = taskWithTwoApprovedActions();
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of(task));
    when(effectiveRoutes.forTaskPhysicalAttempts(RUN_ID, "T1", List.of("worker-earlier")))
        .thenReturn(exactAttempt("openrouter", "x-ai/grok-4.20", 2, 2, "worker-earlier"));
    when(effectiveRoutes.forReviewerPhysicalAttempts(RUN_ID, List.of("reviewer-earlier")))
        .thenReturn(exactAttempt("openrouter", "x-ai/grok-4.20", 3, 3, "reviewer-earlier"));
    when(effectiveRoutes.forTaskPhysicalAttempts(RUN_ID, "T1", List.of("worker-latest")))
        .thenReturn(exactAttempt("anthropic", "claude-sonnet-4-6", 4, 4, "worker-latest"));
    when(effectiveRoutes.forReviewerPhysicalAttempts(RUN_ID, List.of("reviewer-latest")))
        .thenReturn(exactAttempt("anthropic", "claude-sonnet-4-6", 5, 5, "reviewer-latest"));

    assertThat(service.assertEligible(run()).code()).isEqualTo("PUBLICATION_ELIGIBLE");

    InOrder durableOrder = inOrder(effectiveRoutes);
    durableOrder
        .verify(effectiveRoutes)
        .forTaskPhysicalAttempts(RUN_ID, "T1", List.of("worker-earlier"));
    durableOrder
        .verify(effectiveRoutes)
        .forReviewerPhysicalAttempts(RUN_ID, List.of("reviewer-earlier"));
    durableOrder
        .verify(effectiveRoutes)
        .forTaskPhysicalAttempts(RUN_ID, "T1", List.of("worker-latest"));
    durableOrder
        .verify(effectiveRoutes)
        .forReviewerPhysicalAttempts(RUN_ID, List.of("reviewer-latest"));
  }

  @Test
  void earlierApprovedActionMismatchBlocksBeforeLaterMatchingAction() {
    AgentTask task = taskWithTwoApprovedActions();
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of(task));
    when(effectiveRoutes.forTaskPhysicalAttempts(RUN_ID, "T1", List.of("worker-earlier")))
        .thenReturn(exactAttempt("openrouter", "deepseek-v4-pro", 2, 2, "worker-earlier"));
    when(effectiveRoutes.forTaskPhysicalAttempts(RUN_ID, "T1", List.of("worker-latest")))
        .thenReturn(exactAttempt("anthropic", "claude-sonnet-4-6", 4, 4, "worker-latest"));

    assertBlocked("PUBLICATION_APPROVED_ROUTE_MISMATCH");

    verify(effectiveRoutes, never())
        .forTaskPhysicalAttempts(RUN_ID, "T1", List.of("worker-latest"));
  }

  @Test
  void rejectedEarlierApprovalWithExactRollbackProofDoesNotPoisonLaterAcceptedCorrection()
      throws Exception {
    AgentTask task = taskWithTwoApprovedActions();
    ObjectMapper mapper = new ObjectMapper();
    ArrayNode actions = (ArrayNode) mapper.readTree(task.getContinuationActionsJson());
    ObjectNode resolution = mapper.createObjectNode();
    resolution.put("status", "REJECTED");
    resolution.put("decision", "CORRECTION_RESOLUTION");
    resolution.put("approvalId", APPROVAL_ID);
    resolution.put("proposalDigest", "c".repeat(64));
    resolution.put("beforeWorkspaceGeneration", GENERATION);
    resolution.put("afterWorkspaceGeneration", GENERATION);
    resolution.put("evidenceInvalidated", true);
    actions.insert(1, resolution);
    task.setContinuationActionsJson(mapper.writeValueAsString(actions));
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of(task));
    when(effectiveRoutes.forTaskPhysicalAttempts(RUN_ID, "T1", List.of("worker-latest")))
        .thenReturn(exactAttempt("anthropic", "claude-sonnet-4-6", 4, 4, "worker-latest"));
    when(effectiveRoutes.forReviewerPhysicalAttempts(RUN_ID, List.of("reviewer-latest")))
        .thenReturn(exactAttempt("anthropic", "claude-sonnet-4-6", 5, 5, "reviewer-latest"));

    assertThat(service.assertEligible(run()).code()).isEqualTo("PUBLICATION_ELIGIBLE");

    verify(effectiveRoutes, never())
        .forTaskPhysicalAttempts(RUN_ID, "T1", List.of("worker-earlier"));
  }

  @Test
  void rejectedApprovalStillReservesItsPhysicalAttemptIdentities() throws Exception {
    AgentTask task = taskWithTwoApprovedActions();
    ObjectMapper mapper = new ObjectMapper();
    ArrayNode actions = (ArrayNode) mapper.readTree(task.getContinuationActionsJson());
    ObjectNode resolution = mapper.createObjectNode();
    resolution.put("status", "ROLLED_BACK");
    resolution.put("decision", "CORRECTION_RESOLUTION");
    resolution.put("approvalId", APPROVAL_ID);
    resolution.put("proposalDigest", "c".repeat(64));
    resolution.put("beforeWorkspaceGeneration", GENERATION);
    resolution.put("afterWorkspaceGeneration", GENERATION);
    resolution.put("evidenceInvalidated", true);
    actions.insert(1, resolution);
    task.setContinuationActionsJson(
        mapper.writeValueAsString(actions).replace("worker-latest", "worker-earlier"));
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of(task));

    assertBlocked("PUBLICATION_ROUTE_EVIDENCE_INVALID");

    verify(effectiveRoutes, never())
        .forTaskPhysicalAttempts(RUN_ID, "T1", List.of("worker-latest"));
  }

  @Test
  void earlierLegacyApprovalWithoutIdentityBlocksEvenWhenLatestReceiptIsValid() {
    AgentTask task = taskWithTwoApprovedActions();
    task.setContinuationActionsJson(
        task.getContinuationActionsJson().replace("\"approvalId\":\"" + APPROVAL_ID + "\",", ""));
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of(task));

    assertBlocked("PUBLICATION_ROUTE_EVIDENCE_INVALID");

    verify(effectiveRoutes, never())
        .forTaskPhysicalAttempts(RUN_ID, "T1", List.of("worker-latest"));
  }

  @Test
  void approvedActionsCannotReusePhysicalAttemptIdentity() {
    AgentTask task = taskWithTwoApprovedActions();
    task.setContinuationActionsJson(
        task.getContinuationActionsJson().replace("worker-latest", "worker-earlier"));
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of(task));
    when(effectiveRoutes.forTaskPhysicalAttempts(RUN_ID, "T1", List.of("worker-earlier")))
        .thenReturn(exactAttempt("openrouter", "x-ai/grok-4.20", 2, 2, "worker-earlier"));
    when(effectiveRoutes.forReviewerPhysicalAttempts(RUN_ID, List.of("reviewer-earlier")))
        .thenReturn(exactAttempt("openrouter", "x-ai/grok-4.20", 3, 3, "reviewer-earlier"));

    assertBlocked("PUBLICATION_ROUTE_EVIDENCE_INVALID");
  }

  @Test
  void approvedActionsCannotDuplicateApprovalOrProposalIdentity() {
    AgentTask duplicateApproval = taskWithTwoApprovedActions();
    duplicateApproval.setContinuationActionsJson(
        duplicateApproval.getContinuationActionsJson().replace(SECOND_APPROVAL_ID, APPROVAL_ID));
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of(duplicateApproval));

    assertBlocked("PUBLICATION_ROUTE_EVIDENCE_INVALID");

    AgentTask duplicateProposal = taskWithTwoApprovedActions();
    duplicateProposal.setContinuationActionsJson(
        duplicateProposal.getContinuationActionsJson().replace("d".repeat(64), "c".repeat(64)));
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of(duplicateProposal));

    assertBlocked("PUBLICATION_ROUTE_EVIDENCE_INVALID");
  }

  @Test
  void failedOrMissingWorkflowEvidenceIsBlocked() {
    ValidationReport report = greenReport();
    report.setWorkflowConfigEvidenceJson(
        """
        {"status":"FAILED","files":[],"reason":"release.yml is invalid"}
        """);
    when(reports.findByRunId(RUN_ID)).thenReturn(Optional.of(report));

    assertThat(service.evaluate(run()))
        .extracting(
            PublicationAdmissionService.Eligibility::code,
            PublicationAdmissionService.Eligibility::blockingGate)
        .containsExactly("PUBLICATION_WORKFLOW_CONFIG_FAILED", "WORKFLOW_CONFIG");

    report.setWorkflowConfigEvidenceJson(null);
    assertThat(service.evaluate(run()).code()).isEqualTo("PUBLICATION_WORKFLOW_CONFIG_UNPROVEN");
  }

  @Test
  void readOnlyEvaluationReturnsStructuredStaleEvidenceWithoutThrowing() {
    when(generations.captureGeneration(Path.of("/tmp/executespec-publication-test", RUN_ID)))
        .thenReturn("b".repeat(64));

    assertThat(service.evaluate(run()))
        .extracting(
            PublicationAdmissionService.Eligibility::eligible,
            PublicationAdmissionService.Eligibility::eligibility,
            PublicationAdmissionService.Eligibility::code,
            PublicationAdmissionService.Eligibility::blockingGate,
            PublicationAdmissionService.Eligibility::retryable,
            PublicationAdmissionService.Eligibility::evidenceWorkspaceGeneration,
            PublicationAdmissionService.Eligibility::currentWorkspaceGeneration)
        .containsExactly(
            false,
            "BLOCKED",
            "PUBLICATION_EVIDENCE_STALE",
            "EVIDENCE_FRESHNESS",
            true,
            GENERATION,
            "b".repeat(64));
  }

  @Test
  void readOnlyEvaluationIncludesProtectedContractsWithoutPersistingThroughTheAssertPath() {
    PipelineRun brownfield = run();
    brownfield.setRepoMode("EXISTING");
    when(brownfieldGuard.inspectCompatibility(RUN_ID))
        .thenThrow(
            new ContractCompatibilityException(
                "BROWNFIELD_HTTP_CONTRACT_REMOVED", "POST /v1/orders was removed"));

    assertThat(service.evaluate(brownfield))
        .extracting(
            PublicationAdmissionService.Eligibility::eligible,
            PublicationAdmissionService.Eligibility::blockingGate,
            PublicationAdmissionService.Eligibility::code,
            PublicationAdmissionService.Eligibility::retryable)
        .containsExactly(false, "PROTECTED_CONTRACTS", "BROWNFIELD_HTTP_CONTRACT_REMOVED", false);
  }

  @Test
  void nonThrowingIncompatibleProtectedContractReportIsBlocked() {
    PipelineRun brownfield = run();
    brownfield.setRepoMode("EXISTING");
    when(brownfieldGuard.inspectCompatibility(RUN_ID))
        .thenReturn(
            new CompatibilityReport(
                "baseline",
                "current",
                List.of(new RouteKey("POST", "/v1/orders")),
                List.of(),
                false,
                false));

    assertThat(service.evaluate(brownfield))
        .extracting(
            PublicationAdmissionService.Eligibility::eligible,
            PublicationAdmissionService.Eligibility::blockingGate,
            PublicationAdmissionService.Eligibility::code)
        .containsExactly(false, "PROTECTED_CONTRACTS", "BROWNFIELD_HTTP_CONTRACT_REMOVED");
  }

  private void assertBlocked(String code) {
    assertThatThrownBy(() -> service.assertEligible(run()))
        .isInstanceOf(PublicationAdmissionService.PublicationBlockedException.class)
        .satisfies(
            failure ->
                assertThat(
                        ((PublicationAdmissionService.PublicationBlockedException) failure).code())
                    .isEqualTo(code));
  }

  private static PipelineRun run() {
    return PipelineRun.builder()
        .runId(RUN_ID)
        .status("COMPLETE")
        .buildProtocolVersion("LEGACY_V1")
        .build();
  }

  private static void bindApprovalIdentity(
      AgentTask task, String proposalDigest, boolean postReview) {
    bindApprovalIdentity(
        task,
        APPROVAL_ID,
        proposalDigest,
        "openrouter",
        "x-ai/grok-4.20",
        "CHANGE_PROVIDER",
        postReview);
  }

  private static void bindApprovalIdentity(
      AgentTask task,
      String approvalId,
      String proposalDigest,
      String provider,
      String model,
      String action,
      boolean postReview) {
    task.setContinuationApprovalId(approvalId);
    task.setContinuationApprovalProposalDigest(proposalDigest);
    task.setContinuationApprovalStatus("COMPLETED");
    task.setContinuationApprovalProvider(provider);
    task.setContinuationApprovalModel(model);
    task.setContinuationApprovalAction(action);
    task.setContinuationApprovalPostReview(postReview);
  }

  private static AgentTask taskWithTwoApprovedActions() {
    AgentTask task =
        AgentTask.builder()
            .taskId(TASK_KEY)
            .runId(RUN_ID)
            .continuationActionsJson(
                """
                [{
                  "status":"APPROVED",
                  "decision":"CHANGE_PROVIDER",
                  "provider":"openrouter",
                  "model":"x-ai/grok-4.20",
                  "approvalId":"%s",
                  "proposalDigest":"%s",
                  "workerAttempt":{
                    "provider":"openrouter",
                    "model":"x-ai/grok-4.20",
                    "attribution":"LEDGER",
                    "callIndexFloor":1,
                    "firstCallIndex":2,
                    "lastCallIndex":2,
                    "physicalAttemptIds":["worker-earlier"]
                  },
                  "reviewerAttempt":{
                    "provider":"openrouter",
                    "model":"x-ai/grok-4.20",
                    "attribution":"LEDGER",
                    "callIndexFloor":2,
                    "firstCallIndex":3,
                    "lastCallIndex":3,
                    "physicalAttemptIds":["reviewer-earlier"]
                  }
                },{
                  "status":"APPROVED",
                  "decision":"STEP_UP_MODEL",
                  "provider":"anthropic",
                  "model":"claude-sonnet-4-6",
                  "approvalId":"%s",
                  "proposalDigest":"%s",
                  "workerAttempt":{
                    "provider":"anthropic",
                    "model":"claude-sonnet-4-6",
                    "attribution":"LEDGER",
                    "callIndexFloor":3,
                    "firstCallIndex":4,
                    "lastCallIndex":4,
                    "physicalAttemptIds":["worker-latest"]
                  },
                  "reviewerAttempt":{
                    "provider":"anthropic",
                    "model":"claude-sonnet-4-6",
                    "attribution":"LEDGER",
                    "callIndexFloor":4,
                    "firstCallIndex":5,
                    "lastCallIndex":5,
                    "physicalAttemptIds":["reviewer-latest"]
                  }
                }]
                """
                    .formatted(APPROVAL_ID, "c".repeat(64), SECOND_APPROVAL_ID, "d".repeat(64)))
            .build();
    bindApprovalIdentity(
        task,
        SECOND_APPROVAL_ID,
        "d".repeat(64),
        "anthropic",
        "claude-sonnet-4-6",
        "STEP_UP_MODEL",
        false);
    return task;
  }

  private static RunEffectiveRouteService.AttemptRouteEvidence exactAttempt(
      String provider, String model, String... physicalAttemptIds) {
    return exactAttempt(provider, model, 5, 6, physicalAttemptIds);
  }

  private static RunEffectiveRouteService.AttemptRouteEvidence exactAttempt(
      String provider,
      String model,
      long firstCallIndex,
      long lastCallIndex,
      String... physicalAttemptIds) {
    return new RunEffectiveRouteService.AttemptRouteEvidence(
        new EffectiveRouteProjection(provider, model, EffectiveRouteProjection.Attribution.LEDGER),
        List.of(physicalAttemptIds),
        firstCallIndex,
        lastCallIndex);
  }

  private static ValidationReport greenReport() {
    return ValidationReport.builder()
        .id("report-1")
        .runId(RUN_ID)
        .status("COMPLETE")
        .pipelineResult(PipelineResult.PASSED)
        .workspaceGeneration(GENERATION)
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
}
