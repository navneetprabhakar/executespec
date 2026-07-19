package com.navneet.executespec.cost;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Secret-free Enterprise report built from the canonical physical-attempt ledger. */
public record LlmUsageAuditReport(
    int schemaVersion,
    String source,
    String orgId,
    Window window,
    Limits limits,
    Page page,
    UsageTotals totals,
    List<UsageRollup> rollups,
    List<UsageAttempt> attempts,
    Reconciliation reconciliation) {

  public record Window(
      LocalDate from, LocalDate to, String timezone, Instant fromInclusive, Instant toExclusive) {}

  public record Limits(
      int maximumWindowDays,
      int maximumPageSize,
      int rollupRowLimit,
      boolean rollupsTruncated,
      int csvRowLimit) {}

  public record Page(int number, int size, long totalRows, long totalPages, int returnedRows) {}

  /** Cost is a lower bound whenever unknownCostAttempts is non-zero. */
  public record UsageTotals(
      long attempts,
      long successfulAttempts,
      long failedAttempts,
      long missingTerminalAttempts,
      long inputTokens,
      long outputTokens,
      long cacheReadTokens,
      long cacheWriteTokens,
      long reasoningTokens,
      BigDecimal knownCostUsdLowerBound,
      long unknownCostAttempts,
      boolean hasUnknownProviderCost) {}

  /**
   * Combined roll-up requested by Enterprise FinOps: user x provider x model x producer x feature x
   * project x run/session x key-source x outcome. Null run/session means that exact primary-key
   * resolution found no entity of that type; sourceType/sourceId still retain the canonical scope.
   */
  public record UsageRollup(
      String userId,
      String userEmail,
      String userRole,
      String provider,
      String model,
      String agentRole,
      String feature,
      String projectId,
      String projectName,
      String runId,
      String sessionId,
      String sourceType,
      String sourceId,
      String keySource,
      String attemptStatus,
      long attempts,
      long inputTokens,
      long outputTokens,
      long cacheReadTokens,
      long cacheWriteTokens,
      long reasoningTokens,
      BigDecimal knownCostUsdLowerBound,
      long unknownCostAttempts) {}

  public record UsageAttempt(
      String id,
      Instant createdAt,
      String llmExecutionContextId,
      String userId,
      String userEmail,
      String userRole,
      String projectId,
      String projectName,
      String runId,
      String sessionId,
      String sourceType,
      String sourceId,
      String taskId,
      String agentRole,
      String feature,
      String stepId,
      String retryOf,
      String correlationId,
      String modelTier,
      String logicalCallId,
      String physicalAttemptId,
      String parentAttemptId,
      String attemptKind,
      long attemptOrdinal,
      String provider,
      String model,
      String keySource,
      String attemptStatus,
      int attemptNumber,
      long latencyMs,
      long inputTokens,
      long outputTokens,
      long cacheReadTokens,
      long cacheWriteTokens,
      long reasoningTokens,
      BigDecimal knownCostUsd,
      String costStatus,
      long managedAiCredits,
      String managedAiProjectionStatus,
      String errorCode) {}

  public record Reconciliation(
      TolerancePolicy tolerancePolicy,
      ReconciliationResult runTotals,
      ReconciliationResult managedAiEvents,
      String providerInvoiceStatus) {}

  public record TolerancePolicy(BigDecimal absoluteUsd, BigDecimal relativeFraction) {}

  public record ReconciliationResult(
      String status,
      BigDecimal canonicalKnownCostUsd,
      BigDecimal comparisonCostUsd,
      BigDecimal costDifferenceUsd,
      BigDecimal appliedToleranceUsd,
      long canonicalInputTokens,
      long comparisonInputTokens,
      long canonicalOutputTokens,
      long comparisonOutputTokens,
      long canonicalReasoningTokens,
      long comparisonReasoningTokens,
      long canonicalCacheReadTokens,
      long comparisonCacheReadTokens,
      long canonicalCacheWriteTokens,
      long comparisonCacheWriteTokens,
      boolean tokensCompared,
      long comparableRecords,
      long missingComparisonRecords,
      long comparisonOnlyRecords,
      long unknownCostAttempts,
      String explanation) {}
}
