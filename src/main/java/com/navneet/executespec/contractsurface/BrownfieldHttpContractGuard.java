package com.navneet.executespec.contractsurface;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contracts.gate.PlanContractGateReport;
import com.navneet.executespec.contracts.gate.PlanContractGateReportRepository;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor.RouteKey;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor.SurfaceSnapshot;
import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.repository.GateRepository;
import com.navneet.executespec.integration.DecisionEvidenceV1;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Immutable brownfield HTTP compatibility boundary.
 *
 * <p>The baseline lives under {@code .agent-context}, which is excluded from every publication.
 * Once captured it is never rewritten. Every subsequent comparison therefore evaluates the
 * workspace against the exact admitted surface rather than against worker-authored tests.
 */
@Service
@RequiredArgsConstructor
public class BrownfieldHttpContractGuard {

  static final Path SNAPSHOT_RELATIVE =
      Path.of(".agent-context", "contracts", "spring-http-surface-v1.json");

  private final AgentProperties properties;
  private final SpringHttpSurfaceExtractor extractor;
  private final ObjectMapper objectMapper;
  private final PipelineRunRepository runRepository;
  private final GateRepository gateRepository;
  private final PlanContractGateReportRepository contractReportRepository;

  public BaselineEnvelope captureBaselineIfAbsent(String runId, Path workspaceRoot) {
    requireRunAndRoot(runId, workspaceRoot);
    Path snapshotPath = workspaceRoot.resolve(SNAPSHOT_RELATIVE);
    PipelineRun run = requireRun(runId);
    if (run.getBrownfieldHttpBaselineDigest() != null) return readDurableBaseline(run);
    if (Files.exists(snapshotPath)) {
      BaselineEnvelope existing = read(snapshotPath);
      bindDurably(run, existing);
      return existing;
    }
    SurfaceSnapshot snapshot = extractor.extract(workspaceRoot);
    BaselineEnvelope envelope = new BaselineEnvelope(1, runId, snapshot);
    try {
      Files.createDirectories(snapshotPath.getParent());
      Path temporary = snapshotPath.resolveSibling(snapshotPath.getFileName() + ".tmp");
      objectMapper.writeValue(temporary.toFile(), envelope);
      try {
        Files.move(
            temporary,
            snapshotPath,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, snapshotPath, StandardCopyOption.REPLACE_EXISTING);
      }
      bindDurably(run, envelope);
      return envelope;
    } catch (IOException e) {
      throw new ContractCompatibilityException(
          "BROWNFIELD_BASELINE_PERSIST_FAILED", "Could not persist HTTP contract baseline", e);
    }
  }

  /** Capture against the canonical run workspace configured for this deployment. */
  public BaselineEnvelope captureBaselineIfAbsent(String runId) {
    return captureBaselineIfAbsent(runId, runWorkspace(runId));
  }

  CompatibilityReport compare(
      String runId, Path workspaceRoot, BreakingChangeAuthorization authorization) {
    requireRunAndRoot(runId, workspaceRoot);
    PipelineRun run = requireRun(runId);
    BaselineEnvelope baseline = readDurableBaseline(run);
    if (!runId.equals(baseline.runId())) {
      throw new ContractCompatibilityException(
          "BROWNFIELD_BASELINE_IDENTITY_MISMATCH",
          "HTTP contract baseline belongs to a different run");
    }
    SurfaceSnapshot current = extractor.extract(workspaceRoot);
    Set<RouteKey> currentKeys = current.keys();
    List<RouteKey> missing =
        baseline.snapshot().keys().stream()
            .filter(route -> !currentKeys.contains(route))
            .sorted()
            .toList();
    List<RouteKey> added =
        currentKeys.stream()
            .filter(route -> !baseline.snapshot().keys().contains(route))
            .sorted()
            .toList();
    boolean authorized =
        !missing.isEmpty() && authorization != null && authorization.allowsRemoval();
    CompatibilityReport report =
        new CompatibilityReport(
            baseline.snapshot().digest(),
            current.digest(),
            missing,
            added,
            missing.isEmpty() || authorized,
            authorized);
    persistComparison(run, report);
    return report;
  }

  public CompatibilityReport assertCompatible(String runId) {
    return assertCompatible(
        runId, runWorkspace(runId), resolveDurableBreakingChangeAuthorization(runId));
  }

  public boolean hasBaseline(String runId) {
    return runRepository
        .findById(runId)
        .map(run -> run.getBrownfieldHttpBaselineDigest() != null)
        .orElse(false);
  }

  CompatibilityReport assertCompatible(
      String runId, Path workspaceRoot, BreakingChangeAuthorization authorization) {
    CompatibilityReport report = compare(runId, workspaceRoot, authorization);
    if (!report.compatible()) {
      String removed =
          report.missing().stream()
              .sorted(Comparator.naturalOrder())
              .map(route -> route.method() + " " + route.path())
              .reduce((left, right) -> left + ", " + right)
              .orElse("(unknown)");
      throw new ContractCompatibilityException(
          "BROWNFIELD_HTTP_CONTRACT_REMOVED",
          "Publication blocked because existing HTTP contracts were removed: " + removed);
    }
    return report;
  }

  /**
   * Resolves breaking-change authority exclusively from the canonical plan-gate receipt and its
   * bound contract report. Any absent, historical, malformed, mismatched, or unreadable evidence
   * fails closed.
   */
  BreakingChangeAuthorization resolveDurableBreakingChangeAuthorization(String runId) {
    if (runId == null || runId.isBlank()) return BreakingChangeAuthorization.none();
    try {
      GateRecord gate = gateRepository.findById("gate:plan:".concat(runId)).orElse(null);
      if (gate == null
          || !"gate:plan:".concat(runId).equals(gate.getGateKey())
          || !runId.equals(gate.getRunId())
          || !"PLAN".equals(gate.getGateType())
          || !"APPROVED".equals(gate.getStatus())
          || !"APPROVE".equals(gate.getDecisionKind())
          || !Integer.valueOf(DecisionEvidenceV1.SCHEMA_VERSION)
              .equals(gate.getAdmissionContractVersion())
          || gate.getResolvedAt() == null
          || gate.getAdmittedAt() == null
          || gate.getApprovalEvidenceJson() == null
          || gate.getApprovalEvidenceDigest() == null
          || !gate.getApprovalEvidenceDigest()
              .equals(DecisionEvidenceV1.sha256(gate.getApprovalEvidenceJson()))) {
        return BreakingChangeAuthorization.none();
      }

      DecisionEvidenceV1 receipt = DecisionEvidenceV1.parse(gate.getApprovalEvidenceJson());
      if (!"APPROVE".equals(receipt.decision())
          || !runId.equals(receipt.runId())
          || !gate.getGateKey().equals(receipt.gateKey())
          || !gate.getResolvedAt().toString().equals(receipt.decidedAt())
          || !gate.getAdmittedAt().equals(gate.getResolvedAt())
          || !Objects.equals(gate.getResolutionSource(), receipt.resolutionSource())
          || !Objects.equals(
              gate.getDecisionRequestFingerprint(), receipt.decisionRequestFingerprint())
          || !Objects.equals(gate.getResolvedByUserId(), receipt.actorUserId())
          || !Objects.equals(gate.getResolvedByRole(), receipt.effectiveRole())
          || !Objects.equals(gate.getResolvedAuthorizationSource(), receipt.authorizationBasis())
          || !Objects.equals(gate.getResolvedAuthorizationOrgId(), receipt.authorizationOrgId())
          || !Boolean.TRUE.equals(receipt.contractRequiresTypedAck())
          || !"ACKNOWLEDGED".equals(receipt.contractAcknowledgementOutcome())
          || !"BREAKING".equals(receipt.contractAcknowledgementText())
          || receipt.contractGateReportDigest() == null
          || !receipt.contractGateReportDigest().equals(gate.getContractGateReportDigest())) {
        return BreakingChangeAuthorization.none();
      }

      PlanContractGateReport report = contractReportRepository.findById(runId).orElse(null);
      if (report == null
          || !runId.equals(report.getRunId())
          || !report.isRequiresTypedAck()
          || !receipt.contractGateReportDigest().equals(report.getReportDigest())
          || !report.getReportDigest().equals(DecisionEvidenceV1.sha256(report.getReportJson()))
          || report.getAcknowledgedAt() == null
          || !report.getAcknowledgedAt().toString().equals(receipt.contractAcknowledgedAt())
          || !receipt.contractAcknowledgedByUserId().equals(report.getAcknowledgedByUserId())
          || !"BREAKING".equals(report.getAcknowledgementText())
          || !receipt.actorUserId().equals(report.getAcknowledgedByUserId())) {
        return BreakingChangeAuthorization.none();
      }
      return BreakingChangeAuthorization.acknowledged(
          receipt.contractAcknowledgementText(), receipt.contractGateReportDigest());
    } catch (RuntimeException invalidOrUnavailableEvidence) {
      return BreakingChangeAuthorization.none();
    }
  }

  private Path runWorkspace(String runId) {
    return Path.of(properties.getWorkspace())
        .toAbsolutePath()
        .normalize()
        .resolve(runId)
        .normalize();
  }

  private BaselineEnvelope read(Path path) {
    try {
      return objectMapper.readValue(path.toFile(), BaselineEnvelope.class);
    } catch (IOException e) {
      throw new ContractCompatibilityException(
          "BROWNFIELD_BASELINE_INVALID", "HTTP contract baseline is unreadable", e);
    }
  }

  private PipelineRun requireRun(String runId) {
    return runRepository
        .findById(runId)
        .orElseThrow(
            () ->
                new ContractCompatibilityException(
                    "BROWNFIELD_RUN_MISSING",
                    "Run does not exist for HTTP contract evidence binding"));
  }

  private BaselineEnvelope readDurableBaseline(PipelineRun run) {
    if (run.getBrownfieldHttpBaselineDigest() == null
        || run.getBrownfieldHttpBaselineJson() == null
        || run.getBrownfieldHttpBaselineJson().isBlank()) {
      throw new ContractCompatibilityException(
          "BROWNFIELD_BASELINE_MISSING",
          "Durable HTTP contract baseline is missing; publication cannot prove backward"
              + " compatibility");
    }
    try {
      BaselineEnvelope envelope =
          objectMapper.readValue(run.getBrownfieldHttpBaselineJson(), BaselineEnvelope.class);
      if (!run.getBrownfieldHttpBaselineDigest().equals(envelope.snapshot().digest())) {
        throw new ContractCompatibilityException(
            "BROWNFIELD_BASELINE_DIGEST_MISMATCH",
            "Durable HTTP contract baseline digest does not match its canonical routes");
      }
      return envelope;
    } catch (ContractCompatibilityException known) {
      throw known;
    } catch (IOException e) {
      throw new ContractCompatibilityException(
          "BROWNFIELD_BASELINE_INVALID", "Durable HTTP contract baseline is unreadable", e);
    }
  }

  private void bindDurably(PipelineRun run, BaselineEnvelope envelope) {
    try {
      run.setBrownfieldHttpBaselineDigest(envelope.snapshot().digest());
      run.setBrownfieldHttpBaselineJson(objectMapper.writeValueAsString(envelope));
      run.setBrownfieldHttpCurrentDigest(envelope.snapshot().digest());
      run.setBrownfieldHttpCompatibilityStatus("BASELINE");
      runRepository.save(run);
    } catch (IOException e) {
      throw new ContractCompatibilityException(
          "BROWNFIELD_BASELINE_PERSIST_FAILED",
          "Could not bind HTTP contract baseline to the run",
          e);
    }
  }

  private void persistComparison(PipelineRun run, CompatibilityReport report) {
    try {
      run.setBrownfieldHttpCurrentDigest(report.currentDigest());
      run.setBrownfieldHttpCompatibilityStatus(report.compatible() ? "COMPATIBLE" : "BREAKING");
      run.setBrownfieldHttpCompatibilityJson(objectMapper.writeValueAsString(report));
      runRepository.save(run);
    } catch (IOException e) {
      throw new ContractCompatibilityException(
          "BROWNFIELD_RESULT_PERSIST_FAILED",
          "Could not durably persist HTTP compatibility evidence",
          e);
    }
  }

  private static void requireRunAndRoot(String runId, Path workspaceRoot) {
    if (runId == null
        || runId.isBlank()
        || workspaceRoot == null
        || !Files.isDirectory(workspaceRoot)) {
      throw new ContractCompatibilityException(
          "BROWNFIELD_WORKSPACE_INVALID", "A valid run workspace is required");
    }
  }

  public record BaselineEnvelope(int schemaVersion, String runId, SurfaceSnapshot snapshot) {}

  public record CompatibilityReport(
      String baselineDigest,
      String currentDigest,
      List<RouteKey> missing,
      List<RouteKey> added,
      boolean compatible,
      boolean breakingRemovalAuthorized) {}

  /**
   * Explicit seam for the durable plan-gate acknowledgement. A caller must supply both the exact
   * uppercase acknowledgement and its persisted report digest; a boolean alone is insufficient.
   */
  record BreakingChangeAuthorization(String acknowledgement, String reportDigest) {
    public static BreakingChangeAuthorization none() {
      return new BreakingChangeAuthorization(null, null);
    }

    public static BreakingChangeAuthorization acknowledged(
        String acknowledgement, String reportDigest) {
      return new BreakingChangeAuthorization(acknowledgement, reportDigest);
    }

    public boolean allowsRemoval() {
      return "BREAKING".equals(acknowledgement)
          && reportDigest != null
          && reportDigest.matches("(?i)[0-9a-f]{64}");
    }
  }

  public static final class ContractCompatibilityException extends RuntimeException {
    private final String code;

    public ContractCompatibilityException(String code, String message) {
      super(message);
      this.code = code;
    }

    public ContractCompatibilityException(String code, String message, Throwable cause) {
      super(message, cause);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
