package com.navneet.executespec.agent.requirement;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;
import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.repository.GateRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Read-only, fail-closed RequirementIR evidence projection for an authorized run. */
@Service
@RequiredArgsConstructor
public class RequirementGateEvidenceService {
  private final PipelineRunRepository runRepository;
  private final GateRepository gateRepository;
  private final RunRequirementContractRepository contractRepository;
  private final RequirementIrApprovalCompatibilityRegistry compatibilityRegistry;

  @Transactional(readOnly = true)
  public Optional<RequirementGateEvidenceDto> findForRun(String runId) {
    PipelineRun run = runRepository.findById(runId).orElseThrow(() -> corrupted("RUN_MISSING"));
    GateRecord gate = gateRepository.findFirstByRunIdAndGateType(runId, "PLAN").orElse(null);
    if (gate == null) {
      if ("AWAITING_APPROVAL".equals(run.getStatus())) {
        throw corrupted("AWAITING_APPROVAL_GATE_MISSING");
      }
      return Optional.empty();
    }
    requireGateIdentity(run, gate);

    boolean enforced = "ENFORCED".equals(gate.getBindingMode());
    if (gate.getRequirementContractId() == null) {
      requireUnboundShape(gate, enforced);
      return Optional.of(unbound(run, gate, enforced));
    }

    RunRequirementContract contract =
        contractRepository
            .findById(gate.getRequirementContractId())
            .orElseThrow(() -> corrupted("CONTRACT_MISSING"));
    requireBoundIdentity(run, gate, contract);
    RequirementIrApprovalCompatibilityRegistry.FrozenProjection projection;
    try {
      projection = compatibilityRegistry.projectFrozenVersion(contract, run.getUserRequest());
    } catch (RuntimeException unsupportedOrCorrupt) {
      throw corrupted("UNSUPPORTED_OR_CORRUPT_REQUIREMENT_IR", unsupportedOrCorrupt);
    }
    requireProjectionSummary(gate, contract, projection);

    boolean approvalReady =
        "PENDING".equals(gate.getStatus())
            && "AWAITING_APPROVAL".equals(run.getStatus())
            && (!enforced || "BOUND".equals(gate.getBindingStatus()))
            && projection.approvalReady()
            && "READY".equals(projection.completeness())
            && contract.getBlockingIssueCount() == 0;
    return Optional.of(toDto(run, gate, contract, projection, enforced, approvalReady));
  }

  private static void requireGateIdentity(PipelineRun run, GateRecord gate) {
    String expectedKey = "gate:plan:" + run.getRunId();
    if (!Objects.equals(gate.getRunId(), run.getRunId())
        || !Objects.equals(gate.getGateKey(), expectedKey)
        || !"PLAN".equals(gate.getGateType())
        || run.getUserRequest() == null
        || run.getPlanJson() == null
        || run.getPlanJson().isBlank()) {
      throw corrupted("GATE_RUN_IDENTITY_MISMATCH");
    }
  }

  private static void requireUnboundShape(GateRecord gate, boolean enforced) {
    boolean hasBoundIdentity =
        gate.getBindingSchemaVersion() != null
            || gate.getBindingCompilerFingerprint() != null
            || gate.getBindingSourceSha256() != null
            || gate.getBindingExactPlanFingerprint() != null
            || gate.getBindingArtifactDigest() != null
            || gate.getBindingBoundDigest() != null
            || gate.getBindingBoundAt() != null;
    if (enforced || hasBoundIdentity || "BOUND".equals(gate.getBindingStatus())) {
      throw corrupted("UNBOUND_GATE_HAS_BOUND_IDENTITY");
    }
  }

  private static void requireBoundIdentity(
      PipelineRun run, GateRecord gate, RunRequirementContract contract) {
    String sourceSha = RequirementIrV1ApprovalValidator.sha256(run.getUserRequest());
    String planSha = ExactPlanFingerprint.sha256(run.getPlanJson());
    boolean exact =
        Objects.equals(contract.getRunId(), run.getRunId())
            && Objects.equals(gate.getBindingSchemaVersion(), contract.getSchemaVersion())
            && Objects.equals(
                gate.getBindingCompilerFingerprint(), contract.getCompilerFingerprint())
            && Objects.equals(gate.getBindingSourceSha256(), contract.getSourceSha256())
            && Objects.equals(
                gate.getBindingExactPlanFingerprint(), contract.getExactPlanFingerprint())
            && Objects.equals(gate.getBindingArtifactDigest(), contract.getArtifactDigest())
            && Objects.equals(gate.getBindingBoundDigest(), contract.getBoundDigest())
            && Objects.equals(sourceSha, contract.getSourceSha256())
            && Objects.equals(planSha, contract.getExactPlanFingerprint())
            && gate.getBindingBoundAt() != null;
    if (!exact) throw corrupted("GATE_CONTRACT_RUN_IDENTITY_MISMATCH");
  }

  private static void requireProjectionSummary(
      GateRecord gate,
      RunRequirementContract contract,
      RequirementIrApprovalCompatibilityRegistry.FrozenProjection projection) {
    boolean exact =
        Objects.equals(projection.schemaVersion(), contract.getSchemaVersion())
            && Objects.equals(projection.compilerFingerprint(), contract.getCompilerFingerprint())
            && Objects.equals(projection.sourceSha256(), contract.getSourceSha256())
            && Objects.equals(projection.completeness(), contract.getCompleteness().name())
            && projection.requirements().size() == contract.getRequirementCount()
            && projection.acceptanceCriteria().size() == contract.getAcceptanceCriterionCount()
            && projection.taskBindings().size() == contract.getTaskBindingCount()
            && projection.persistedIssueCount() == contract.getIssueCount()
            && projection.persistedBlockingIssueCount() == contract.getBlockingIssueCount()
            && ("BOUND".equals(gate.getBindingStatus())
                || "BLOCKED".equals(gate.getBindingStatus()));
    if (!exact) throw corrupted("PROJECTED_REQUIREMENT_IR_SUMMARY_MISMATCH");
  }

  private static RequirementGateEvidenceDto unbound(
      PipelineRun run, GateRecord gate, boolean enforced) {
    boolean approvalReady =
        !enforced
            && "PENDING".equals(gate.getStatus())
            && "AWAITING_APPROVAL".equals(run.getStatus());
    String bindingMode = gate.getBindingMode() == null ? "LEGACY" : gate.getBindingMode();
    String bindingStatus = gate.getBindingStatus() == null ? "UNBOUND" : gate.getBindingStatus();
    return new RequirementGateEvidenceDto(
        run.getRunId(),
        gate.getGateKey(),
        gate.getStatus(),
        bindingMode,
        bindingStatus,
        gate.getBindingReason(),
        enforced,
        approvalReady,
        null,
        null,
        null,
        ExactPlanFingerprint.sha256(run.getPlanJson()),
        null,
        null,
        null,
        0,
        0,
        0,
        0,
        0,
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static RequirementGateEvidenceDto toDto(
      PipelineRun run,
      GateRecord gate,
      RunRequirementContract contract,
      RequirementIrApprovalCompatibilityRegistry.FrozenProjection projection,
      boolean enforced,
      boolean approvalReady) {
    return new RequirementGateEvidenceDto(
        run.getRunId(),
        gate.getGateKey(),
        gate.getStatus(),
        gate.getBindingMode(),
        gate.getBindingStatus(),
        gate.getBindingReason(),
        enforced,
        approvalReady,
        contract.getSchemaVersion(),
        contract.getCompilerFingerprint(),
        contract.getSourceSha256(),
        contract.getExactPlanFingerprint(),
        contract.getArtifactDigest(),
        contract.getBoundDigest(),
        projection.completeness(),
        contract.getRequirementCount(),
        contract.getAcceptanceCriterionCount(),
        contract.getTaskBindingCount(),
        projection.issues().size(),
        Math.toIntExact(projection.issues().stream().filter(i -> i.blocking()).count()),
        projection.requirements().stream()
            .map(
                r ->
                    new RequirementGateEvidenceDto.Requirement(
                        r.id(),
                        r.text(),
                        r.kind(),
                        r.origin(),
                        r.state(),
                        r.inferenceRationale(),
                        r.deferredReason(),
                        r.acceptanceIds(),
                        r.taskIds()))
            .toList(),
        projection.acceptanceCriteria().stream()
            .map(
                a ->
                    new RequirementGateEvidenceDto.AcceptanceCriterion(
                        a.id(), a.requirementIds(), a.kind(), a.evidenceType(), a.expected()))
            .toList(),
        projection.taskBindings().stream()
            .map(t -> new RequirementGateEvidenceDto.TaskBinding(t.taskId(), t.requirementIds()))
            .toList(),
        projection.issues().stream()
            .map(
                i ->
                    new RequirementGateEvidenceDto.Issue(
                        i.code(), i.blocking(), i.message(), i.requirementIds()))
            .toList());
  }

  private static RequirementGateEvidenceException corrupted(String reason) {
    return new RequirementGateEvidenceException(reason, null);
  }

  private static RequirementGateEvidenceException corrupted(String reason, Throwable cause) {
    return new RequirementGateEvidenceException(reason, cause);
  }

  public static final class RequirementGateEvidenceException extends IllegalStateException {
    public RequirementGateEvidenceException(String reason, Throwable cause) {
      super("RequirementIR gate evidence is unavailable: " + reason, cause);
    }
  }
}
