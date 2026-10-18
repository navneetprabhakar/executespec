package com.navneet.executespec.harness;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.integration.RunLlmOperationLifecycleService;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.workspacegeneration.BuildProtocolVersion;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttempt;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttemptRepository;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationKind;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationState;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Atomically admits one BUILD continuation using the canonical run-then-task lock order. */
@Service
@RequiredArgsConstructor
public class BuildContinuationAdmissionService {

  private static final List<String> REOPENABLE_TERMINAL_RUNS =
      List.of("PARTIAL", "FAILED", "COMPLETE");

  private final PipelineRunRepository runs;
  private final AgentTaskRepository tasks;
  private final WorkspaceGenerationAttemptRepository attempts;
  private final RunLlmOperationLifecycleService operations;

  /**
   * Commits ADMITTED, the physical LLM operation claim, and OPERATION_CLAIMED atomically. A crash
   * cannot expose an admitted row without its exact operation or an orphan operation without its
   * generation attempt.
   */
  @Transactional
  public ClaimedAdmission admitAndClaim(AttemptRequest request, ExecutionContext baseContext) {
    Admission admission = admitAttempt(request);
    if (!admission.admitted()) return new ClaimedAdmission(admission, null, null);
    String immutableIdentity =
        request.taskContractDigest() + "|generation-attempt=" + admission.attemptId();
    RunLlmOperationLifecycleService.PhaseLease operation =
        request.kind() == AdmissionKind.INITIAL
            ? operations.claimBuildTask(
                baseContext,
                request.runId(),
                request.taskId(),
                request.taskIndex(),
                immutableIdentity)
            : operations.beginBuildTaskAttempt(
                baseContext,
                request.runId(),
                request.taskId(),
                request.taskIndex(),
                immutableIdentity);
    LlmExecutionOperationCoordinator.LeaseIdentity identity = operations.identity(operation);
    int claimed =
        attempts.claimOperation(
            admission.attemptId(),
            identity.operationId(),
            identity.attempt(),
            identity.leaseToken(),
            WorkspaceGenerationState.ADMITTED,
            WorkspaceGenerationState.OPERATION_CLAIMED,
            Instant.now());
    if (claimed != 1) {
      throw new IllegalStateException("WORKSPACE_GENERATION_OPERATION_CLAIM_CAS_FAILED");
    }
    return new ClaimedAdmission(admission, operation, identity);
  }

  private static final Set<String> CONTINUATION_TASK_SOURCES =
      Set.of("PARTIAL", "FAILED", "COMPLETE", "COMPLETED_WITH_WARNING", "BLOCKED");

  /**
   * Atomically admits the durable generation row and, for a continuation, reopens its task. This is
   * the only BUILD entry point allowed to create an ADMITTED physical attempt.
   */
  @Transactional
  public Admission admitAttempt(AttemptRequest request) {
    if (request == null
        || request.runId() == null
        || request.runId().length() < 8
        || request.taskId() == null
        || request.taskId().isBlank()
        || request.taskIndex() < 0
        || request.workspaceRoot() == null
        || request.authorityDigest() == null
        || request.taskContractDigest() == null) {
      return Admission.refused("IDENTITY_INVALID");
    }
    PipelineRun run =
        runs.findByIdForTaskContinuation(request.runId())
            .orElseThrow(() -> new IllegalStateException("BUILD_ATTEMPT_RUN_MISSING"));
    String previousRunStatus = run.getStatus();
    BuildProtocolVersion protocolVersion =
        BuildProtocolVersion.parse(run.getBuildProtocolVersion());
    protocolVersion.requireExecutable();
    boolean alreadyWorking = "WORKING".equals(previousRunStatus);
    boolean correctingReview =
        request.kind() == AdmissionKind.AUTOMATIC_CONTINUATION
            && List.of("REVIEWING", "VALIDATING").contains(previousRunStatus);
    if (!alreadyWorking
        && !correctingReview
        && (request.kind() != AdmissionKind.AUTHORIZED_TERMINAL
            || !REOPENABLE_TERMINAL_RUNS.contains(previousRunStatus))) {
      return Admission.refused("RUN_NOT_REOPENABLE:" + previousRunStatus);
    }

    String taskKey = request.runId().substring(0, 8) + "_" + request.taskId();
    AgentTask task =
        tasks
            .findByIdForUpdate(taskKey)
            .orElseThrow(() -> new IllegalStateException("BUILD_ATTEMPT_TASK_MISSING"));
    if (request.expectedRecoveryAttempt() != null
        && !SequentialExecutor.recoveryAttemptMatches(
            request.expectedRecoveryAttempt(), task.getRecoveryAttempt())) {
      return Admission.refused("STALE_RECOVERY_ATTEMPT");
    }
    if ("CANCELLED".equals(task.getStatus())) return Admission.refused("TASK_CANCELLED");
    String previousTaskStatus = task.getStatus();
    boolean correction =
        request.kind() != AdmissionKind.INITIAL
            && run.getAcceptedGenerationAttemptId() != null
            && run.getAcceptedTaskIndex() != null
            && request.taskIndex() <= run.getAcceptedTaskIndex();
    var expectedFrontier = request.expectedFrontier();
    if (correction
        && (expectedFrontier == null
            || !java.util.Objects.equals(
                expectedFrontier.attemptId(), run.getAcceptedGenerationAttemptId())
            || expectedFrontier.taskIndex() != run.getAcceptedTaskIndex()
            || !java.util.Objects.equals(
                expectedFrontier.generation(), run.getAcceptedWorkspaceGeneration()))) {
      return Admission.refused("CORRECTION_CONTRACT_FRONTIER_CHANGED");
    }
    if (!correction && expectedFrontier != null)
      return Admission.refused("CORRECTION_FRONTIER_MISSING");
    if (correctingReview && !correction)
      return Admission.refused("REVIEW_CORRECTION_FRONTIER_MISSING");
    if (request.kind() == AdmissionKind.INITIAL) {
      if (!"PENDING".equals(previousTaskStatus)) {
        return Admission.refused("INITIAL_TASK_NOT_PENDING:" + previousTaskStatus);
      }
    } else if (request.kind() == AdmissionKind.RECOVERY) {
      if (!List.of("PENDING", "WORKING", "PARTIAL").contains(previousTaskStatus)) {
        return Admission.refused("RECOVERY_TASK_NOT_ADMISSIBLE:" + previousTaskStatus);
      }
    } else if (!CONTINUATION_TASK_SOURCES.contains(previousTaskStatus)) {
      return Admission.refused("CONTINUATION_TASK_NOT_TERMINAL:" + previousTaskStatus);
    }
    if (attempts.existsByRunIdAndTaskTransitionState(request.runId(), "PENDING")) {
      return Admission.refused("TASK_TRANSITION_PENDING");
    }

    int attemptNumber = attempts.maximumAttemptNumber(request.runId(), request.taskId()) + 1;
    Instant now = Instant.now();
    if (!alreadyWorking) {
      run.setStatus("WORKING");
      run.setCompletedAt(null);
      runs.saveAndFlush(run);
    }
    UUID attemptId = UUID.randomUUID();
    WorkspaceGenerationAttempt attempt =
        WorkspaceGenerationAttempt.builder()
            .id(attemptId)
            .runId(request.runId())
            .buildProtocolVersion(protocolVersion.name())
            .verificationCommandProtocolVersion((short) 2)
            .taskId(request.taskId())
            .taskIndex(request.taskIndex())
            .attemptNumber(attemptNumber)
            .attemptKind(
                correction
                    ? WorkspaceGenerationKind.CORRECTION
                    : WorkspaceGenerationKind.BUILD_TASK)
            .baseFrontierAttemptId(correction ? run.getAcceptedGenerationAttemptId() : null)
            .frontierTaskIndex(correction ? run.getAcceptedTaskIndex() : null)
            .baseFrontierGeneration(correction ? run.getAcceptedWorkspaceGeneration() : null)
            .state(WorkspaceGenerationState.ADMITTED)
            .workspaceRoot(request.workspaceRoot().toAbsolutePath().normalize().toString())
            .authorityDigest(request.authorityDigest())
            .taskContractDigest(request.taskContractDigest())
            .taskContractJson(request.taskContractJson())
            .initialFailureEvidenceJson(request.initialFailureEvidenceJson())
            .requestedProvider(request.requestedProvider())
            .requestedModel(request.requestedModel())
            .createdAt(now)
            .updatedAt(now)
            .build();
    attempts.saveAndFlush(attempt);

    if (request.kind() != AdmissionKind.INITIAL) {
      task.setStatus("PENDING");
      task.setStartedAt(null);
      task.setCompletedAt(null);
      task.setDurationMs(null);
      task.setLastActivityAt(now);
      tasks.saveAndFlush(task);
    }
    return new Admission(
        true, previousRunStatus, previousTaskStatus, null, attemptId, attemptNumber);
  }

  /** Records physical worker dispatch only after generation preparation and baseline admission. */
  @Transactional
  public com.fasterxml.jackson.databind.JsonNode recordDispatch(UUID attemptId) {
    WorkspaceGenerationAttempt attempt = attempts.findById(attemptId).orElseThrow();
    PipelineRun run = runs.findByIdForTaskContinuation(attempt.getRunId()).orElseThrow();
    if (!"WORKING".equals(run.getStatus())
        || attempt.getState() != WorkspaceGenerationState.MUTATING) {
      throw new IllegalStateException("CONTINUATION_DISPATCH_NOT_ADMITTED");
    }
    String key = attempt.getRunId().substring(0, 8) + "_" + attempt.getTaskId();
    AgentTask task = tasks.findByIdForUpdate(key).orElseThrow();
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    try {
      var actions =
          mapper.readTree(
              task.getContinuationActionsJson() == null ? "[]" : task.getContinuationActionsJson());
      if (!actions.isArray()) throw new IllegalStateException("CONTINUATION_HISTORY_MALFORMED");
      for (int i = actions.size() - 1; i >= 0; i--) {
        var action = actions.get(i);
        if (!"RESERVED".equals(action.path("status").asText())) continue;
        if (!action.path("provider").asText().equalsIgnoreCase(attempt.getRequestedProvider())
            || !action.path("model").asText().equals(attempt.getRequestedModel())) continue;
        var dispatched = (com.fasterxml.jackson.databind.node.ObjectNode) action;
        dispatched.put("status", "EXECUTED");
        dispatched.put("physicalAttemptId", attemptId.toString());
        task.setContinuationActionsJson(mapper.writeValueAsString(actions));
        tasks.saveAndFlush(task);
        return dispatched;
      }
      return null; // Explicit approved retries already carry their separate APPROVED receipt.
    } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
      throw new IllegalStateException("CONTINUATION_HISTORY_MALFORMED", malformed);
    }
  }

  public record Admission(
      boolean admitted,
      String previousRunStatus,
      String previousTaskStatus,
      String refusal,
      UUID attemptId,
      int attemptNumber) {
    static Admission refused(String reason) {
      return new Admission(false, null, null, reason, null, 0);
    }
  }

  public enum AdmissionKind {
    INITIAL,
    AUTOMATIC_CONTINUATION,
    AUTHORIZED_TERMINAL,
    RECOVERY
  }

  public record AttemptRequest(
      String runId,
      String taskId,
      int taskIndex,
      java.nio.file.Path workspaceRoot,
      String authorityDigest,
      String taskContractDigest,
      String taskContractJson,
      String initialFailureEvidenceJson,
      String requestedProvider,
      String requestedModel,
      Integer expectedRecoveryAttempt,
      AdmissionKind kind,
      com.navneet.executespec.workspacegeneration.AcceptedWorkspaceFrontier expectedFrontier) {
    public AttemptRequest(
        String runId,
        String taskId,
        int taskIndex,
        java.nio.file.Path workspaceRoot,
        String authorityDigest,
        String taskContractDigest,
        String taskContractJson,
        String initialFailureEvidenceJson,
        String requestedProvider,
        String requestedModel,
        Integer expectedRecoveryAttempt,
        AdmissionKind kind) {
      this(
          runId,
          taskId,
          taskIndex,
          workspaceRoot,
          authorityDigest,
          taskContractDigest,
          taskContractJson,
          initialFailureEvidenceJson,
          requestedProvider,
          requestedModel,
          expectedRecoveryAttempt,
          kind,
          null);
    }

    public AttemptRequest {
      if (kind == null) kind = AdmissionKind.INITIAL;
    }
  }

  public record ClaimedAdmission(
      Admission admission,
      RunLlmOperationLifecycleService.PhaseLease operation,
      LlmExecutionOperationCoordinator.LeaseIdentity identity) {}
}
