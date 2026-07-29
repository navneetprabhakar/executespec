package com.navneet.executespec.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

import com.navneet.executespec.agent.requirement.ExactPlanFingerprint;
import com.navneet.executespec.agent.requirement.RequirementGateBindingService;
import com.navneet.executespec.agent.requirement.RequirementGateBindingService.RequestedMode;
import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.entity.PlanGateResumeOutbox;
import com.navneet.executespec.harness.repository.GateRepository;
import com.navneet.executespec.harness.repository.PlanGateResumeOutboxRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Atomically opens a plan gate from already-persisted exact plan/source bytes. */
@Service
@RequiredArgsConstructor
class PlanGateOpenService {
  private static final String GATE_TYPE = "PLAN_APPROVAL";

  private final PipelineRunRepository runRepository;
  private final GateRepository gateRepository;
  private final PlanGateResumeOutboxRepository outboxRepository;
  private final RequirementGateBindingService bindingService;
  private final PlanGateOpenStore store;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional
  OpenResult open(OpenCommand command) {
    requireCommand(command);
    PipelineRun run =
        runRepository
            .findByIdForPlanGateOpen(command.runId())
            .orElseThrow(() -> new IllegalStateException("Run not found: " + command.runId()));
    String exactPlanJson = run.getPlanJson();
    if (exactPlanJson == null || exactPlanJson.isBlank()) {
      throw new IllegalStateException("Cannot open plan gate without exact persisted plan bytes");
    }
    if (!Objects.equals(exactPlanJson, command.exactPlanJson())) {
      throw new IllegalStateException("Plan gate exact plan bytes changed before opening");
    }
    if (!Objects.equals(ExactPlanFingerprint.sha256(exactPlanJson), command.planFingerprint())) {
      throw new IllegalStateException("Plan gate plan fingerprint does not match persisted bytes");
    }

    String gateKey = "gate:plan:" + command.runId();
    String continuationSha = sha256(command.continuationPayload());
    var binding =
        bindingService.select(
            command.runId(), run.getUserRequest(), exactPlanJson, command.bindingMode());
    Instant now = Instant.now();

    if ("AWAITING_APPROVAL".equals(run.getStatus())) {
      verifyExactExisting(gateKey, command, continuationSha, binding);
      return OpenResult.ALREADY_OPEN;
    }
    if (!"PLANNING".equals(run.getStatus())) {
      throw new IllegalStateException(
          "Cannot open plan gate from run status " + String.valueOf(run.getStatus()));
    }

    store.insertGate(
        gateKey,
        command.runId(),
        command.continuationPayload(),
        continuationSha,
        command.continuationSchemaVersion(),
        command.contractGateReportDigest(),
        binding,
        now);
    store.insertWaitingOutbox(
        command.runId(),
        gateKey,
        command.continuationPayload(),
        continuationSha,
        command.continuationSchemaVersion(),
        now);
    verifyExactExisting(gateKey, command, continuationSha, binding);

    run.setStatus("AWAITING_APPROVAL");
    run.setCurrentGate(GATE_TYPE);
    runRepository.saveAndFlush(run);
    int credits = run.getCreditsCharged() == null ? 1 : run.getCreditsCharged();
    double estimatedCost = run.getCostEstimateHigh() == null ? 0.0 : run.getCostEstimateHigh();
    eventPublisher.publishEvent(
        new PlanGateOpenedCommitted(run.getRunId(), credits, estimatedCost));
    return OpenResult.OPENED;
  }

  private void verifyExactExisting(
      String gateKey,
      OpenCommand command,
      String continuationSha,
      RequirementGateBindingService.RequirementGateBinding binding) {
    GateRecord gate =
        gateRepository
            .findByIdForPlanGateOpen(gateKey)
            .orElseThrow(() -> new IllegalStateException("Plan gate insert did not persist"));
    PlanGateResumeOutbox outbox =
        outboxRepository
            .findById(command.runId())
            .orElseThrow(
                () -> new IllegalStateException("Plan gate continuation insert did not persist"));
    boolean boundIdentity = binding.contractId() != null;
    boolean same =
        "PENDING".equals(gate.getStatus())
            && Objects.equals(gate.getRunId(), command.runId())
            && Objects.equals(gate.getGateType(), "PLAN")
            && gate.getResolvedAt() == null
            && gate.getAdmissionContractVersion() == null
            && gate.getApprovalEvidenceJson() == null
            && gate.getApprovalEvidenceDigest() == null
            && gate.getResolvedByUserId() == null
            && gate.getResolvedByRole() == null
            && gate.getResolutionSource() == null
            && gate.getResolutionReason() == null
            && gate.getBillingOwnerUserId() == null
            && gate.getBillingOrgId() == null
            && gate.getAdmittedAt() == null
            && Objects.equals(gate.getComment(), command.continuationPayload())
            && Objects.equals(
                gate.getContinuationSchemaVersion(), command.continuationSchemaVersion())
            && Objects.equals(gate.getContinuationSha256(), continuationSha)
            && Objects.equals(
                gate.getContractGateReportDigest(), command.contractGateReportDigest())
            && Objects.equals(gate.getBindingMode(), binding.mode().name())
            && Objects.equals(gate.getBindingStatus(), PlanGateOpenStore.persistedStatus(binding))
            && Objects.equals(gate.getBindingReason(), PlanGateOpenStore.bindingReason(binding))
            && Objects.equals(gate.getRequirementContractId(), binding.contractId())
            && Objects.equals(
                gate.getBindingSchemaVersion(), boundIdentity ? binding.schemaVersion() : null)
            && Objects.equals(
                gate.getBindingCompilerFingerprint(),
                boundIdentity ? binding.compilerFingerprint() : null)
            && Objects.equals(
                gate.getBindingSourceSha256(), boundIdentity ? binding.sourceSha256() : null)
            && Objects.equals(
                gate.getBindingExactPlanFingerprint(),
                boundIdentity ? binding.exactPlanFingerprint() : null)
            && Objects.equals(
                gate.getBindingArtifactDigest(), boundIdentity ? binding.artifactDigest() : null)
            && Objects.equals(
                gate.getBindingBoundDigest(), boundIdentity ? binding.boundDigest() : null)
            && (boundIdentity == (gate.getBindingBoundAt() != null))
            && Objects.equals(outbox.getGateKey(), gateKey)
            && Objects.equals(outbox.getPayload(), command.continuationPayload())
            && Objects.equals(outbox.getPayloadSha256(), continuationSha)
            && outbox.getHandlerContractVersion() == command.continuationSchemaVersion()
            && "WAITING".equals(outbox.getStatus());
    if (!same) {
      throw new IllegalStateException("Conflicting durable plan gate already exists");
    }
  }

  private static void requireCommand(OpenCommand command) {
    if (command == null
        || command.runId() == null
        || command.runId().isBlank()
        || command.exactPlanJson() == null
        || command.exactPlanJson().isBlank()
        || command.planFingerprint() == null
        || command.planFingerprint().isBlank()
        || command.continuationPayload() == null
        || command.continuationPayload().isBlank()
        || command.continuationSchemaVersion() <= 0
        || command.bindingMode() == null) {
      throw new IllegalArgumentException("Incomplete plan gate open command");
    }
  }

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  enum OpenResult {
    OPENED,
    ALREADY_OPEN
  }

  record OpenCommand(
      String runId,
      String exactPlanJson,
      String planFingerprint,
      String continuationPayload,
      int continuationSchemaVersion,
      String contractGateReportDigest,
      RequestedMode bindingMode) {}
}
