package com.navneet.executespec.agentsession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.navneet.executespec.cost.ProjectedCallBudgetExceededException;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.executioncontext.LlmDispatchPolicyException;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.specchat.SpecChatService;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Publishes one durable and streamed terminal failure for an agent turn. */
final class AgentStreamFailurePublisher {

  private final AgentSessionService sessionService;
  private final AgentEventRecorder eventRecorder;
  private final AgentTurnOperationLifecycle operationLifecycle;

  AgentStreamFailurePublisher(
      AgentSessionService sessionService,
      AgentEventRecorder eventRecorder,
      AgentTurnOperationLifecycle operationLifecycle) {
    this.sessionService = sessionService;
    this.eventRecorder = eventRecorder;
    this.operationLifecycle = operationLifecycle;
  }

  void publish(
      SseEmitter emitter,
      AgentSessionService.StreamTurnContext ctx,
      Throwable error,
      RunCostTracker.Snapshot latest,
      RunCostTracker.Snapshot turnDelta,
      SpecChatService.DurableTurnOperation operation,
      boolean operationCompleted) {
    StreamErrorProjection streamError = project(error, ctx.provider(), latest);
    String message = streamError.message();
    sessionService.failStreamTurn(ctx, message, turnDelta);
    operationLifecycle.fail(operation, operationCompleted, message);
    Map<String, Object> errorPayload = streamError.payload(ctx.sessionId(), ctx.turnId());
    if (eventRecorder != null) {
      eventRecorder.append(ctx.sessionId(), ctx.turnId(), "TURN_ERROR", errorPayload);
    }
    try {
      send(emitter, "error", errorPayload);
    } catch (Exception ignored) {
      // emitter is already broken
    }
    // The response is already committed as text/event-stream. Completing with an exception
    // re-enters Spring's JSON exception handlers and produces a second broken response.
    emitter.complete();
  }

  private static StreamErrorProjection project(
      Throwable error, String provider, RunCostTracker.Snapshot latest) {
    ProjectedCallBudgetExceededException projectedBudget =
        findCause(error, ProjectedCallBudgetExceededException.class);
    if (projectedBudget != null) {
      return new StreamErrorProjection(
          "PROJECTED_CALL_BUDGET_EXCEEDED",
          "ACTION_REQUIRED",
          "The next model call would exceed the configured cost cap, so no provider request was"
              + " sent.",
          true,
          "Raise the run cap or choose a cheaper permitted route before retrying.",
          projectedBudget.spentUsd(),
          projectedBudget.capUsd(),
          projectedBudget.projectedCallUsd());
    }
    LlmDispatchPolicyException policyFailure = findPolicyFailure(error);
    if (policyFailure != null
        && policyFailure.reason() == LlmDispatchPolicyException.Reason.BUDGET_EXHAUSTED) {
      return new StreamErrorProjection(
          "AGENT_TURN_BUDGET_EXHAUSTED",
          "ACTION_REQUIRED",
          "This agent turn reached its AI execution limit before a response was produced.",
          true,
          "Start a new agent turn; if the first turn also stops, contact support.",
          latest == null ? null : latest.costUsd(),
          null,
          null);
    }
    String raw = error == null ? "" : error.getMessage();
    if (raw == null || raw.isBlank()) {
      return platformStreamFailure("Agent stream failed.");
    }
    if (!looksLikeProviderCredentialFailure(raw)) {
      return platformStreamFailure(new ContextSanitizer().sanitizeException(error));
    }
    String providerLabel =
        provider == null || provider.isBlank() ? "Selected provider" : provider.trim();
    return new StreamErrorProjection(
        "PROVIDER_CREDENTIALS_INVALID",
        "ACTION_REQUIRED",
        providerLabel + " authentication failed.",
        true,
        "Check Provider keys, switch to a configured provider, or use managed AI credits before"
            + " retrying.",
        latest == null ? null : latest.costUsd(),
        null,
        null);
  }

  private static StreamErrorProjection platformStreamFailure(String message) {
    return new StreamErrorProjection(
        "AGENT_STREAM_FAILED",
        "PLATFORM_FAILURE",
        message,
        true,
        "Retry once, then contact support if the problem continues.",
        null,
        null,
        null);
  }

  private static LlmDispatchPolicyException findPolicyFailure(Throwable error) {
    return findCause(error, LlmDispatchPolicyException.class);
  }

  private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
    Throwable current = error;
    for (int depth = 0; current != null && depth < 8; depth++) {
      if (type.isInstance(current)) return type.cast(current);
      current = current.getCause();
    }
    return null;
  }

  private static boolean looksLikeProviderCredentialFailure(String raw) {
    String normalized = raw.toLowerCase(Locale.ROOT);
    return normalized.contains("invalid x-api-key")
        || normalized.contains("invalid api key")
        || normalized.contains("api key")
        || normalized.contains("unauthorized")
        || normalized.contains("authentication")
        || normalized.contains("401");
  }

  private static void send(SseEmitter emitter, String event, Object data) {
    try {
      emitter.send(SseEmitter.event().name(event).data(data));
    } catch (IOException io) {
      throw new UncheckedIOException(io);
    }
  }

  private record StreamErrorProjection(
      String code,
      String category,
      String message,
      boolean retryable,
      String nextAction,
      Double actualCostUsd,
      Double capUsd,
      Double projectedCallUsd) {

    Map<String, Object> payload(String sessionId, String turnId) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("session_id", sessionId);
      payload.put("turn_id", turnId);
      payload.put("code", code);
      payload.put("category", category);
      payload.put("message", message);
      payload.put("retryable", retryable);
      payload.put("next_action", nextAction);
      if (actualCostUsd != null) payload.put("actual_cost_usd", actualCostUsd);
      if (capUsd != null) payload.put("cap_usd", capUsd);
      if (projectedCallUsd != null) payload.put("projected_call_usd", projectedCallUsd);
      return Collections.unmodifiableMap(payload);
    }
  }
}
