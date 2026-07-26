package com.navneet.executespec.agent;

import java.util.Locale;

/**
 * Scopes idempotency behavior contracts to mutation owners and keeps negated rules non-positive.
 */
final class PlanSafetyIdempotencySupport {
  private PlanSafetyIdempotencySupport() {}

  static boolean hasSplitTransactionInstruction(String value) {
    for (String clause : PlanSafetyEvidenceSupport.safe(value).split("[.\\n;]+")) {
      String normalized = clause.toLowerCase(Locale.ROOT);
      if (TaskExpansionSemanticContract.containsAny(
          normalized, "must not", "do not", "never ", "without ", "forbidden")) {
        continue;
      }
      if (normalized.matches(
              "(?s).*(?:separate|short-lived|initial).{0,100}transaction.{0,220}"
                  + "(?:business|handler|request proceed).*")
          || TaskExpansionSemanticContract.containsAny(
              normalized,
              "commit after storing the record",
              "after the response is generated",
              "after commit",
              "later update")) {
        return true;
      }
    }
    return false;
  }

  static String removeBehaviorContractsFromFrontend(String value) {
    return PlanSafetyEvidenceSupport.safe(value)
        .replaceAll("(?im)^SQLITE IDEMPOTENCY ALGORITHM \\(binding\\):[^\\n]*(?:\\R|$)", "")
        .replaceAll("(?im)^IDEMPOTENCY TRANSACTION \\(binding\\):[^\\n]*(?:\\R|$)", "")
        .replaceAll("(?im)^IDEMPOTENCY PERSISTENCE CONTRACT \\(binding\\):[^\\n]*(?:\\R|$)", "")
        .replaceAll("\\n{3,}", "\n\n")
        .strip();
  }

  static boolean isBehaviorOnlyConstraint(String value) {
    String normalized = PlanSafetyEvidenceSupport.safe(value).toLowerCase(Locale.ROOT);
    return normalized.contains("sqlite idempotency ownership must start begin immediate")
        || normalized.contains("claim idempotencyrecord ownership before any business mutation")
        || normalized.contains("ownership, business mutation, and completed replay")
        || normalized.contains("keep ownership, business mutation, and completed replay");
  }
}
