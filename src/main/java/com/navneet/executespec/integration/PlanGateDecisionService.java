package com.navneet.executespec.integration;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.EntityManager;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.contracts.gate.PlanContractGateReport;
import com.navneet.executespec.governance.GovernanceGuard;
import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.entity.PlanGateResumeOutbox;
import com.navneet.executespec.harness.repository.GateRepository;
import com.navneet.executespec.harness.repository.PlanGateResumeOutboxRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * The single database transaction that decides a plan gate.
 *
 * <p>The lock order is run, gate, outbox, optional contract report, RequirementIR, then mutable
 * admission-policy rows. Receipt construction/replay verification and locked policy evaluation are
 * delegated to focused, side-effect-free collaborators while this service retains transaction and
 * state-transition ownership. No executor, workspace, SSE, Redis, provider, or other external side
 * effect is invoked here. The committed READY event is consumed by an {@code AFTER_COMMIT}
 * dispatcher.
 */
@Service
@RequiredArgsConstructor
public class PlanGateDecisionService {
  static final int ADMISSION_CONTRACT_VERSION = DecisionEvidenceV1.SCHEMA_VERSION;
  private static final String PLAN_GATE_TYPE = "PLAN";

  private final PipelineRunRepository runRepository;
  private final GateRepository gateRepository;
  private final PlanGateResumeOutboxRepository outboxRepository;
  private final RunRequirementContractRepository requirementContractRepository;
  private final EntityManager entityManager;
  private final ApiKeyResolver apiKeyResolver;
  private final ManagedAiUsageService managedAiUsageService;
  private final GovernanceGuard governanceGuard;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional
  public DecisionResult decide(
      String runId, PlanApprovalService.Command command, AppUserDetails actor) {
    return decide(runId, command, actor, ResolutionSource.PLAN_APPROVAL_API);
  }

  @Transactional
  public DecisionResult decide(
      String runId,
      PlanApprovalService.Command command,
      AppUserDetails actor,
      ResolutionSource resolutionSource) {
    if (resolutionSource == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Plan decision resolution source is required.");
    }
    DecisionKind kind = command.approved() ? DecisionKind.APPROVE : DecisionKind.REJECT;
    return decideLocked(
        runId,
        kind,
        command,
        actor,
        resolutionSource.name(),
        kind == DecisionKind.REJECT ? "plan rejected at approval gate" : null);
  }

  /** Atomically cancels a still-pending gate without recording a rejection. */
  @Transactional
  public DecisionResult cancel(
      String runId, AppUserDetails actor, ResolutionSource resolutionSource, String reason) {
    if (resolutionSource != ResolutionSource.RUN_CANCEL_API
        && resolutionSource != ResolutionSource.PROJECT_CHAT_ACTION
        && resolutionSource != ResolutionSource.SYSTEM_CANCEL) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Cancellation requires a cancellation resolution source.");
    }
    return decideLocked(
        runId,
        DecisionKind.CANCEL,
        PlanApprovalService.Command.rejectLegacy(),
        actor,
        resolutionSource.name(),
        PlanGateDecisionEvidence.bounded(
            PlanGateDecisionEvidence.normalized(reason, "cancelled before plan approval"),
            512,
            "resolution reason"));
  }

  private DecisionResult decideLocked(
      String runId,
      DecisionKind kind,
      PlanApprovalService.Command command,
      AppUserDetails actor,
      String resolutionSource,
      String resolutionReason) {
    if (runId == null || runId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Run id is required.");
    }
    validateResolutionSource(kind, resolutionSource, actor);

    LockedDecisionRows rows = lockDecisionRows(runId);
    PipelineRun run = rows.run();
    GateRecord gate = rows.gate();
    PlanGateResumeOutbox outbox = rows.outbox();
    String actualPlanFingerprint = PlanGateDecisionEvidence.requireExactPlan(run);
    String executionMode =
        PlanGateDecisionEvidence.normalizedExecutionMode(command.executionMode());
    String persistedExecutionMode =
        PlanGateDecisionEvidence.normalizedExecutionMode(run.getApprovedExecutionMode());
    if (!Objects.equals(executionMode, persistedExecutionMode)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "The approved execution mode changed. Reload the plan before deciding.");
    }
    String requestFingerprint =
        PlanGateDecisionEvidence.decisionRequestFingerprint(
            kind,
            command,
            actor,
            actualPlanFingerprint,
            resolutionSource,
            resolutionReason,
            executionMode);
    if (!"PENDING".equals(gate.getStatus())) {
      return replay(
          run,
          gate,
          kind,
          actualPlanFingerprint,
          requestFingerprint,
          lockBoundEvidence(runId, gate));
    }
    if (!"AWAITING_APPROVAL".equals(run.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Plan decision cannot be made from the run's current state.");
    }

    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    BoundEvidence boundEvidence = lockBoundEvidence(runId, gate);
    AppUserDetails lockedActor =
        PlanGateDecisionAdmission.lockPolicyRows(entityManager, run, actor, kind);
    PlanGateDecisionEvidence.ActorEvidence actorEvidence =
        PlanGateDecisionEvidence.actorEvidence(lockedActor, kind, run);
    String suppliedPlanFingerprint =
        PlanGateDecisionEvidence.normalizeSha(command.planFingerprint());
    Boolean fingerprintMatches =
        suppliedPlanFingerprint == null
            ? null
            : suppliedPlanFingerprint.equalsIgnoreCase(actualPlanFingerprint);
    if (Boolean.FALSE.equals(fingerprintMatches)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "The plan you reviewed is not the plan currently on this run. Reload the plan and review"
              + " it again before approving.");
    }

    PlanGateDecisionEvidence.AdmissionEvidence admission =
        kind == DecisionKind.APPROVE
            ? PlanGateDecisionAdmission.admitApproval(
                run,
                command,
                lockedActor,
                actorEvidence.userId(),
                boundEvidence.contractReport(),
                now,
                apiKeyResolver,
                managedAiUsageService,
                governanceGuard)
            : PlanGateDecisionEvidence.notEvaluated(run);
    DecisionEvidenceV1 evidence =
        PlanGateDecisionEvidence.create(
            run,
            gate,
            kind,
            command,
            actorEvidence,
            admission,
            boundEvidence.contractReport(),
            boundEvidence.requirementContract(),
            actualPlanFingerprint,
            suppliedPlanFingerprint,
            fingerprintMatches,
            requestFingerprint,
            executionMode,
            resolutionSource,
            resolutionReason,
            now);
    persistDecision(
        run, gate, outbox, kind, actorEvidence, evidence, resolutionSource, resolutionReason, now);
    if (kind == DecisionKind.APPROVE) {
      eventPublisher.publishEvent(new PlanGateResumeReadyCommitted(runId));
    }
    return new DecisionResult(run, kind, evidence, false);
  }

  private LockedDecisionRows lockDecisionRows(String runId) {
    PipelineRun run =
        runRepository
            .findByIdForPlanGateOpen(runId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found: " + runId));
    String gateKey = "gate:plan:" + runId;
    GateRecord gate =
        gateRepository
            .findByIdForPlanGateOpen(gateKey)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.CONFLICT, "The durable plan gate is missing."));
    PlanGateResumeOutbox outbox =
        outboxRepository
            .findByIdForUpdate(runId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.CONFLICT, "The durable plan continuation is missing."));
    verifyGateIdentity(runId, gateKey, gate, outbox);
    return new LockedDecisionRows(run, gate, outbox);
  }

  private BoundEvidence lockBoundEvidence(String runId, GateRecord gate) {
    PlanContractGateReport contractReport =
        PlanGateDecisionAdmission.lockContractReport(entityManager, runId);
    PlanGateDecisionAdmission.verifyContractReportBinding(gate, contractReport);
    RunRequirementContract requirementContract =
        PlanGateDecisionAdmission.lockRequirementContract(requirementContractRepository, gate);
    return new BoundEvidence(contractReport, requirementContract);
  }

  private static DecisionResult replay(
      PipelineRun run,
      GateRecord gate,
      DecisionKind kind,
      String actualPlanFingerprint,
      String requestFingerprint,
      BoundEvidence boundEvidence) {
    return PlanGateDecisionEvidence.replayOrConflict(
        run,
        gate,
        kind,
        actualPlanFingerprint,
        requestFingerprint,
        boundEvidence.contractReport(),
        boundEvidence.requirementContract());
  }

  private void persistDecision(
      PipelineRun run,
      GateRecord gate,
      PlanGateResumeOutbox outbox,
      DecisionKind kind,
      PlanGateDecisionEvidence.ActorEvidence actor,
      DecisionEvidenceV1 evidence,
      String resolutionSource,
      String resolutionReason,
      Instant now) {
    String evidenceJson = evidence.canonicalJson();
    gate.setAdmissionContractVersion(ADMISSION_CONTRACT_VERSION);
    gate.setApprovalEvidenceJson(evidenceJson);
    gate.setApprovalEvidenceDigest(DecisionEvidenceV1.sha256(evidenceJson));
    gate.setDecisionKind(kind.name());
    gate.setResolvedActorType(actor.type());
    gate.setDecisionRequestFingerprint(evidence.decisionRequestFingerprint());
    gate.setStatus(
        kind == DecisionKind.APPROVE
            ? "APPROVED"
            : kind == DecisionKind.REJECT ? "REJECTED" : "CANCELLED");
    gate.setResolvedAt(now);
    gate.setResolvedByUserId(actor.userId());
    gate.setResolvedByRole(actor.effectiveRole());
    gate.setResolvedAuthorizationSource(actor.authorizationBasis());
    gate.setResolvedAuthorizationOrgId(actor.authorizationOrgId());
    gate.setResolutionSource(resolutionSource);
    gate.setResolutionReason(resolutionReason);
    gate.setBillingOwnerUserId(run.getCreatedByUserId());
    gate.setBillingOrgId(run.getOrgId());
    gate.setAdmittedAt(now);

    clearLease(outbox);
    if (kind == DecisionKind.APPROVE) {
      outbox.setStatus("READY");
      outbox.setNextRetryAt(now);
      outbox.setCompletedAt(null);
      outbox.setLastError(null);
      run.setStatus("APPROVAL_RESUMING");
      run.setResumedAt(now);
    } else {
      outbox.setStatus("CANCELLED");
      outbox.setCompletedAt(now);
      outbox.setLastError(kind == DecisionKind.REJECT ? "PLAN_REJECTED" : "PLAN_CANCELLED");
      run.setStatus("CANCELLED");
      run.setCurrentGate(null);
      run.setCompletedAt(now);
      run.setFailureReason(resolutionReason);
    }
    gateRepository.save(gate);
    outboxRepository.save(outbox);
    runRepository.saveAndFlush(run);
  }

  private static void verifyGateIdentity(
      String runId, String gateKey, GateRecord gate, PlanGateResumeOutbox outbox) {
    boolean valid =
        PLAN_GATE_TYPE.equals(gate.getGateType())
            && runId.equals(gate.getRunId())
            && gateKey.equals(outbox.getGateKey())
            && runId.equals(outbox.getRunId())
            && Objects.equals(
                gate.getContinuationSchemaVersion(), outbox.getHandlerContractVersion())
            && Objects.equals(gate.getContinuationSha256(), outbox.getPayloadSha256())
            && outbox.getPayload() != null
            && Objects.equals(
                outbox.getPayloadSha256(), DecisionEvidenceV1.sha256(outbox.getPayload()));
    if (!valid) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "The durable plan gate and continuation identity do not match.");
    }
    try {
      PlanGateContinuation continuation = PlanGateContinuation.parse(outbox.getPayload());
      if (outbox.getHandlerContractVersion() != PlanGateContinuation.SCHEMA_VERSION
          || continuation.schemaVersion() != outbox.getHandlerContractVersion()) {
        throw new IllegalArgumentException("unsupported continuation schema");
      }
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "The durable plan continuation payload is invalid.", invalid);
    }
  }

  private static void validateResolutionSource(
      DecisionKind kind, String source, AppUserDetails actor) {
    ResolutionSource typed;
    try {
      typed = ResolutionSource.valueOf(source);
    } catch (RuntimeException invalid) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Plan decision resolution source is invalid.");
    }
    boolean cancellationSource =
        typed == ResolutionSource.RUN_CANCEL_API
            || typed == ResolutionSource.SYSTEM_CANCEL
            || typed == ResolutionSource.PROJECT_CHAT_ACTION;
    if ((kind == DecisionKind.CANCEL) != cancellationSource
        && !(kind != DecisionKind.CANCEL && typed == ResolutionSource.PROJECT_CHAT_ACTION)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Plan decision kind and resolution source are incompatible.");
    }
    boolean systemActor = actor == null || actor.getUser() == null;
    if ((typed == ResolutionSource.SYSTEM_CANCEL) != systemActor) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "System cancellation authority does not match the decision actor.");
    }
  }

  private static void clearLease(PlanGateResumeOutbox outbox) {
    outbox.setLeaseOwner(null);
    outbox.setLeaseToken(null);
    outbox.setLeaseExpiresAt(null);
    outbox.setHeartbeatAt(null);
  }

  public enum DecisionKind {
    APPROVE,
    REJECT,
    CANCEL
  }

  /** Stable audit vocabulary for every customer/system plan-gate decision surface. */
  public enum ResolutionSource {
    PLAN_APPROVAL_API,
    EXECUTION_VIEW_API,
    PROJECT_CHAT_ACTION,
    RUN_CANCEL_API,
    SYSTEM_CANCEL
  }

  public record DecisionResult(
      PipelineRun run, DecisionKind decision, DecisionEvidenceV1 evidence, boolean replayed) {}

  private record LockedDecisionRows(
      PipelineRun run, GateRecord gate, PlanGateResumeOutbox outbox) {}

  private record BoundEvidence(
      PlanContractGateReport contractReport, RunRequirementContract requirementContract) {}
}
