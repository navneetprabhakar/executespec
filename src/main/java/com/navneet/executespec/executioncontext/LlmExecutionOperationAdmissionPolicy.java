package com.navneet.executespec.executioncontext;

import com.navneet.executespec.agent.LlmRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deterministic rollout decision for operation-fenced physical LLM-attempt admission.
 *
 * <p>This policy grants no provider authority by itself. Route, credential, durable-context and
 * operation-claim validation remain mandatory at their canonical boundaries. It only tells a caller
 * whether the current rollout mode permits legacy version-0 preparation, requires atomic version-1
 * admission, or must reject before transport.
 */
@Component
public final class LlmExecutionOperationAdmissionPolicy {

  private final LlmExecutionOperationAdmissionMode mode;

  /**
   * Defaults to LEGACY in code so adding the policy bean cannot activate operation admission.
   * Invalid configured values fail application startup instead of silently weakening enforcement.
   */
  public LlmExecutionOperationAdmissionPolicy(
      @Value("${executespec.llm.operation-admission.mode:LEGACY}") String configuredMode) {
    this.mode = LlmExecutionOperationAdmissionMode.parse(configuredMode);
  }

  public LlmExecutionOperationAdmissionMode mode() {
    return mode;
  }

  /** Decide for a provider-neutral chat request without inspecting or serializing its authority. */
  public Decision decide(LlmRequest request) {
    if (request == null) throw new IllegalArgumentException("request required");
    LlmExecutionReference reference =
        request.llmExecutionContextId() == null
            ? null
            : new LlmExecutionReference(
                request.llmExecutionContextId(), request.llmExecutionScope());
    return decide(reference, request.llmExecutionOperationAuthority());
  }

  /** Decide for a governed non-chat capability or another caller carrying an explicit reference. */
  public Decision decide(
      LlmExecutionReference reference, LlmExecutionOperationAuthority authority) {
    if (reference == null && authority != null) {
      return Decision.reject(Reason.AUTHORITY_WITHOUT_DURABLE_REFERENCE);
    }
    if (reference == null) {
      return switch (mode) {
        case LEGACY -> Decision.legacy(Reason.CONTEXT_FREE_LEGACY_COMPATIBILITY, false);
        case SHADOW -> Decision.legacy(Reason.CONTEXT_FREE_SHADOW_COMPATIBILITY, true);
        case ENFORCED -> Decision.reject(Reason.CONTEXT_FREE_REJECTED);
      };
    }
    return switch (mode) {
      case LEGACY -> Decision.legacy(Reason.LEGACY_MODE, false);
      case SHADOW ->
          authority == null
              ? Decision.legacy(Reason.MISSING_AUTHORITY_SHADOW_FALLBACK, true)
              : Decision.atomic();
      case ENFORCED ->
          authority == null
              ? Decision.reject(Reason.MISSING_AUTHORITY_ENFORCED)
              : Decision.atomic();
    };
  }

  public enum Action {
    USE_ATOMIC_V1,
    ALLOW_LEGACY_V0,
    REJECT_BEFORE_TRANSPORT
  }

  /** Stable bounded reasons suitable for metrics labels and redacted diagnostics. */
  public enum Reason {
    AUTHORITY_PRESENT,
    LEGACY_MODE,
    MISSING_AUTHORITY_SHADOW_FALLBACK,
    MISSING_AUTHORITY_ENFORCED,
    AUTHORITY_WITHOUT_DURABLE_REFERENCE,
    CONTEXT_FREE_LEGACY_COMPATIBILITY,
    CONTEXT_FREE_SHADOW_COMPATIBILITY,
    CONTEXT_FREE_REJECTED
  }

  public record Decision(Action action, Reason reason, boolean diagnosticRequired) {
    public Decision {
      if (action == null) throw new IllegalArgumentException("action required");
      if (reason == null) throw new IllegalArgumentException("reason required");
      if (action == Action.REJECT_BEFORE_TRANSPORT && diagnosticRequired) {
        throw new IllegalArgumentException("rejection is not a legacy-fallback diagnostic");
      }
    }

    static Decision atomic() {
      return new Decision(Action.USE_ATOMIC_V1, Reason.AUTHORITY_PRESENT, false);
    }

    static Decision legacy(Reason reason, boolean diagnosticRequired) {
      return new Decision(Action.ALLOW_LEGACY_V0, reason, diagnosticRequired);
    }

    static Decision reject(Reason reason) {
      return new Decision(Action.REJECT_BEFORE_TRANSPORT, reason, false);
    }

    public boolean usesAtomicV1() {
      return action == Action.USE_ATOMIC_V1;
    }

    public boolean allowsLegacyV0() {
      return action == Action.ALLOW_LEGACY_V0;
    }

    public boolean rejectsBeforeTransport() {
      return action == Action.REJECT_BEFORE_TRANSPORT;
    }
  }
}
