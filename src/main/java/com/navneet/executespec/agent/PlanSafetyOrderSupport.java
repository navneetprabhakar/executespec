package com.navneet.executespec.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic repairs for shared Order persistence and transport terminology. */
final class PlanSafetyOrderSupport {
  private PlanSafetyOrderSupport() {}

  static String normalizeOrderStatusField(String value) {
    String normalized =
        PlanSafetyEvidenceSupport.safe(value)
            .replaceAll(
                "(?i)DRAFT\\s*/\\s*RESERVED\\s*/\\s*COMMITTED\\s*/\\s*CANCEL\\b",
                "DRAFT/RESERVED/COMMITTED/CANCELLED")
            .replaceAll(
                "(?i)DRAFT\\s*,\\s*RESERVED\\s*,\\s*COMMITTED\\s*,\\s*CANCEL\\b",
                "DRAFT, RESERVED, COMMITTED, CANCELLED")
            .replaceAll("(?i)(\\b(?:state|status)\\s+changes\\s+to\\s+)CANCEL\\b", "$1CANCELLED");
    return normalizeLifecycleSchemaField(normalized, "Order");
  }

  /**
   * Rewrites an exact dotted lifecycle-field reference or a positively identified persistence
   * schema field after the assembled plan has established {@code Entity.status} as canonical.
   *
   * <p>This deliberately does not rewrite bare words such as "state", response DTO fields, or a
   * different entity's lifecycle. It closes producer/consumer drift such as an acceptance task
   * asserting {@code Reservation.state} after the schema owner and deterministic contract both
   * define {@code Reservation.status}.
   */
  static String normalizeCanonicalStatusReference(String value, String entity) {
    if (value == null || value.isBlank() || entity == null || entity.isBlank()) {
      return PlanSafetyEvidenceSupport.safe(value);
    }
    return normalizeLifecycleSchemaField(value, entity);
  }

  private static String normalizeLifecycleSchemaField(String value, String entity) {
    String source =
        value.replaceAll(
            "(?i)\\b" + Pattern.quote(entity) + "\\.state\\b",
            Matcher.quoteReplacement(entity + ".status"));
    StringBuilder normalized = new StringBuilder(source.length());
    for (String clause : source.split("(?<=[;\\n])|(?<=\\.)(?=\\s+[A-Z])", -1)) {
      String lower = clause.toLowerCase(java.util.Locale.ROOT);
      String entityLower = entity.toLowerCase(java.util.Locale.ROOT);
      boolean namesEntity = lower.matches("(?s).*\\b" + Pattern.quote(entityLower) + "\\b.*");
      boolean schemaContext =
          lower.matches(
                  "(?s).*\\b(?:add|define|persist|migrate|model|schema|table)\\w*\\b"
                      + "[^.\\n]{0,160}\\b"
                      + Pattern.quote(entityLower)
                      + "\\b[^.\\n]{0,160}\\bstate\\b.*")
              || lower.matches(
                  "(?s).*\\b"
                      + Pattern.quote(entityLower)
                      + "\\s+(?:orm\\s+)?(?:model|schema|table)\\b"
                      + "[^.\\n]{0,160}\\bstate\\b.*")
              || lower.matches(
                  "(?s).*\\bstate\\s+(?:field|column)\\b[^.\\n]{0,120}\\b"
                      + Pattern.quote(entityLower)
                      + "\\b.*");
      if (namesEntity && schemaContext) {
        clause = clause.replaceAll("(?i)\\bstate\\b", "status");
      }
      normalized.append(clause);
    }
    return normalized.toString();
  }
}
