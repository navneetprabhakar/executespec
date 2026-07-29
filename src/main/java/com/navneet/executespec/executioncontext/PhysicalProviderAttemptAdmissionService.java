package com.navneet.executespec.executioncontext;

import java.time.Duration;
import java.time.Instant;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.RunKeyContext;
import com.navneet.executespec.cost.LlmUsageAttemptIntent;
import com.navneet.executespec.cost.RunStepUsageRecorder;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Shared provider-inert coordinator for one physical chat or capability transport attempt.
 *
 * <p>Every path reauthorizes current durable policy and credentials without the legacy logical-call
 * reservation, drafts paired usage identity, then delegates the only budget/lease/intent mutation
 * to {@link LlmExecutionOperationAdmissionService}. No legacy or context-free fallback belongs
 * here.
 */
@Service
@RequiredArgsConstructor
public class PhysicalProviderAttemptAdmissionService {

  private static final int MAX_RUN_ID_CHARS = 96;
  private static final int MAX_TASK_ID_CHARS = 160;
  private static final int MAX_AGENT_ROLE_CHARS = 80;
  private static final int MAX_STEP_ID_CHARS = 120;
  private static final int MAX_PROVIDER_CHARS = 32;
  private static final int MAX_MODEL_CHARS = 128;

  private final LlmExecutionDispatchGuard dispatchGuard;
  private final RunStepUsageRecorder usageRecorder;
  private final LlmExecutionOperationAdmissionService operationAdmission;

  /**
   * Reauthorizes and atomically admits one physical chat-provider attempt.
   *
   * <p>The request must carry the exact durable reference and the process-local authority issued
   * for that reference. Missing or mismatched authority fails before credential resolution,
   * attribution drafting, or durable admission.
   */
  public AdmittedPhysicalAttempt admitChatAttempt(
      LlmRequest request,
      int attemptNumber,
      String attemptKind,
      String parentAttemptId,
      Duration transportTimeout,
      Duration safetyMargin) {
    if (request == null) throw new IllegalArgumentException("request required");
    LlmExecutionReference reference = referenceFrom(request);
    LlmExecutionOperationAuthority authority =
        requireAuthority(request.llmExecutionOperationAuthority());
    requireAuthorityReference(reference, authority);
    requirePositiveAttempt(attemptNumber);

    LlmExecutionDispatchGuard.ResolvedDispatch dispatch = dispatchGuard.reauthorize(request);
    RunStepUsageRecorder.AttemptDraft draft =
        usageRecorder.draftAttempt(
            request,
            request.provider(),
            request.model(),
            dispatch.keySource().name(),
            attemptNumber,
            attemptKind,
            parentAttemptId);
    LlmExecutionOperationAdmissionService.AdmittedAttempt admitted =
        operationAdmission.admit(
            reference,
            authority,
            draft.intent(),
            request.maxTokens(),
            transportTimeout,
            safetyMargin);
    return result(draft, admitted, dispatch);
  }

  /**
   * Reauthorizes and atomically admits one governed non-chat physical provider attempt.
   *
   * <p>{@code requestedMaxOutputTokens} is mandatory: zero is the exact cap for transports such as
   * embedding batches, while output-producing capabilities must supply their explicit positive
   * transport cap.
   */
  public AdmittedPhysicalAttempt admitCapabilityAttempt(CapabilityAttempt request) {
    if (request == null) throw new IllegalArgumentException("request required");
    LlmExecutionReference reference = requireReference(request.reference());
    LlmExecutionOperationAuthority authority = requireAuthority(request.authority());
    requireAuthorityReference(reference, authority);
    validateCapability(request);

    LlmExecutionDispatchGuard.ResolvedDispatch dispatch =
        dispatchGuard.reauthorizeProviderCapability(reference, request.provider());
    RunStepUsageRecorder.AttemptDraft draft =
        usageRecorder.draftCapabilityAttempt(
            reference,
            request.runId(),
            request.taskId(),
            request.agentRole(),
            request.stepId(),
            request.provider(),
            request.model(),
            dispatch.keySource().name(),
            request.attemptNumber());
    LlmExecutionOperationAdmissionService.AdmittedAttempt admitted =
        operationAdmission.admit(
            reference,
            authority,
            draft.intent(),
            request.requestedMaxOutputTokens(),
            request.transportTimeout(),
            request.safetyMargin());
    return result(draft, admitted, dispatch);
  }

  private static AdmittedPhysicalAttempt result(
      RunStepUsageRecorder.AttemptDraft draft,
      LlmExecutionOperationAdmissionService.AdmittedAttempt admitted,
      LlmExecutionDispatchGuard.ResolvedDispatch dispatch) {
    if (!draft.handle().physicalAttemptId().equals(admitted.intent().getPhysicalAttemptId())
        || !draft.handle().logicalCallId().equals(admitted.intent().getLogicalCallId())
        || draft.handle().attemptOrdinal() != admitted.intent().getAttemptOrdinal()) {
      throw new IllegalStateException(
          "admitted intent no longer matches its physical attempt handle");
    }
    return new AdmittedPhysicalAttempt(
        draft.handle(),
        admitted.intent(),
        dispatch.apiKey(),
        dispatch.keySource(),
        admitted.transportDeadline());
  }

  private static LlmExecutionReference referenceFrom(LlmRequest request) {
    if (request.llmExecutionContextId() == null || request.llmExecutionScope() == null) {
      throw new IllegalArgumentException("exact durable execution reference required");
    }
    return new LlmExecutionReference(request.llmExecutionContextId(), request.llmExecutionScope());
  }

  private static LlmExecutionReference requireReference(LlmExecutionReference reference) {
    if (reference == null) {
      throw new IllegalArgumentException("exact durable execution reference required");
    }
    return reference;
  }

  private static LlmExecutionOperationAuthority requireAuthority(
      LlmExecutionOperationAuthority authority) {
    if (authority == null) {
      throw new IllegalArgumentException("operation authority required");
    }
    return authority;
  }

  private static void requireAuthorityReference(
      LlmExecutionReference reference, LlmExecutionOperationAuthority authority) {
    if (!authority.claim().context().equals(reference)) {
      throw new IllegalArgumentException(
          "operation authority belongs to a different durable execution reference");
    }
  }

  private static void validateCapability(CapabilityAttempt request) {
    requireBoundedText(request.runId(), "runId", MAX_RUN_ID_CHARS);
    requireOptionalBoundedText(request.taskId(), "taskId", MAX_TASK_ID_CHARS);
    requireBoundedText(request.agentRole(), "agentRole", MAX_AGENT_ROLE_CHARS);
    requireOptionalBoundedText(request.stepId(), "stepId", MAX_STEP_ID_CHARS);
    requireBoundedText(request.provider(), "provider", MAX_PROVIDER_CHARS);
    requireBoundedText(request.model(), "model", MAX_MODEL_CHARS);
    requirePositiveAttempt(request.attemptNumber());
    if (request.requestedMaxOutputTokens() == null || request.requestedMaxOutputTokens() < 0) {
      throw new IllegalArgumentException(
          "requestedMaxOutputTokens must be an explicit non-negative cap");
    }
    if (request.transportTimeout() == null) {
      throw new IllegalArgumentException("transportTimeout required");
    }
    if (request.safetyMargin() == null) {
      throw new IllegalArgumentException("safetyMargin required");
    }
  }

  private static void requirePositiveAttempt(int attemptNumber) {
    if (attemptNumber <= 0) throw new IllegalArgumentException("attemptNumber must be positive");
  }

  private static void requireBoundedText(String value, String field, int maxChars) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " required");
    }
    if (value.trim().length() > maxChars) {
      throw new IllegalArgumentException(field + " exceeds " + maxChars + " characters");
    }
  }

  private static void requireOptionalBoundedText(String value, String field, int maxChars) {
    if (value != null && !value.isBlank() && value.trim().length() > maxChars) {
      throw new IllegalArgumentException(field + " exceeds " + maxChars + " characters");
    }
  }

  /** Bounded attribution and exact transport limits for a governed capability attempt. */
  public record CapabilityAttempt(
      LlmExecutionReference reference,
      LlmExecutionOperationAuthority authority,
      String runId,
      String taskId,
      String agentRole,
      String stepId,
      String provider,
      String model,
      int attemptNumber,
      Integer requestedMaxOutputTokens,
      Duration transportTimeout,
      Duration safetyMargin) {}

  /** Provider-ready identity, fresh credential and database-derived deadline. */
  public record AdmittedPhysicalAttempt(
      RunStepUsageRecorder.AttemptHandle handle,
      LlmUsageAttemptIntent intent,
      String apiKey,
      RunKeyContext.Source keySource,
      Instant transportDeadline) {
    public AdmittedPhysicalAttempt {
      if (handle == null) throw new IllegalArgumentException("handle required");
      if (intent == null) throw new IllegalArgumentException("intent required");
      if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("apiKey required");
      if (keySource == null) throw new IllegalArgumentException("keySource required");
      if (transportDeadline == null) {
        throw new IllegalArgumentException("transportDeadline required");
      }
    }
  }
}
