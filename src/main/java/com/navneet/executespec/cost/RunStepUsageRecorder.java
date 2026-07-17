package com.navneet.executespec.cost;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.observability.Metrics;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Writes one durable row for every physical provider attempt, including failures. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunStepUsageRecorder {

  private final RunStepUsageRepository repository;
  private final LlmUsageAttributionResolver attributionResolver;
  private final Metrics metrics;

  /** Backward-compatible success API for older tests/callers. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(
      String runId, String taskId, String provider, String model, RunCostTracker.CallUsage usage) {
    record(runId, taskId, null, null, null, provider, model, usage);
  }

  /** Backward-compatible success API for older tests/callers. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(
      String runId,
      String taskId,
      String agentRole,
      String stepId,
      String retryOf,
      String provider,
      String model,
      RunCostTracker.CallUsage usage) {
    if (runId == null || runId.isBlank() || usage == null) return;
    persist(
        null, runId, taskId, agentRole, stepId, retryOf, provider, model, "UNKNOWN", 1, 0, usage,
        null, null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordSuccess(
      LlmRequest request,
      String provider,
      String model,
      String keySource,
      int attemptNumber,
      long latencyMs,
      RunCostTracker.CallUsage usage,
      ChatResponse response) {
    if (request == null || usage == null) return;
    persist(
        request,
        request.context().runId(),
        request.context().taskId(),
        request.agentRole(),
        request.stepId(),
        retryOf(request, attemptNumber),
        provider,
        model,
        keySource,
        attemptNumber,
        latencyMs,
        usage,
        response,
        null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(
      LlmRequest request,
      String provider,
      String model,
      String keySource,
      int attemptNumber,
      long latencyMs,
      Throwable error) {
    if (request == null) return;
    if (error == null) error = new IllegalStateException("provider failure detail unavailable");
    persist(
        request,
        request.context().runId(),
        request.context().taskId(),
        request.agentRole(),
        request.stepId(),
        retryOf(request, attemptNumber),
        provider,
        model,
        keySource,
        attemptNumber,
        latencyMs,
        null,
        null,
        error);
  }

  /** Records a successful physical attempt for a governed non-chat provider capability. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordProviderCapabilitySuccess(
      LlmExecutionReference reference,
      String runId,
      String agentRole,
      String stepId,
      String provider,
      String model,
      String keySource,
      int attemptNumber,
      long latencyMs,
      RunCostTracker.CallUsage usage) {
    persist(
        null,
        runId,
        agentRole,
        agentRole,
        stepId,
        null,
        provider,
        model,
        keySource,
        attemptNumber,
        latencyMs,
        usage,
        null,
        null,
        attributionResolver.resolve(reference));
  }

  /** Records a failed physical attempt for a governed non-chat provider capability. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordProviderCapabilityFailure(
      LlmExecutionReference reference,
      String runId,
      String agentRole,
      String stepId,
      String provider,
      String model,
      String keySource,
      int attemptNumber,
      long latencyMs,
      Throwable error) {
    persist(
        null,
        runId,
        agentRole,
        agentRole,
        stepId,
        null,
        provider,
        model,
        keySource,
        attemptNumber,
        latencyMs,
        null,
        null,
        error,
        attributionResolver.resolve(reference));
  }

  private void persist(
      LlmRequest request,
      String runId,
      String taskId,
      String agentRole,
      String stepId,
      String retryOf,
      String provider,
      String model,
      String keySource,
      int attemptNumber,
      long latencyMs,
      RunCostTracker.CallUsage usage,
      ChatResponse response,
      Throwable error) {
    persist(
        request,
        runId,
        taskId,
        agentRole,
        stepId,
        retryOf,
        provider,
        model,
        keySource,
        attemptNumber,
        latencyMs,
        usage,
        response,
        error,
        null);
  }

  private void persist(
      LlmRequest request,
      String runId,
      String taskId,
      String agentRole,
      String stepId,
      String retryOf,
      String provider,
      String model,
      String keySource,
      int attemptNumber,
      long latencyMs,
      RunCostTracker.CallUsage usage,
      ChatResponse response,
      Throwable error,
      LlmUsageAttributionResolver.Attribution explicitAttribution) {
    if (runId == null || runId.isBlank()) return;
    String status = error == null ? "SUCCESS" : "FAILED";
    try {
      LlmUsageAttributionResolver.Attribution attribution =
          explicitAttribution != null
              ? explicitAttribution
              : request == null
                  ? LlmUsageAttributionResolver.Attribution.unattributed(runId)
                  : attributionResolver.resolve(request);
      String attributionResult = attributionResult(attribution);
      metrics.recordLlmUsageAttribution(attributionResult);
      if ("invalid_context".equals(attributionResult) || "unattributed".equals(attributionResult)) {
        log.warn(
            "LLM attempt usage attribution {} run={} sourceType={} sourceId={}",
            attributionResult,
            runId,
            attribution.sourceType(),
            attribution.sourceId());
      }
      repository.save(
          RunStepUsage.builder()
              .id(UUID.randomUUID().toString())
              .runId(runId)
              .taskId(blankToNull(taskId))
              .agentRole(firstNonBlank(agentRole, "UNKNOWN"))
              .stepId(blankToNull(stepId))
              .retryOf(blankToNull(retryOf))
              .llmExecutionContextId(attribution.llmExecutionContextId())
              .orgId(attribution.orgId())
              .userId(attribution.userId())
              .projectId(attribution.projectId())
              .repoId(attribution.repoId())
              .feature(attribution.feature())
              .sourceType(attribution.sourceType())
              .sourceId(firstNonBlank(attribution.sourceId(), runId))
              .keySource(firstNonBlank(keySource, "UNKNOWN").toUpperCase(Locale.ROOT))
              .attemptStatus(status)
              .errorCode(errorCode(error))
              .errorRedacted(redact(error))
              .attemptNumber(Math.max(1, attemptNumber))
              .latencyMs(Math.max(0, latencyMs))
              .provider(
                  firstNonBlank(
                      firstNonBlank(provider, usage == null ? null : usage.provider()), "unknown"))
              .model(
                  firstNonBlank(
                      firstNonBlank(model, usage == null ? null : usage.model()), "unknown"))
              .inputTokens(usage == null ? 0 : usage.inputTokens())
              .outputTokens(usage == null ? 0 : usage.outputTokens())
              .cacheWriteTokens(usage == null ? 0 : usage.cacheWriteTokens())
              .cacheReadTokens(usage == null ? 0 : usage.cacheReadTokens())
              .reasoningTokens(usage == null ? 0 : usage.reasoningTokens())
              .costUsd(usage == null ? 0 : usage.costUsd())
              .callIndex(usage == null ? 0 : usage.callIndex())
              .providerUsageRawJson(safeUsageMetadata(response, usage))
              .createdAt(Instant.now())
              .build());
      metrics.recordLlmUsagePersistence(status, "ok");
    } catch (Exception persistenceFailure) {
      metrics.recordLlmUsagePersistence(status, "failed");
      log.warn(
          "Canonical LLM attempt usage write FAILED run={} task={} provider={} status={}: {}",
          runId,
          taskId,
          provider,
          status,
          persistenceFailure.getMessage());
    }
  }

  private static Map<String, Object> safeUsageMetadata(
      ChatResponse response, RunCostTracker.CallUsage usage) {
    if (response == null || response.getMetadata() == null) return null;
    Map<String, Object> safe = new LinkedHashMap<>();
    putNumber(
        safe, "cache_read_input_tokens", response.getMetadata().get("cache_read_input_tokens"));
    putNumber(
        safe,
        "cache_creation_input_tokens",
        response.getMetadata().get("cache_creation_input_tokens"));
    putNumber(safe, "thinking_tokens", response.getMetadata().get("thinking_tokens"));
    putNumber(safe, "reasoning_tokens", response.getMetadata().get("reasoning_tokens"));
    putNumber(
        safe,
        "provider_reported_cost_usd",
        response.getMetadata().get("provider_reported_cost_usd"));
    putNumber(
        safe,
        "provider_upstream_cost_usd",
        response.getMetadata().get("provider_upstream_cost_usd"));
    if (usage != null) safe.put("accounted_cost_usd", usage.costUsd());
    return safe.isEmpty() ? null : Map.copyOf(safe);
  }

  private static void putNumber(Map<String, Object> target, String key, Object value) {
    if (value instanceof Number number) target.put(key, number);
  }

  private static String retryOf(LlmRequest request, int attempt) {
    if (request == null) return null;
    if (attempt <= 1) return request.retryOf();
    String role = firstNonBlank(request.agentRole(), "unknown");
    String step = firstNonBlank(request.stepId(), "llm.call");
    return role + ":" + step + "#attempt-" + (attempt - 1);
  }

  private static String errorCode(Throwable error) {
    if (error == null) return null;
    Throwable root = error;
    while (root.getCause() != null) root = root.getCause();
    String simple = root.getClass().getSimpleName();
    String normalized = simple.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_.-]", "_");
    return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
  }

  private static String attributionResult(LlmUsageAttributionResolver.Attribution attribution) {
    if (attribution.llmExecutionContextId() != null) return "durable_context";
    if ("INVALID_EXECUTION_CONTEXT".equals(attribution.sourceType())) return "invalid_context";
    if (attribution.orgId() != null && "PIPELINE_RUN".equals(attribution.sourceType())) {
      return "pipeline_run";
    }
    if (attribution.orgId() != null && "AGENT_SESSION".equals(attribution.sourceType())) {
      return "agent_session";
    }
    return "unattributed";
  }

  private static String redact(Throwable error) {
    if (error == null) return null;
    // Provider exceptions can echo request bodies (including prompts and tool arguments). Persist
    // a bounded classification, never the arbitrary exception text.
    String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
    String category;
    if (message.contains("429") || message.contains("rate limit")) category = "RATE_LIMITED";
    else if (message.contains("401") || message.contains("unauthorized")) category = "HTTP_401";
    else if (message.contains("403") || message.contains("forbidden")) category = "HTTP_403";
    else if (message.contains("400") || message.contains("bad request")) category = "HTTP_400";
    else if (message.contains("500")) category = "HTTP_500";
    else if (message.contains("502") || message.contains("bad gateway")) category = "HTTP_502";
    else if (message.contains("503") || message.contains("unavailable")) category = "HTTP_503";
    else if (message.contains("504") || message.contains("gateway timeout")) category = "HTTP_504";
    else if (message.contains("context length") || message.contains("context window")) {
      category = "CONTEXT_LIMIT";
    } else if (message.contains("timed out") || message.contains("timeout")) category = "TIMEOUT";
    else if (message.contains("connection reset")) category = "CONNECTION_RESET";
    else if (message.contains("cancel") || message.contains("interrupt")) category = "CANCELLED";
    else category = "PROVIDER_ERROR_DETAIL_OMITTED";
    return error.getClass().getSimpleName() + ":" + category;
  }

  private static String firstNonBlank(String primary, String fallback) {
    return primary == null || primary.isBlank() ? blankToNull(fallback) : primary;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
