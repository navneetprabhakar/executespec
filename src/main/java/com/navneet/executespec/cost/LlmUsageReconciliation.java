package com.navneet.executespec.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.navneet.executespec.cost.LlmUsageAuditReport.ReconciliationResult;
import com.navneet.executespec.cost.LlmUsageAuditReport.TolerancePolicy;

/** Pure reconciliation policy shared by the SQL-backed report and deterministic tests. */
final class LlmUsageReconciliation {

  static final BigDecimal ABSOLUTE_USD_TOLERANCE = new BigDecimal("0.000001");
  static final BigDecimal RELATIVE_TOLERANCE = new BigDecimal("0.005");

  private LlmUsageReconciliation() {}

  static TolerancePolicy tolerancePolicy() {
    return new TolerancePolicy(ABSOLUTE_USD_TOLERANCE, RELATIVE_TOLERANCE);
  }

  static ReconciliationResult calculate(
      String emptyStatus,
      String incompleteStatus,
      UsageSide canonical,
      UsageSide comparison,
      long comparableRecords,
      long missingComparisonRecords,
      long comparisonOnlyRecords,
      long unknownCostAttempts,
      boolean compareTokens,
      String matchedExplanation,
      String incompleteExplanation,
      String emptyExplanation) {
    BigDecimal expected = canonical.cost();
    BigDecimal actual = comparison.cost();
    BigDecimal difference = expected.subtract(actual).setScale(9, RoundingMode.HALF_UP);
    BigDecimal tolerance = ABSOLUTE_USD_TOLERANCE.max(expected.abs().multiply(RELATIVE_TOLERANCE));
    boolean tokensMatch = !compareTokens || canonical.sameTokens(comparison);
    String status;
    String explanation;
    if (comparableRecords == 0 && missingComparisonRecords == 0 && comparisonOnlyRecords == 0) {
      status = emptyStatus;
      explanation = emptyExplanation;
    } else if (missingComparisonRecords > 0
        || comparisonOnlyRecords > 0
        || unknownCostAttempts > 0) {
      status = incompleteStatus;
      explanation = incompleteExplanation;
    } else if (difference.abs().compareTo(tolerance) <= 0 && tokensMatch) {
      status = "MATCH";
      explanation = matchedExplanation;
    } else {
      status = "MISMATCH";
      explanation = "Known cost or token totals differ beyond the documented tolerance.";
    }
    return new ReconciliationResult(
        status,
        expected,
        actual,
        difference,
        tolerance,
        canonical.inputTokens(),
        comparison.inputTokens(),
        canonical.outputTokens(),
        comparison.outputTokens(),
        canonical.reasoningTokens(),
        comparison.reasoningTokens(),
        canonical.cacheReadTokens(),
        comparison.cacheReadTokens(),
        canonical.cacheWriteTokens(),
        comparison.cacheWriteTokens(),
        compareTokens,
        comparableRecords,
        missingComparisonRecords,
        comparisonOnlyRecords,
        unknownCostAttempts,
        explanation);
  }

  record UsageSide(
      BigDecimal cost,
      long inputTokens,
      long outputTokens,
      long reasoningTokens,
      long cacheReadTokens,
      long cacheWriteTokens) {
    UsageSide {
      cost = cost == null ? BigDecimal.ZERO : cost;
    }

    boolean sameTokens(UsageSide other) {
      return inputTokens == other.inputTokens
          && outputTokens == other.outputTokens
          && reasoningTokens == other.reasoningTokens
          && cacheReadTokens == other.cacheReadTokens
          && cacheWriteTokens == other.cacheWriteTokens;
    }
  }
}
