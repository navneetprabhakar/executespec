package com.navneet.executespec.integration;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.EntityManager;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.requirement.RequirementIrApprovalCompatibilityRegistry;
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
import lombok.extern.slf4j.Slf4j;

/**
 * The single database transaction that decides a plan gate.
 *
 * <p>The lock order is run, gate, outbox, optional contract report, RequirementIR, then mutable
 * admission-policy rows. Receipt construction/replay verification and locked policy evaluation are
 * delegated to focused, side-effect-free collaborators while this service retains transaction and
 * state-transition ownership. No executor, workspace, SSE, Redis, provider, or other external side
 * effect is invoked here. The committed READY event is consumed by an {@code AFTER_COMMIT}
 * dispatcher.
 *
 * <p>Structural partial state (a missing gate/continuation row, a gate/continuation identity or
 * digest mismatch, an invalid continuation payload, or a missing immutable plan artifact — e.g.
 * after an interrupted rolling deploy) is typed through {@link BrokenPlanGateStateException} and
 * resolved deterministically: APPROVE refuses with the stable {@code
 * PLAN_GATE_STATE_UNRECOVERABLE[code]} 409 and a cancel-and-resubmit remediation, while REJECT and
 * pre-approval CANCEL fence the run to a terminal cancelled state with a typed reason. The
 * broken-state fence never fabricates an APPROVE receipt, never mutates a resolved gate, and leaves
 * a structurally broken PENDING gate row byte-untouched because no valid canonical receipt can be
 * written against corrupt rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanGateDecisionService {
  static final int ADMISSION_CONTRACT_VERSION = DecisionEvidenceV1.SCHEMA_VERSION;

  /** Stable machine-readable prefix for decisions refused on structurally broken gate state. */
  public static final String UNRECOVERABLE_CODE = "PLAN_GATE_STATE_UNRECOVERABLE";

  private static final String PLAN_GATE_TYPE = "PLAN";

  private final PipelineRunRepository runRepository;
  private final GateRepository gateRepository;
  private final PlanGateResumeOutboxRepository outboxRepository;
  private final RunRequirementContractRepository requirementContractRepository;
  private final RequirementIrApprovalCompatibilityRegistry requirementIrCompatibilityRegistry;
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

    PipelineRun run =
        runRepository
            .findByIdForPlanGateOpen(runId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found: " + runId));
    try {
      return decideWithLockedRun(run, kind, command, actor, resolutionSource, resolutionReason);
    } catch (BrokenPlanGateStateException broken) {
      return resolveBrokenGateState(run, kind, resolutionSource, resolutionReason, broken);
    }
  }

  private DecisionResult decideWithLockedRun(
      PipelineRun run,
      DecisionKind kind,
      PlanApprovalService.Command command,
      AppUserDetails actor,
      String resolutionSource,
      String resolutionReason) {
    String runId = run.getRunId();
    LockedGateRows rows = lockGateRows(runId);
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
          lockBoundEvidence(runId, gate, run.getUserRequest(), kind));
    }
    if (!"AWAITING_APPROVAL".equals(run.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Plan decision cannot be made from the run's current state.");
    }

    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    BoundEvidence boundEvidence = lockBoundEvidence(runId, gate, run.getUserRequest(), kind);
    AppUserDetails lockedActor =
        PlanGateDecisionAdmission.lockPolicyRows(entityManager, run, actor, kind);
    PlanGateDecisionEvidence.ActorEvidence actorEvidence =
        PlanGateDecisionEvidence.actorEvidence(lockedActor, kind, run);
    String suppliedPlanFingerprint =
        PlanGateDecisionEvidence.normalizeSha(command.planFingerprint());
    if (kind == DecisionKind.APPROVE
        && "ENFORCED".equals(gate.getBindingMode())
        && suppliedPlanFingerprint == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "RequirementIR-enforced approval requires the exact plan fingerprint. Reload the plan"
              + " before approving.");
    }
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

  private LockedGateRows lockGateRows(String runId) {
    String gateKey = "gate:plan:" + runId;
    GateRecord gate =
        gateRepository
            .findByIdForPlanGateOpen(gateKey)
            .orElseThrow(
                () ->
                    new BrokenPlanGateStateException(
                        BrokenPlanGateStateException.Code.GATE_ROW_MISSING,
                        "The durable plan gate is missing."));
    PlanGateResumeOutbox outbox =
        outboxRepository
            .findByIdForUpdate(runId)
            .orElseThrow(
                () ->
                    new BrokenPlanGateStateException(
                        BrokenPlanGateStateException.Code.CONTINUATION_ROW_MISSING,
                        "The durable plan continuation is missing."));
    verifyGateIdentity(runId, gateKey, gate, outbox);
    return new LockedGateRows(gate, outbox);
  }

  /**
   * Deterministic resolution for structurally broken durable gate state.
   *
   * <p>APPROVE must never execute over corrupt authority, so it refuses with the stable
   * machine-readable conflict. REJECT and pre-approval CANCEL succeed by fencing the run (and any
   * surviving continuation row) to a terminal cancelled state with the typed reason — a customer
   * can always stop a broken run. No receipt is fabricated: a broken PENDING gate row is left
   * untouched because a canonical {@link DecisionEvidenceV1} cannot be truthfully constructed from
   * corrupt or missing rows, and a resolved gate's immutable receipt is never rewritten.
   */
  private DecisionResult resolveBrokenGateState(
      PipelineRun run,
      DecisionKind kind,
      String resolutionSource,
      String resolutionReason,
      BrokenPlanGateStateException broken) {
    String typedReason = UNRECOVERABLE_CODE + "[" + broken.code() + "]: " + broken.getMessage();
    if (kind == DecisionKind.APPROVE) {
      log.warn(
          "Plan gate approve refused on broken durable state: run={} code={}",
          run.getRunId(),
          broken.code());
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          typedReason
              + " Approval cannot safely authorize execution from this state. Cancel the run and"
              + " resubmit the spec.");
    }
    if (!"AWAITING_APPROVAL".equals(run.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          typedReason + " Plan decision cannot be made from the run's current state.");
    }
    log.warn(
        "Fencing run with broken plan-gate state to CANCELLED: run={} code={} source={}",
        run.getRunId(),
        broken.code(),
        resolutionSource);
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    outboxRepository
        .findByIdForUpdate(run.getRunId())
        .ifPresent(
            outbox -> {
              clearLease(outbox);
              outbox.setStatus("CANCELLED");
              outbox.setCompletedAt(now);
              outbox.setLastError(UNRECOVERABLE_CODE + "[" + broken.code() + "]");
              outboxRepository.save(outbox);
            });
    String failureReason =
        typedReason + (resolutionReason == null ? "" : " (" + resolutionReason + ")");
    if (failureReason.length() > 512) failureReason = failureReason.substring(0, 512);
    run.setStatus("CANCELLED");
    run.setCurrentGate(null);
    run.setCompletedAt(now);
    run.setFailureReason(failureReason);
    runRepository.saveAndFlush(run);
    return new DecisionResult(run, DecisionKind.CANCEL, null, false);
  }

  private BoundEvidence lockBoundEvidence(
      String runId, GateRecord gate, String exactSource, DecisionKind kind) {
    PlanContractGateReport contractReport =
        PlanGateDecisionAdmission.lockContractReport(entityManager, runId);
    PlanGateDecisionAdmission.verifyContractReportBinding(gate, contractReport);
    RunRequirementContract requirementContract =
        PlanGateDecisionAdmission.lockRequirementContract(requirementContractRepository, gate);
    try {
      if (kind == DecisionKind.APPROVE && requirementContract != null) {
        requirementIrCompatibilityRegistry.requireSupported(
            requirementContract.getSchemaVersion(), requirementContract.getCompilerFingerprint());
        if ("ENFORCED".equals(gate.getBindingMode())) {
          requirementIrCompatibilityRegistry.verifyApprovalCompatible(
              requirementContract, exactSource);
        }
      }
    } catch (
        RequirementIrApprovalCompatibilityRegistry.IncompatibleRequirementIrException
            incompatible) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "The bound RequirementIR version is not approval compatible with this deployment.",
          incompatible);
    }
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
    gate.setBillingOwnerUserId(evidence.billingOwnerUserId());
    gate.setBillingOrgId(evidence.billingOrgId());
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
      throw new BrokenPlanGateStateException(
          BrokenPlanGateStateException.Code.GATE_CONTINUATION_IDENTITY_MISMATCH,
          "The durable plan gate and continuation identity do not match.");
    }
    try {
      PlanGateContinuation continuation = PlanGateContinuation.parse(outbox.getPayload());
      if (outbox.getHandlerContractVersion() != PlanGateContinuation.SCHEMA_VERSION
          || continuation.schemaVersion() != outbox.getHandlerContractVersion()) {
        throw new IllegalArgumentException("unsupported continuation schema");
      }
    } catch (IllegalArgumentException invalid) {
      throw new BrokenPlanGateStateException(
          BrokenPlanGateStateException.Code.CONTINUATION_PAYLOAD_INVALID,
          "The durable plan continuation payload is invalid.",
          invalid);
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

  private record LockedGateRows(GateRecord gate, PlanGateResumeOutbox outbox) {}

  private record BoundEvidence(
      PlanContractGateReport contractReport, RunRequirementContract requirementContract) {}
}
