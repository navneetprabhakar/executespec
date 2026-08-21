package com.navneet.executespec.integration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationDescriptor;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Owns durable LLM-operation leases for phase boundaries in the central run pipeline. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunLlmOperationLifecycleService {

  private static final Duration LEASE_DURATION = Duration.ofHours(4);
  private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
  private static final String LEASE_OWNER = "run-orchestrator:" + UUID.randomUUID();

  private final PipelineRunRepository runRepo;
  private final LlmExecutionPolicyFactory policyFactory;
  private final LlmExecutionOperationCoordinator coordinator;

  public PhaseLease claimPlanning(
      ExecutionContext context, String runId, String provider, String model) {
    return claim(context, runId, "planning", "RUN_PLANNING", provider + "|" + model);
  }

  public PhaseLease claimExecution(
      ExecutionContext context, String runId, String provider, String model) {
    return claim(context, runId, "execution", "RUN_EXECUTION", provider + "|" + model);
  }

  /** Claims one independently fenced BUILD task under the run's durable LLM context. */
  public PhaseLease claimBuildTask(
      ExecutionContext context,
      String runId,
      String taskId,
      int taskIndex,
      String immutableIdentity) {
    return claim(
        context,
        runId,
        "build-task:" + taskId,
        "RUN_BUILD_TASK",
        taskIndex + "|" + taskId + "|" + immutableIdentity);
  }

  /** Starts a distinct durable LLM invocation for a new physical BUILD generation attempt. */
  public PhaseLease beginBuildTaskAttempt(
      ExecutionContext context,
      String runId,
      String taskId,
      int taskIndex,
      String immutableIdentity) {
    return beginNew(
        context,
        runId,
        "build-task:" + taskId,
        "RUN_BUILD_TASK",
        taskIndex + "|" + taskId + "|" + immutableIdentity);
  }

  public PhaseLease claimReview(
      ExecutionContext context, String runId, String provider, String model) {
    return claim(context, runId, "review", "RUN_REVIEW", provider + "|" + model);
  }

  public PhaseLease beginReviewRetry(
      ExecutionContext context, String runId, String provider, String model) {
    return beginNew(context, runId, "review", "RUN_REVIEW", provider + "|" + model);
  }

  public PhaseLease claimPatch(
      ExecutionContext context, String runId, String provider, String model) {
    return claim(context, runId, "patch", "RUN_PATCH", provider + "|" + model);
  }

  public PhaseLease claimValidation(String runId, String phase, String immutableIdentity) {
    return claimOneShot(
        runScopedContext(runId, phase), runId, phase, "RUN_VALIDATION", immutableIdentity);
  }

  public PhaseLease claimAsyncTestGeneration(String runId, String immutableIdentity) {
    return claimOneShot(
        runScopedContext(runId, "async-test-generation"),
        runId,
        "async-test-generation",
        "RUN_ASYNC_TEST_GENERATION",
        immutableIdentity);
  }

  public PhaseLease claimPostValidationContinuation(
      ExecutionContext context, String runId, String immutableIdentity) {
    return claimOneShot(
        context,
        runId,
        "post-validation-continuation",
        "RUN_POST_VALIDATION_CONTINUATION",
        immutableIdentity);
  }

  public PhaseLease claim(
      ExecutionContext baseContext,
      String runId,
      String phase,
      String operationKind,
      String immutableIdentity) {
    LlmExecutionReference reference =
        new LlmExecutionReference(
            baseContext.llmExecutionContextId(), baseContext.llmExecutionScope());
    LlmExecutionOperationDescriptor descriptor =
        descriptor(runId, phase, operationKind, immutableIdentity);
    LlmExecutionOperationCoordinator.Lease lease =
        coordinator
            .claimOrResume(reference, descriptor, LEASE_OWNER, LEASE_DURATION)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Run phase is terminal, active elsewhere, or not retryable: "
                            + runId
                            + "/"
                            + phase));
    return new PhaseLease(phase, lease, coordinator.bind(baseContext, lease));
  }

  private PhaseLease beginNew(
      ExecutionContext baseContext,
      String runId,
      String phase,
      String operationKind,
      String immutableIdentity) {
    LlmExecutionReference reference =
        new LlmExecutionReference(
            baseContext.llmExecutionContextId(), baseContext.llmExecutionScope());
    LlmExecutionOperationCoordinator.Lease lease =
        coordinator.beginNewInvocation(
            reference,
            descriptor(runId, phase, operationKind, immutableIdentity),
            LEASE_OWNER,
            LEASE_DURATION);
    return new PhaseLease(phase, lease, coordinator.bind(baseContext, lease));
  }

  private PhaseLease claimOneShot(
      ExecutionContext baseContext,
      String runId,
      String phase,
      String operationKind,
      String immutableIdentity) {
    LlmExecutionReference reference =
        new LlmExecutionReference(
            baseContext.llmExecutionContextId(), baseContext.llmExecutionScope());
    LlmExecutionOperationCoordinator.Lease lease =
        coordinator
            .claimOrResume(
                reference,
                oneShotDescriptor(runId, phase, operationKind, immutableIdentity),
                LEASE_OWNER,
                LEASE_DURATION)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "One-shot run operation is terminal, active elsewhere, or ambiguous: "
                            + runId
                            + "/"
                            + phase));
    return new PhaseLease(phase, lease, coordinator.bind(baseContext, lease));
  }

  private ExecutionContext runScopedContext(String runId, String phase) {
    LlmExecutionReference reference = policyFactory.forRun(runId);
    return new ExecutionContext(
        runId,
        "__" + phase + "__",
        Path.of(System.getProperty("java.io.tmpdir", "/tmp")).toAbsolutePath().normalize(),
        reference);
  }

  public ExecutionContext bind(ExecutionContext baseContext, PhaseLease lease) {
    if (lease == null) return baseContext;
    return lease.context();
  }

  public void complete(PhaseLease lease) {
    if (lease == null) return;
    if (!coordinator.complete(lease.delegate())) {
      throw new IllegalStateException("Run operation completion lost its lease: " + lease.phase());
    }
  }

  public PhaseLease completeAndClear(PhaseLease lease) {
    complete(lease);
    return null;
  }

  public PhaseLease finishReviewAndClear(PhaseLease lease, boolean failed) {
    if (failed) fail(lease, "REVIEW_FAILED");
    else complete(lease);
    return null;
  }

  public void fail(PhaseLease lease, String code) {
    if (lease == null) return;
    if (!coordinator.fail(
        lease.delegate(),
        code,
        "run phase did not persist a successful business outcome: " + lease.phase(),
        true,
        RETRY_DELAY)) {
      throw new IllegalStateException("Run operation failure lost its lease: " + lease.phase());
    }
  }

  /** A physical BUILD generation is terminal; replacement generations use a new invocation. */
  public void failBuildTask(PhaseLease lease, String code) {
    if (lease == null) return;
    if (!coordinator.fail(
        lease.delegate(),
        code,
        "BUILD task attempt did not persist an accepted generation: " + lease.phase(),
        false,
        Duration.ZERO)) {
      throw new IllegalStateException(
          "BUILD task operation failure lost its lease: " + lease.phase());
    }
  }

  public void terminateOneShot(PhaseLease lease, String code) {
    if (lease == null) return;
    if (coordinator.acknowledgeCancellation(lease.delegate())) return;
    if (!coordinator.fail(
        lease.delegate(),
        code,
        "one-shot run operation did not persist a successful business outcome: " + lease.phase(),
        false,
        null)) {
      throw new IllegalStateException(
          "One-shot run operation failure lost its lease: " + lease.phase());
    }
  }

  public void heartbeat(PhaseLease lease) {
    if (lease == null) return;
    if (!coordinator.heartbeat(lease.delegate(), LEASE_DURATION)) {
      throw new IllegalStateException("Run operation heartbeat lost its lease: " + lease.phase());
    }
  }

  public LlmExecutionOperationCoordinator.LeaseIdentity identity(PhaseLease lease) {
    if (lease == null) throw new IllegalArgumentException("lease required");
    return coordinator.identity(lease.delegate());
  }

  public void closeFromPersistedState(String runId, PhaseLease lease) {
    if (lease == null) return;
    String status = runRepo.findById(runId).map(PipelineRun::getStatus).orElse("MISSING");
    if ("CANCELLED".equals(status)) {
      acknowledgeCancellation(runId, lease);
    } else if (RunPipelineSupport.isTerminal(status)
        && !"FAILED".equals(status)
        && !"MISSING".equals(status)) {
      complete(lease);
    } else {
      fail(lease, "RUN_PHASE_INTERRUPTED");
    }
  }

  public PhaseLease closeAndClear(String runId, PhaseLease lease) {
    closeFromPersistedState(runId, lease);
    return null;
  }

  public void closeQuietly(String runId, PhaseLease lease) {
    if (lease == null) return;
    try {
      closeFromPersistedState(runId, lease);
    } catch (RuntimeException failure) {
      log.error(
          "Run operation close failed run={} phase={} error={}",
          runId,
          lease.phase(),
          failure.getClass().getSimpleName());
    }
  }

  private void acknowledgeCancellation(String runId, PhaseLease lease) {
    coordinator.requestCancellation(
        policyFactory.forRun(runId), "pipeline-run:" + runId + ":" + lease.phase());
    if (!coordinator.acknowledgeCancellation(lease.delegate())) {
      throw new IllegalStateException(
          "Run operation cancellation acknowledgement lost its lease: " + lease.phase());
    }
  }

  static LlmExecutionOperationDescriptor descriptor(
      String runId, String phase, String operationKind, String immutableIdentity) {
    return LlmExecutionOperationDescriptor.of(
        "pipeline-run:" + runId + ":" + phase,
        operationKind,
        runId + "|" + phase + "|" + immutableIdentity,
        4);
  }

  static LlmExecutionOperationDescriptor oneShotDescriptor(
      String runId, String phase, String operationKind, String immutableIdentity) {
    return LlmExecutionOperationDescriptor.of(
        "pipeline-run:" + runId + ":" + phase,
        operationKind,
        runId + "|" + phase + "|" + immutableIdentity,
        1);
  }

  static String leaseOwnerForTest() {
    return LEASE_OWNER;
  }

  public record PhaseLease(
      String phase, LlmExecutionOperationCoordinator.Lease delegate, ExecutionContext context) {
    public PhaseLease {
      if (phase == null || phase.isBlank()) throw new IllegalArgumentException("phase required");
      if (delegate == null) throw new IllegalArgumentException("delegate required");
      if (context == null) throw new IllegalArgumentException("context required");
    }
  }
}
