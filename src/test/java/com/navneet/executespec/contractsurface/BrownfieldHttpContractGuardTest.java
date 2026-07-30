package com.navneet.executespec.contractsurface;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contracts.gate.PlanContractGateReport;
import com.navneet.executespec.contracts.gate.PlanContractGateReportRepository;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.BreakingChangeAuthorization;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.ContractCompatibilityException;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor.RouteKey;
import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.repository.GateRepository;
import com.navneet.executespec.integration.DecisionEvidenceV1;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrownfieldHttpContractGuardTest {

  @TempDir Path workspace;

  private BrownfieldHttpContractGuard guard;
  private PipelineRunRepository runRepository;
  private GateRepository gateRepository;
  private PlanContractGateReportRepository contractReportRepository;

  @BeforeEach
  void setUp() {
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.getParent().toString());
    runRepository = mock(PipelineRunRepository.class);
    gateRepository = mock(GateRepository.class);
    contractReportRepository = mock(PlanContractGateReportRepository.class);
    guard =
        new BrownfieldHttpContractGuard(
            properties,
            new SpringHttpSurfaceExtractor(),
            new ObjectMapper(),
            runRepository,
            gateRepository,
            contractReportRepository);
  }

  @Test
  void unchangedSurfacePassesAndBaselineCannotBeRewritten() throws Exception {
    bindRun("run-1");
    writeController("/casino-game");
    var baseline = guard.captureBaselineIfAbsent("run-1", workspace);
    String firstDigest = baseline.snapshot().digest();

    writeController("/replacement");
    var secondCapture = guard.captureBaselineIfAbsent("run-1", workspace);

    assertThat(secondCapture.snapshot().digest()).isEqualTo(firstDigest);
    assertThatThrownBy(
            () -> guard.assertCompatible("run-1", workspace, BreakingChangeAuthorization.none()))
        .isInstanceOf(ContractCompatibilityException.class)
        .extracting(error -> ((ContractCompatibilityException) error).code())
        .isEqualTo("BROWNFIELD_HTTP_CONTRACT_REMOVED");
  }

  @Test
  void preserveExistingEndpointResolvesToExactImmutableRouteConstraint() throws Exception {
    bindRun("run-preserve");
    writeController("/casino-game");

    var baseline = guard.captureBaselineIfAbsent("run-preserve", workspace);

    assertThat(baseline.snapshot().keys())
        .containsExactly(new RouteKey("POST", "/simulation/casino-game"));
    assertThat(baseline.snapshot().digest()).matches("[0-9a-f]{64}");
    assertThat(baseline.runId()).isEqualTo("run-preserve");
  }

  @Test
  void preservingOriginalAsAliasPasses() throws Exception {
    bindRun("run-2");
    writeController("/casino-game");
    guard.captureBaselineIfAbsent("run-2", workspace);

    write(
        """
        @RequestMapping("/simulation")
        class CasinoController {
          @PostMapping({"/casino-game", "/api/casino-game"})
          void play() {}
        }
        """);

    var report = guard.assertCompatible("run-2", workspace, BreakingChangeAuthorization.none());

    assertThat(report.compatible()).isTrue();
    assertThat(report.missing()).isEmpty();
    assertThat(report.added())
        .extracting(route -> route.method() + " " + route.path())
        .containsExactly("POST /simulation/api/casino-game");
  }

  @Test
  void readOnlyInspectionDoesNotRewriteDurableComparisonEvidence() throws Exception {
    String runId = workspace.getFileName().toString();
    bindRun(runId);
    writeController("/casino-game");
    guard.captureBaselineIfAbsent(runId, workspace);
    clearInvocations(runRepository);

    var report = guard.inspectCompatibility(runId);

    assertThat(report.compatible()).isTrue();
    verify(runRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void replacementFailsRegardlessOfSelfAuthoredTests() throws Exception {
    bindRun("run-3");
    writeController("/casino-game");
    guard.captureBaselineIfAbsent("run-3", workspace);
    writeController("/api/casino-game");
    Path test = workspace.resolve("src/test/java/example/CasinoControllerTest.java");
    Files.createDirectories(test.getParent());
    Files.writeString(
        test,
        """
        class CasinoControllerTest {
          // A worker-authored green test for /simulation/api/casino-game is not contract evidence.
        }
        """);

    assertThatThrownBy(
            () -> guard.assertCompatible("run-3", workspace, BreakingChangeAuthorization.none()))
        .isInstanceOf(ContractCompatibilityException.class)
        .hasMessageContaining("POST /simulation/casino-game");
  }

  @Test
  void removalRequiresExactBreakingAcknowledgementAndDigest() throws Exception {
    bindRun("run-4");
    writeController("/casino-game");
    guard.captureBaselineIfAbsent("run-4", workspace);
    writeController("/api/casino-game");

    assertThatThrownBy(
            () ->
                guard.assertCompatible(
                    "run-4",
                    workspace,
                    BreakingChangeAuthorization.acknowledged("breaking", "a".repeat(64))))
        .isInstanceOf(ContractCompatibilityException.class);

    var report =
        guard.assertCompatible(
            "run-4",
            workspace,
            BreakingChangeAuthorization.acknowledged(
                "BREAKING",
                "a".repeat(64),
                java.util.Set.of(new RouteKey("POST", "/simulation/casino-game"))));

    assertThat(report.compatible()).isTrue();
    assertThat(report.breakingRemovalAuthorized()).isTrue();
    assertThat(report.missing()).hasSize(1);
  }

  @Test
  void publicGuardAllowsRemovalOnlyFromExactDurableApprovalReceipt() throws Exception {
    String runId = workspace.getFileName().toString();
    bindRun(runId);
    writeController("/casino-game");
    guard.captureBaselineIfAbsent(runId, workspace);
    writeController("/api/casino-game");
    bindBreakingApproval(runId, "BREAKING");

    var report = guard.assertCompatible(runId);

    assertThat(report.compatible()).isTrue();
    assertThat(report.breakingRemovalAuthorized()).isTrue();
  }

  @Test
  void acknowledgementForUnrelatedContractCannotAuthorizeEndpointRemoval() throws Exception {
    String runId = workspace.getFileName().toString();
    bindRun(runId);
    writeController("/casino-game");
    guard.captureBaselineIfAbsent(runId, workspace);
    writeController("/api/casino-game");
    bindBreakingApproval(
        runId,
        "BREAKING",
        """
        {"schemaVersion":1,"brownfieldHttpRouteRemovals":[
          {"method":"DELETE","path":"/simulation/unrelated"}
        ]}
        """);

    assertThatThrownBy(() -> guard.assertCompatible(runId))
        .isInstanceOf(ContractCompatibilityException.class)
        .extracting(error -> ((ContractCompatibilityException) error).code())
        .isEqualTo("BROWNFIELD_HTTP_CONTRACT_REMOVED");
  }

  @Test
  void genericBreakingReportCannotAuthorizeEndpointRemoval() throws Exception {
    String runId = workspace.getFileName().toString();
    bindRun(runId);
    writeController("/casino-game");
    guard.captureBaselineIfAbsent(runId, workspace);
    writeController("/api/casino-game");
    bindBreakingApproval(runId, "BREAKING", "{\"breaking\":true}");

    assertThatThrownBy(() -> guard.assertCompatible(runId))
        .isInstanceOf(ContractCompatibilityException.class)
        .extracting(error -> ((ContractCompatibilityException) error).code())
        .isEqualTo("BROWNFIELD_HTTP_CONTRACT_REMOVED");
  }

  @Test
  void publicGuardFailsClosedWhenReportAcknowledgementDiffersFromReceipt() throws Exception {
    String runId = workspace.getFileName().toString();
    bindRun(runId);
    writeController("/casino-game");
    guard.captureBaselineIfAbsent(runId, workspace);
    writeController("/api/casino-game");
    bindBreakingApproval(runId, "breaking");

    assertThatThrownBy(() -> guard.assertCompatible(runId))
        .isInstanceOf(ContractCompatibilityException.class)
        .extracting(error -> ((ContractCompatibilityException) error).code())
        .isEqualTo("BROWNFIELD_HTTP_CONTRACT_REMOVED");
  }

  @Test
  void publicGuardFailsClosedWhenReceiptDigestDoesNotMatchPersistedBytes() throws Exception {
    String runId = workspace.getFileName().toString();
    bindRun(runId);
    writeController("/casino-game");
    guard.captureBaselineIfAbsent(runId, workspace);
    writeController("/api/casino-game");
    GateRecord gate = bindBreakingApproval(runId, "BREAKING");
    gate.setApprovalEvidenceDigest("f".repeat(64));

    assertThatThrownBy(() -> guard.assertCompatible(runId))
        .isInstanceOf(ContractCompatibilityException.class)
        .extracting(error -> ((ContractCompatibilityException) error).code())
        .isEqualTo("BROWNFIELD_HTTP_CONTRACT_REMOVED");
  }

  @Test
  void baselineIsBoundToRunIdentity() throws Exception {
    PipelineRun run = bindRun("original-run");
    when(runRepository.findById("different-run")).thenReturn(java.util.Optional.of(run));
    writeController("/casino-game");
    guard.captureBaselineIfAbsent("original-run", workspace);

    assertThatThrownBy(
            () ->
                guard.assertCompatible(
                    "different-run", workspace, BreakingChangeAuthorization.none()))
        .isInstanceOf(ContractCompatibilityException.class)
        .extracting(error -> ((ContractCompatibilityException) error).code())
        .isEqualTo("BROWNFIELD_BASELINE_IDENTITY_MISMATCH");
  }

  @Test
  void durableBaselineSurvivesLossOfLocalAgentContext() throws Exception {
    PipelineRun run = bindRun("restored-run");
    writeController("/casino-game");
    guard.captureBaselineIfAbsent("restored-run", workspace);
    Files.delete(workspace.resolve(".agent-context/contracts/spring-http-surface-v1.json"));

    var report =
        guard.assertCompatible("restored-run", workspace, BreakingChangeAuthorization.none());

    assertThat(report.compatible()).isTrue();
    assertThat(run.getBrownfieldHttpBaselineDigest()).matches("[0-9a-f]{64}");
    assertThat(run.getBrownfieldHttpCompatibilityStatus()).isEqualTo("COMPATIBLE");
  }

  private void writeController(String methodPath) throws Exception {
    write(
        """
        @RequestMapping("/simulation")
        class CasinoController {
          @PostMapping("%s")
          void play() {}
        }
        """
            .formatted(methodPath));
  }

  private PipelineRun bindRun(String runId) {
    PipelineRun run = PipelineRun.builder().runId(runId).build();
    when(runRepository.findById(runId)).thenReturn(java.util.Optional.of(run));
    return run;
  }

  private GateRecord bindBreakingApproval(String runId, String persistedAcknowledgement) {
    return bindBreakingApproval(
        runId,
        persistedAcknowledgement,
        """
        {"schemaVersion":1,"brownfieldHttpRouteRemovals":[
          {"method":"POST","path":"/simulation/casino-game"}
        ]}
        """);
  }

  private GateRecord bindBreakingApproval(
      String runId, String persistedAcknowledgement, String reportJson) {
    String reportDigest = DecisionEvidenceV1.sha256(reportJson);
    DecisionEvidenceV1 evidence = breakingApprovalEvidence(runId, reportDigest);
    String evidenceJson = evidence.canonicalJson();
    GateRecord gate = GateRecord.pending("gate:plan:" + runId, runId, "PLAN");
    gate.setStatus("APPROVED");
    gate.setDecisionKind("APPROVE");
    gate.setAdmissionContractVersion(DecisionEvidenceV1.SCHEMA_VERSION);
    gate.setResolvedAt(java.time.Instant.parse("2026-07-28T00:00:02Z"));
    gate.setAdmittedAt(gate.getResolvedAt());
    gate.setResolutionSource("PLAN_APPROVAL_API");
    gate.setDecisionRequestFingerprint("b".repeat(64));
    gate.setResolvedByUserId("user-1");
    gate.setResolvedByRole("USER");
    gate.setResolvedAuthorizationSource("RUN_OWNER");
    gate.setContractGateReportDigest(reportDigest);
    gate.setApprovalEvidenceJson(evidenceJson);
    gate.setApprovalEvidenceDigest(DecisionEvidenceV1.sha256(evidenceJson));
    when(gateRepository.findById("gate:plan:" + runId)).thenReturn(java.util.Optional.of(gate));

    PlanContractGateReport report =
        PlanContractGateReport.builder()
            .runId(runId)
            .reportJson(reportJson)
            .reportDigest(reportDigest)
            .requiresTypedAck(true)
            .contractCount(1)
            .createdAt(java.time.Instant.parse("2026-07-28T00:00:00Z"))
            .acknowledgedAt(java.time.Instant.parse("2026-07-28T00:00:01Z"))
            .acknowledgedByUserId("user-1")
            .acknowledgementText(persistedAcknowledgement)
            .build();
    when(contractReportRepository.findById(runId)).thenReturn(java.util.Optional.of(report));
    return gate;
  }

  private static DecisionEvidenceV1 breakingApprovalEvidence(String runId, String reportDigest) {
    String sha = "a".repeat(64);
    return new DecisionEvidenceV1(
        1,
        DecisionEvidenceV1.POLICY_CONTRACT,
        "APPROVE",
        "2026-07-28T00:00:02Z",
        runId,
        "gate:plan:" + runId,
        "PLAN_APPROVAL_API",
        null,
        "HUMAN",
        "user-1",
        "USER",
        null,
        "USER",
        "RUN_OWNER",
        null,
        sha,
        null,
        null,
        "b".repeat(64),
        1,
        "c".repeat(64),
        "LEGACY",
        "UNBOUND",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "HISTORICAL",
        null,
        null,
        null,
        null,
        null,
        "REALTIME",
        "4.500000",
        null,
        3,
        3,
        true,
        "owner-1",
        "org-1",
        "anthropic",
        "SYSTEM",
        "SYSTEM",
        "SYSTEM_PLATFORM_KEY",
        "org-1",
        false,
        "ALLOWED",
        "MANAGED",
        2000L,
        1900L,
        100L,
        "25.000000",
        "23.500000",
        "ALLOWED",
        null,
        "4.500000",
        "NO_THRESHOLD",
        "ALLOWED",
        null,
        "0.000000",
        "4.500000",
        "NO_CAP",
        reportDigest,
        true,
        "2026-07-28T00:00:01Z",
        "user-1",
        "BREAKING",
        "ACKNOWLEDGED");
  }

  private void write(String source) throws Exception {
    Path path = workspace.resolve("src/main/java/example/CasinoController.java");
    Files.createDirectories(path.getParent());
    Files.writeString(path, source);
  }
}
