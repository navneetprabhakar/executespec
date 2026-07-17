package com.navneet.executespec.cost;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.router.ModelRegistry;

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
  private final LlmUsageAttemptIntentRepository intentRepository;
  private final LlmUsageAttributionResolver attributionResolver;
  private final Metrics metrics;
  private final ModelRegistry modelRegistry;
  private final LlmUsageRecoverySpool recoverySpool;

  /**
   * Persists the immutable attempt intent before any provider transport is invoked. Failure is
   * deliberately fail-closed: an Enterprise provider call must not occur without durable audit
   * identity.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AttemptHandle prepareAttempt(
      LlmRequest request, String provider, String model, String keySource, int attemptNumber) {
    return prepareAttempt(request, provider, model, keySource, attemptNumber, null, null);
  }

  /**
   * Write-ahead intent for a governed non-chat capability such as an embedding HTTP request.
   * Capability callers must retain the returned handle and use the matching terminal overload.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AttemptHandle prepareProviderCapabilityAttempt(
      LlmExecutionReference reference,
      String runId,
      String taskId,
      String agentRole,
      String stepId,
      String provider,
      String model,
      String keySource,
      int attemptNumber) {
    if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId required");
    var attribution = attributionResolver.resolve(reference);
    AttemptHandle handle =
        handleFor(null, provider, model, keySource, attemptNumber, "CAPABILITY", null, attribution);
    LlmUsageAttemptIntent intent =
        LlmUsageAttemptIntent.builder()
            .physicalAttemptId(handle.physicalAttemptId())
            .logicalCallId(handle.logicalCallId())
            .parentAttemptId(handle.parentAttemptId())
            .attemptKind(handle.attemptKind())
            .attemptOrdinal(handle.attemptOrdinal())
            .llmExecutionContextId(attribution.llmExecutionContextId())
            .correlationId(firstNonBlank(attribution.correlationId(), runId))
            .orgId(attribution.orgId())
            .userId(attribution.userId())
            .projectId(attribution.projectId())
            .feature(attribution.feature())
            .sourceType(attribution.sourceType())
            .sourceId(firstNonBlank(attribution.sourceId(), runId))
            .runId(runId)
            .taskId(blankToNull(taskId))
            .agentRole(firstNonBlank(agentRole, "UNKNOWN"))
            .stepId(blankToNull(stepId))
            .provider(handle.provider())
            .model(handle.model())
            .modelTier(handle.modelTier())
            .keySource(handle.keySource())
            .startedAt(Instant.now())
            .build();
    try {
      intentRepository.save(intent);
      metrics.recordLlmUsagePersistence("STARTED", "ok");
      return handle;
    } catch (RuntimeException persistenceFailure) {
      metrics.recordLlmUsagePersistence("STARTED", "failed");
      throw new LlmUsagePersistenceException(
          "Provider capability blocked because usage intent could not be persisted",
          persistenceFailure);
    }
  }

  /**
   * Persists a write-ahead intent for a provider client that performs multiple physical HTTP
   * operations inside one router call (for example Gemini cache mint + generate retries). Attempt
   * ordinals are supplied by the caller's single logical-call counter and the explicit parent links
   * the resulting append-only chain.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AttemptHandle prepareAttempt(
      LlmRequest request,
      String provider,
      String model,
      String keySource,
      int attemptNumber,
      String attemptKind,
      String parentAttemptId) {
    if (request == null) throw new IllegalArgumentException("request required");
    AttemptHandle handle =
        handleFor(
            request,
            provider,
            model,
            keySource,
            attemptNumber,
            attemptKind,
            parentAttemptId,
            attributionResolver.resolve(request));
    try {
      intentRepository.save(handle.toIntent(request));
      metrics.recordLlmUsagePersistence("STARTED", "ok");
      return handle;
    } catch (RuntimeException persistenceFailure) {
      metrics.recordLlmUsagePersistence("STARTED", "failed");
      log.error(
          "LLM attempt intent write FAILED run={} task={} provider={}; provider call blocked",
          request.context().runId(),
          request.context().taskId(),
          provider,
          persistenceFailure);
      throw new LlmUsagePersistenceException(
          "Provider call blocked because usage intent could not be persisted", persistenceFailure);
    }
  }

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
  public String recordSuccess(
      LlmRequest request,
      String provider,
      String model,
      String keySource,
      int attemptNumber,
      long latencyMs,
      RunCostTracker.CallUsage usage,
      ChatResponse response) {
    if (request == null || usage == null) return null;
    return persist(
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
  public String recordSuccess(
      AttemptHandle handle,
      LlmRequest request,
      long latencyMs,
      RunCostTracker.CallUsage usage,
      ChatResponse response) {
    if (handle == null || request == null || usage == null) return null;
    return persist(
        request,
        request.context().runId(),
        request.context().taskId(),
        request.agentRole(),
        request.stepId(),
        retryOf(request, handle.attemptNumber()),
        handle.provider(),
        handle.model(),
        handle.keySource(),
        handle.attemptNumber(),
        latencyMs,
        usage,
        response,
        null,
        handle.attribution(),
        handle);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String recordFailure(
      LlmRequest request,
      String provider,
      String model,
      String keySource,
      int attemptNumber,
      long latencyMs,
      Throwable error) {
    if (request == null) return null;
    if (error == null) error = new IllegalStateException("provider failure detail unavailable");
    return persist(
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

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String recordFailure(
      AttemptHandle handle, LlmRequest request, long latencyMs, Throwable error) {
    if (handle == null || request == null) return null;
    Throwable resolvedError =
        error == null ? new IllegalStateException("provider failure detail unavailable") : error;
    return persist(
        request,
        request.context().runId(),
        request.context().taskId(),
        request.agentRole(),
        request.stepId(),
        retryOf(request, handle.attemptNumber()),
        handle.provider(),
        handle.model(),
        handle.keySource(),
        handle.attemptNumber(),
        latencyMs,
        null,
        null,
        resolvedError,
        handle.attribution(),
        handle);
  }

  /** Records a successful physical attempt for a governed non-chat provider capability. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String recordProviderCapabilitySuccess(
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
    return persist(
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

  /** Terminal success paired to a prior capability intent. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String recordProviderCapabilitySuccess(
      AttemptHandle handle,
      String runId,
      String taskId,
      String agentRole,
      String stepId,
      long latencyMs,
      RunCostTracker.CallUsage usage) {
    if (handle == null) return null;
    return persist(
        null,
        runId,
        taskId,
        agentRole,
        stepId,
        null,
        handle.provider(),
        handle.model(),
        handle.keySource(),
        handle.attemptNumber(),
        latencyMs,
        usage,
        null,
        null,
        handle.attribution(),
        handle);
  }

  /** Records a failed physical attempt for a governed non-chat provider capability. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String recordProviderCapabilityFailure(
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
    return persist(
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

  /** Terminal failure paired to a prior capability intent. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String recordProviderCapabilityFailure(
      AttemptHandle handle,
      String runId,
      String taskId,
      String agentRole,
      String stepId,
      long latencyMs,
      Throwable error) {
    if (handle == null) return null;
    return persist(
        null,
        runId,
        taskId,
        agentRole,
        stepId,
        null,
        handle.provider(),
        handle.model(),
        handle.keySource(),
        handle.attemptNumber(),
        latencyMs,
        null,
        null,
        error == null ? new IllegalStateException("provider failure detail unavailable") : error,
        handle.attribution(),
        handle);
  }

  private String persist(
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
    return persist(
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
        null,
        null);
  }

  private String persist(
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
    return persist(
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
        explicitAttribution,
        null);
  }

  private String persist(
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
      LlmUsageAttributionResolver.Attribution explicitAttribution,
      AttemptHandle preparedHandle) {
    if (runId == null || runId.isBlank()) return null;
    String status = error == null ? "SUCCESS" : "FAILED";
    RunStepUsage row = null;
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
      String resolvedProvider =
          firstNonBlank(
              firstNonBlank(provider, usage == null ? null : usage.provider()), "unknown");
      String resolvedModel =
          firstNonBlank(firstNonBlank(model, usage == null ? null : usage.model()), "unknown");
      AttemptHandle resolvedHandle =
          preparedHandle != null
              ? preparedHandle
              : handleFor(
                  request,
                  resolvedProvider,
                  resolvedModel,
                  keySource,
                  attemptNumber,
                  null,
                  null,
                  attribution);
      row =
          RunStepUsage.builder()
              .id(resolvedHandle.physicalAttemptId())
              .runId(runId)
              .taskId(blankToNull(taskId))
              .agentRole(firstNonBlank(agentRole, "UNKNOWN"))
              .stepId(blankToNull(stepId))
              .retryOf(blankToNull(retryOf))
              .llmExecutionContextId(attribution.llmExecutionContextId())
              .correlationId(firstNonBlank(attribution.correlationId(), runId))
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
              .provider(resolvedProvider)
              .model(resolvedModel)
              .modelTier(modelRegistry.chainTierFor(resolvedModel))
              .logicalCallId(resolvedHandle.logicalCallId())
              .physicalAttemptId(resolvedHandle.physicalAttemptId())
              .parentAttemptId(resolvedHandle.parentAttemptId())
              .attemptKind(resolvedHandle.attemptKind())
              .attemptOrdinal(resolvedHandle.attemptOrdinal())
              .inputTokens(usage == null ? 0 : usage.inputTokens())
              .outputTokens(usage == null ? 0 : usage.outputTokens())
              .cacheWriteTokens(usage == null ? 0 : usage.cacheWriteTokens())
              .cacheReadTokens(usage == null ? 0 : usage.cacheReadTokens())
              .reasoningTokens(usage == null ? 0 : usage.reasoningTokens())
              .costUsd(usage == null ? 0 : usage.costUsd())
              .callIndex(usage == null ? 0 : usage.callIndex())
              .providerUsageRawJson(safeUsageMetadata(response, usage))
              .createdAt(Instant.now())
              .build();
      repository.save(row);
      metrics.recordLlmUsagePersistence(status, "ok");
      return row.getId();
    } catch (Exception persistenceFailure) {
      metrics.recordLlmUsagePersistence(status, "failed");
      log.warn(
          "Canonical LLM attempt usage write FAILED run={} task={} provider={} status={}: {}",
          runId,
          taskId,
          provider,
          status,
          persistenceFailure.getMessage());
      if (row != null) recoverySpool.enqueue(row);
      return null;
    }
  }

  private AttemptHandle handleFor(
      LlmRequest request,
      String provider,
      String model,
      String keySource,
      int attemptNumber,
      String explicitAttemptKind,
      String explicitParentAttemptId,
      LlmUsageAttributionResolver.Attribution attribution) {
    String resolvedProvider = firstNonBlank(provider, "unknown");
    String resolvedModel = firstNonBlank(model, "unknown");
    String logicalCallId =
        request == null
            ? UUID.randomUUID().toString()
            : firstNonBlank(request.usageLogicalCallId(), UUID.randomUUID().toString());
    int ordinal = Math.max(1, attemptNumber);
    String physicalAttemptId =
        logicalCallId + ":" + resolvedProvider.toLowerCase(Locale.ROOT) + ":" + ordinal;
    String attemptKind =
        firstNonBlank(
            explicitAttemptKind,
            request == null
                ? "CAPABILITY"
                : request.retryOf() != null && !request.retryOf().isBlank()
                    ? "FAILOVER"
                    : ordinal > 1 ? "RETRY" : "PRIMARY");
    String parentAttemptId =
        firstNonBlank(
            explicitParentAttemptId,
            ordinal > 1
                ? logicalCallId
                    + ":"
                    + resolvedProvider.toLowerCase(Locale.ROOT)
                    + ":"
                    + (ordinal - 1)
                : request == null ? null : blankToNull(request.retryOf()));
    return new AttemptHandle(
        physicalAttemptId,
        logicalCallId,
        parentAttemptId,
        attemptKind,
        ordinal,
        resolvedProvider,
        resolvedModel,
        modelRegistry.chainTierFor(resolvedModel),
        firstNonBlank(keySource, "UNKNOWN").toUpperCase(Locale.ROOT),
        ordinal,
        attribution);
  }

  public record AttemptHandle(
      String physicalAttemptId,
      String logicalCallId,
      String parentAttemptId,
      String attemptKind,
      long attemptOrdinal,
      String provider,
      String model,
      String modelTier,
      String keySource,
      int attemptNumber,
      LlmUsageAttributionResolver.Attribution attribution) {
    private LlmUsageAttemptIntent toIntent(LlmRequest request) {
      return LlmUsageAttemptIntent.builder()
          .physicalAttemptId(physicalAttemptId)
          .logicalCallId(logicalCallId)
          .parentAttemptId(parentAttemptId)
          .attemptKind(attemptKind)
          .attemptOrdinal(attemptOrdinal)
          .llmExecutionContextId(attribution.llmExecutionContextId())
          .correlationId(firstNonBlank(attribution.correlationId(), request.context().runId()))
          .orgId(attribution.orgId())
          .userId(attribution.userId())
          .projectId(attribution.projectId())
          .feature(attribution.feature())
          .sourceType(attribution.sourceType())
          .sourceId(firstNonBlank(attribution.sourceId(), request.context().runId()))
          .runId(request.context().runId())
          .taskId(blankToNull(request.context().taskId()))
          .agentRole(firstNonBlank(request.agentRole(), "UNKNOWN"))
          .stepId(blankToNull(request.stepId()))
          .provider(provider)
          .model(model)
          .modelTier(modelTier)
          .keySource(keySource)
          .startedAt(Instant.now())
          .build();
    }
  }

  public static final class LlmUsagePersistenceException extends RuntimeException {
    public LlmUsagePersistenceException(String message, Throwable cause) {
      super(message, cause);
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
