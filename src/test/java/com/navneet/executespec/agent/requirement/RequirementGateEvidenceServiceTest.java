package com.navneet.executespec.agent.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.RequirementSpec;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;
import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.repository.GateRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequirementGateEvidenceServiceTest {
  private static final String RUN_ID = "run-evidence-1";

  private final PipelineRunRepository runs = mock(PipelineRunRepository.class);
  private final GateRepository gates = mock(GateRepository.class);
  private final RunRequirementContractRepository contracts =
      mock(RunRequirementContractRepository.class);
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final RequirementIrCompiler compiler = new RequirementIrCompiler();
  private final RequirementIrCodec codec = new RequirementIrCodec(mapper);
  private final RequirementIrApprovalCompatibilityRegistry registry =
      new RequirementIrApprovalCompatibilityRegistry(mapper);
  private final RequirementGateEvidenceService service =
      new RequirementGateEvidenceService(runs, gates, contracts, registry);

  @BeforeEach
  void setUp() {
    when(runs.findById(RUN_ID)).thenReturn(Optional.of(run("Requirement", plan("Requirement"))));
  }

  @Test
  void noPlanGateReturnsEmptyInsteadOfInventingEvidence() {
    when(gates.findFirstByRunIdAndGateType(RUN_ID, "PLAN")).thenReturn(Optional.empty());

    assertThat(service.findForRun(RUN_ID)).isEmpty();
  }

  @Test
  void awaitingApprovalWithoutPlanGateFailsClosed() {
    when(runs.findById(RUN_ID))
        .thenReturn(
            Optional.of(
                PipelineRun.builder()
                    .runId(RUN_ID)
                    .status("AWAITING_APPROVAL")
                    .userRequest("Requirement")
                    .planJson(plan("Requirement"))
                    .build()));
    when(gates.findFirstByRunIdAndGateType(RUN_ID, "PLAN")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.findForRun(RUN_ID))
        .isInstanceOf(RequirementGateEvidenceService.RequirementGateEvidenceException.class)
        .hasMessageContaining("AWAITING_APPROVAL_GATE_MISSING");
  }

  @Test
  void historicalNullBindingFieldsNormalizeToLegacyUnboundEvidence() {
    GateRecord gate = new GateRecord();
    gate.setGateKey("gate:plan:" + RUN_ID);
    gate.setRunId(RUN_ID);
    gate.setGateType("PLAN");
    gate.setStatus("PENDING");
    when(gates.findFirstByRunIdAndGateType(RUN_ID, "PLAN")).thenReturn(Optional.of(gate));

    RequirementGateEvidenceDto evidence = service.findForRun(RUN_ID).orElseThrow();

    assertThat(evidence.bindingMode()).isEqualTo("LEGACY");
    assertThat(evidence.bindingStatus()).isEqualTo("UNBOUND");
    assertThat(evidence.requirements()).isEmpty();
  }

  @Test
  void readyGateProjectsFrozenRequirementsAcceptanceAndBindings() throws Exception {
    String source = "Return the order response.";
    String planJson = plan(source);
    RunRequirementContract contract = contract(source, planJson, readyPlan(source));
    GateRecord gate = gate(contract, "ENFORCED", "BOUND", "NONE");
    arrange(source, planJson, gate, contract);

    RequirementGateEvidenceDto evidence = service.findForRun(RUN_ID).orElseThrow();

    assertThat(evidence.enforced()).isTrue();
    assertThat(evidence.approvalReady()).isTrue();
    assertThat(evidence.completeness()).isEqualTo("READY");
    assertThat(evidence.requirementCount()).isEqualTo(evidence.requirements().size());
    assertThat(evidence.acceptanceCriterionCount()).isEqualTo(evidence.acceptanceCriteria().size());
    assertThat(evidence.taskBindingCount()).isEqualTo(evidence.taskBindings().size());
  }

  @Test
  void readyArtifactIsNotApprovalReadyOutsideTheLivePendingDecisionState() throws Exception {
    String source = "Return the order response.";
    String planJson = plan(source);
    RunRequirementContract contract = contract(source, planJson, readyPlan(source));
    GateRecord gate = gate(contract, "ENFORCED", "BOUND", "NONE");
    gate.setStatus("APPROVED");
    when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(source, planJson, "APPROVAL_RESUMING")));
    when(gates.findFirstByRunIdAndGateType(RUN_ID, "PLAN")).thenReturn(Optional.of(gate));
    when(contracts.findById(contract.getId())).thenReturn(Optional.of(contract));

    assertThat(service.findForRun(RUN_ID).orElseThrow().approvalReady()).isFalse();
  }

  @Test
  void blockedGateProjectsMissingErrorUiAndAuthorizationAcceptanceIssues() throws Exception {
    String error = "Support failure behavior";
    String ui = "Provide interface behavior";
    String auth = "Respect access policy";
    String source = String.join("\n", error, ui, auth);
    RequirementIrDocument document = missingAcceptanceDocument(source, error, ui, auth);
    Plan plan =
        Plan.builder()
            .requirements(List.of())
            .tasks(
                List.of(
                    task("T-ERR", error, "R-ERR"),
                    task("T-UI", ui, "R-UI"),
                    task("T-AUTH", auth, "R-AUTH")))
            .build();
    String planJson = mapper.writeValueAsString(plan);
    RunRequirementContract contract = contract(source, planJson, document);
    GateRecord gate = gate(contract, "ENFORCED", "BLOCKED", "APPROVAL_BLOCKED");
    arrange(source, planJson, gate, contract);

    RequirementGateEvidenceDto evidence = service.findForRun(RUN_ID).orElseThrow();

    assertThat(evidence.enforced()).isTrue();
    assertThat(evidence.approvalReady()).isFalse();
    assertThat(evidence.completeness()).isEqualTo("BLOCKED");
    assertThat(evidence.issues())
        .filteredOn(RequirementGateEvidenceDto.Issue::blocking)
        .filteredOn(issue -> "MISSING_ACCEPTANCE".equals(issue.code()))
        .extracting(RequirementGateEvidenceDto.Issue::requirementIds)
        .anySatisfy(ids -> assertThat(ids).contains("R-ERR"))
        .anySatisfy(ids -> assertThat(ids).contains("R-UI"))
        .anySatisfy(ids -> assertThat(ids).contains("R-AUTH"));
    assertThat(evidence.issues())
        .allSatisfy(
            issue -> {
              assertThat(issue.message()).isNotBlank();
              assertThat(issue.requirementIds()).isNotNull();
            });
  }

  @Test
  void crossRowIdentityCorruptionFailsClosed() throws Exception {
    String source = "Return the order response.";
    String planJson = plan(source);
    RunRequirementContract contract = contract(source, planJson, readyPlan(source));
    GateRecord gate = gate(contract, "ENFORCED", "BOUND", "NONE");
    gate.setBindingArtifactDigest("f".repeat(64));
    arrange(source, planJson, gate, contract);

    assertThatThrownBy(() -> service.findForRun(RUN_ID))
        .isInstanceOf(RequirementGateEvidenceService.RequirementGateEvidenceException.class)
        .hasMessageContaining("GATE_CONTRACT_RUN_IDENTITY_MISMATCH");
  }

  private void arrange(
      String source, String planJson, GateRecord gate, RunRequirementContract contract) {
    when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(source, planJson, "AWAITING_APPROVAL")));
    when(gates.findFirstByRunIdAndGateType(RUN_ID, "PLAN")).thenReturn(Optional.of(gate));
    when(contracts.findById(contract.getId())).thenReturn(Optional.of(contract));
  }

  private RunRequirementContract contract(String source, String planJson, Plan plan) {
    return contract(source, planJson, compiler.compile(source, plan));
  }

  private RunRequirementContract contract(
      String source, String planJson, RequirementIrDocument document) {
    String canonical = codec.writeCanonical(document);
    String planFingerprint = ExactPlanFingerprint.sha256(planJson);
    String artifactDigest = RequirementContractDigests.artifactDigest(canonical);
    return RunRequirementContract.builder()
        .id(UUID.randomUUID())
        .runId(RUN_ID)
        .schemaVersion(document.schemaVersion())
        .compilerFingerprint(document.compilerFingerprint())
        .sourceSha256(document.sourceSha256())
        .exactPlanFingerprint(planFingerprint)
        .canonicalArtifactJson(canonical)
        .artifactDigest(artifactDigest)
        .boundDigest(
            RequirementContractDigests.boundDigest(
                planFingerprint, document.sourceSha256(), artifactDigest))
        .completeness(document.completeness())
        .requirementCount(document.requirements().size())
        .acceptanceCriterionCount(document.acceptanceCriteria().size())
        .taskBindingCount(document.taskBindings().size())
        .issueCount(document.issues().size())
        .blockingIssueCount(
            Math.toIntExact(document.issues().stream().filter(i -> i.blocking()).count()))
        .build();
  }

  private static RequirementIrDocument missingAcceptanceDocument(
      String source, String error, String ui, String auth) {
    var errorSpan =
        new RequirementIrDocument.SourceSpan(0, error.length(), 1, 1, 1, error.length() + 1);
    int uiStart = error.length() + 1;
    var uiSpan =
        new RequirementIrDocument.SourceSpan(
            uiStart, uiStart + ui.length(), 2, 1, 2, ui.length() + 1);
    int authStart = uiStart + ui.length() + 1;
    var authSpan =
        new RequirementIrDocument.SourceSpan(
            authStart, authStart + auth.length(), 3, 1, 3, auth.length() + 1);
    return new RequirementIrDocument(
        RequirementIrDocument.CURRENT_SCHEMA_VERSION,
        RequirementIrCompiler.COMPILER_FINGERPRINT,
        RequirementIrValidator.sha256(source),
        List.of(
            requirement(
                "R-ERR", error, RequirementIrDocument.RequirementKind.ERROR, errorSpan, "T-ERR"),
            requirement("R-UI", ui, RequirementIrDocument.RequirementKind.UI, uiSpan, "T-UI"),
            requirement(
                "R-AUTH",
                auth,
                RequirementIrDocument.RequirementKind.SECURITY,
                authSpan,
                "T-AUTH")),
        List.of(),
        List.of(
            new RequirementIrDocument.TaskBinding("T-ERR", List.of("R-ERR")),
            new RequirementIrDocument.TaskBinding("T-UI", List.of("R-UI")),
            new RequirementIrDocument.TaskBinding("T-AUTH", List.of("R-AUTH"))),
        List.of(missingAcceptance("R-ERR"), missingAcceptance("R-UI"), missingAcceptance("R-AUTH")),
        RequirementIrDocument.Completeness.BLOCKED);
  }

  private static RequirementIrDocument.RequirementNode requirement(
      String id,
      String text,
      RequirementIrDocument.RequirementKind kind,
      RequirementIrDocument.SourceSpan span,
      String taskId) {
    return new RequirementIrDocument.RequirementNode(
        id,
        text,
        kind,
        RequirementIrDocument.Origin.USER_STATED,
        RequirementIrDocument.RequirementState.EXPLICIT,
        span,
        null,
        null,
        List.of(),
        List.of(taskId));
  }

  private static RequirementIrDocument.RequirementIssue missingAcceptance(String requirementId) {
    return new RequirementIrDocument.RequirementIssue(
        RequirementIrDocument.IssueCode.MISSING_ACCEPTANCE,
        true,
        "requirement needs an observable acceptance criterion",
        List.of(requirementId),
        List.of());
  }

  private static GateRecord gate(
      RunRequirementContract contract, String mode, String status, String reason) {
    GateRecord gate = new GateRecord();
    gate.setGateKey("gate:plan:" + RUN_ID);
    gate.setRunId(RUN_ID);
    gate.setGateType("PLAN");
    gate.setStatus("PENDING");
    gate.setBindingMode(mode);
    gate.setBindingStatus(status);
    gate.setBindingReason(reason);
    gate.setRequirementContractId(contract.getId());
    gate.setBindingSchemaVersion(contract.getSchemaVersion());
    gate.setBindingCompilerFingerprint(contract.getCompilerFingerprint());
    gate.setBindingSourceSha256(contract.getSourceSha256());
    gate.setBindingExactPlanFingerprint(contract.getExactPlanFingerprint());
    gate.setBindingArtifactDigest(contract.getArtifactDigest());
    gate.setBindingBoundDigest(contract.getBoundDigest());
    gate.setBindingBoundAt(Instant.now());
    return gate;
  }

  private static PipelineRun run(String source, String planJson) {
    return run(source, planJson, "PLANNING");
  }

  private static PipelineRun run(String source, String planJson, String status) {
    return PipelineRun.builder()
        .runId(RUN_ID)
        .status(status)
        .userRequest(source)
        .planJson(planJson)
        .build();
  }

  private String plan(String source) {
    try {
      return mapper.writeValueAsString(readyPlan(source));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static Plan readyPlan(String source) {
    return Plan.builder()
        .requirements(
            List.of(new RequirementSpec("R1", source, RequirementSpec.RequirementKind.FUNCTIONAL)))
        .tasks(List.of(task("T1", source, "R1")))
        .build();
  }

  private static PlannedTask task(String id, String text, String requirementId) {
    return PlannedTask.builder()
        .id(id)
        .title(text)
        .description(text)
        .meetsRequirements(List.of(requirementId))
        .build();
  }
}
