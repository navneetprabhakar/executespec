package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.CompatibilityReport;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.ContractCompatibilityException;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor.RouteKey;
import com.navneet.executespec.cost.EffectiveRouteProjection;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublicationAdmissionServiceTest {

  private static final String RUN_ID = "run-publication";
  private static final String TASK_KEY = RUN_ID.substring(0, 8) + "_T1";
  private static final String GENERATION = "a".repeat(64);
  private static final String APPROVAL_ID = "11111111-1111-1111-1111-111111111111";

  private final ValidationReportRepository reports = mock(ValidationReportRepository.class);
  private final AgentTaskRepository tasks = mock(AgentTaskRepository.class);
  private final CorrectionWorkspaceTransaction generations =
      mock(CorrectionWorkspaceTransaction.class);
  private final AgentProperties properties = new AgentProperties();
  private final BrownfieldHttpContractGuard brownfieldGuard =
      mock(BrownfieldHttpContractGuard.class);
  private final RunEffectiveRouteService effectiveRoutes = mock(RunEffectiveRouteService.class);
  private final PublicationAdmissionService service =
      new PublicationAdmissionService(
          reports,
          tasks,
          generations,
          properties,
          new ObjectMapper(),
          brownfieldGuard,
          effectiveRoutes);

  @BeforeEach
  void setUp() {
    properties.setWorkspace("/tmp/executespec-publication-test");
    when(reports.findByRunId(RUN_ID)).thenReturn(Optional.of(greenReport()));
    when(generations.captureGeneration(Path.of("/tmp/executespec-publication-test", RUN_ID)))
        .thenReturn(GENERATION);
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of());
    when(brownfieldGuard.hasBaseline(RUN_ID)).thenReturn(false);
  }

  @Test
  void admitsFreshBuildAndVerifiedTests() {
    assertThat(service.assertEligible(run()).workspaceGeneration()).isEqualTo(GENERATION);
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
    return PipelineRun.builder().runId(RUN_ID).status("COMPLETE").build();
  }

  private static void bindApprovalIdentity(
      AgentTask task, String proposalDigest, boolean postReview) {
    task.setContinuationApprovalId(APPROVAL_ID);
    task.setContinuationApprovalProposalDigest(proposalDigest);
    task.setContinuationApprovalStatus("COMPLETED");
    task.setContinuationApprovalProvider("openrouter");
    task.setContinuationApprovalModel("x-ai/grok-4.20");
    task.setContinuationApprovalAction("CHANGE_PROVIDER");
    task.setContinuationApprovalPostReview(postReview);
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
        .testsPassed(2)
        .testsTotal(2)
        .workflowConfigEvidenceJson(
            """
            {"status":"PASSED","files":[],"reason":"workflow syntax valid"}
            """)
        .build();
  }
}
