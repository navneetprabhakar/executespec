package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.containsAny;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.safe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PlanSafetyAuditSupport {

  private PlanSafetyAuditSupport() {}

  static List<String> requiredAuditOperations(String requirements) {
    String normalized = safe(requirements).toLowerCase(Locale.ROOT);
    if (!normalized.contains("audit")) return List.of();
    List<String> operations = new ArrayList<>();
    addWhenPresent(operations, normalized, "ADJUSTMENT", "adjustment", "adjust stock");
    addWhenPresent(operations, normalized, "TRANSFER", "transfer");
    addWhenPresent(operations, normalized, "RESERVE", "reserve", "reservation");
    addWhenPresent(operations, normalized, "CANCEL", "cancel");
    addWhenPresent(operations, normalized, "COMMIT", "commit");
    return List.copyOf(operations);
  }

  static String normalizeAuditOperations(String value, List<String> requiredOperations) {
    if (requiredOperations == null || requiredOperations.isEmpty()) return safe(value);
    String canonical = String.join(", ", requiredOperations);
    return safe(value)
        .replaceAll(
            "(?i)(?:ADJUSTMENT\\s*,\\s*)?TRANSFER_OUT\\s*,\\s*TRANSFER_IN"
                + "(?:\\s*,\\s*RESERVE)?(?:\\s*,\\s*COMMIT)?(?:\\s*,\\s*CANCELLED)?",
            canonical)
        .replaceAll("\\bTRANSFER_OUT\\b|\\bTRANSFER_IN\\b", "TRANSFER")
        .replaceAll("\\bADJUST\\b(?=\\s*(?:[,|)]|$))", "ADJUSTMENT")
        .replaceAll("\\bCANCELLED\\b(?=\\s*(?:[,|)]|$))", "CANCEL");
  }

  static String bindingAuditContract(List<String> requiredOperations) {
    if (requiredOperations == null || requiredOperations.isEmpty()) return null;
    String values = String.join(", ", requiredOperations);
    String transferRule =
        requiredOperations.contains("TRANSFER")
            ? " A completed transfer writes exactly one TRANSFER audit event for the atomic"
                + " source-to-destination mutation; TRANSFER_OUT/TRANSFER_IN pairs are forbidden."
            : "";
    return "AUDIT OPERATION CONTRACT (binding): the immutable audit operation values are exactly "
        + values
        + ". Each successful required mutation writes its audit event in the same transaction;"
        + " failed or rolled-back mutations write none."
        + transferRule;
  }

  private static void addWhenPresent(
      List<String> operations, String requirements, String operation, String... terms) {
    if (containsAny(requirements, terms)) operations.add(operation);
  }
}
