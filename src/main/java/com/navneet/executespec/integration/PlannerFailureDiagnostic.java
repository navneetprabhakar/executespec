package com.navneet.executespec.integration;

import java.util.Locale;
import java.util.regex.Pattern;

import com.navneet.executespec.agent.TaskExecutionBriefCompiler;
import com.navneet.executespec.agent.TaskExpanderAgent;
import com.navneet.executespec.security.ContextSanitizer;

/** Customer-safe, bounded evidence for deterministic planner-contract rejection. */
public final class PlannerFailureDiagnostic {

  static final int MAX_CHARS = 2_000;
  private static final String TRUNCATION_SUFFIX = "… [truncated]";
  private static final String DETERMINISTIC_PREFIX = "Plan validation rejected: ";

  private static final Pattern LOCAL_UNIX_PATH =
      Pattern.compile(
          "(?i)(?<![A-Za-z0-9])/(?:Users|home|tmp|var/folders|private/tmp|private/var/folders)/[^\\s;,]+");
  private static final Pattern LOCAL_WINDOWS_PATH =
      Pattern.compile("(?i)\\b[A-Z]:\\\\(?:Users|Temp)\\\\[^\\s;,]+");

  private PlannerFailureDiagnostic() {}

  static String describe(Throwable failure, String genericReason, ContextSanitizer sanitizer) {
    String deterministic = deterministicMessage(failure);
    if (deterministic == null) return genericReason;

    String safe = sanitizer.sanitizeOutput(deterministic);
    safe = LOCAL_UNIX_PATH.matcher(safe).replaceAll("[local path redacted]");
    safe = LOCAL_WINDOWS_PATH.matcher(safe).replaceAll("[local path redacted]");
    safe = safe.replaceAll("[\\p{Cntrl}&&[^\\t]]+", " ").replaceAll("\\s+", " ").trim();
    String diagnostic = DETERMINISTIC_PREFIX + safe;
    return diagnostic.length() <= MAX_CHARS
        ? diagnostic
        : diagnostic.substring(0, MAX_CHARS - TRUNCATION_SUFFIX.length()) + TRUNCATION_SUFFIX;
  }

  private static String deterministicMessage(Throwable failure) {
    String candidate = null;
    for (Throwable current = failure; current != null; current = current.getCause()) {
      String message = current.getMessage();
      if (message == null || message.isBlank()) continue;
      if (current instanceof TaskExecutionBriefCompiler.ExecutablePlanContractException) {
        candidate = "final plan contract validation failed: " + message;
        continue;
      }
      if (current instanceof TaskExpanderAgent.ExpansionContractException) {
        candidate = "task expansion contract validation failed: " + message;
        continue;
      }
      String normalized = message.toLowerCase(Locale.ROOT);
      // The hierarchical coordinator deliberately wraps every coherence failure in
      // "hierarchical plan coherence validation failed". That wrapper is lifecycle context, not
      // proof of a deterministic contract rejection: its cause may be provider authentication,
      // model resolution, prompt assembly, transport, or malformed tool output. Admit only
      // validator-owned messages whose shape carries the rejected contract evidence itself.
      if (normalized.startsWith(
              "assembled plan still violates executable contracts after one targeted repair:")
          || normalized.startsWith("plan contract validation failed:")
          || normalized.startsWith("fatal contract conflicts:")
          || normalized.startsWith(
              "medium plan coherence validation failed: fatal contract conflicts:")) {
        candidate = message;
      }
    }
    return candidate;
  }

  /** Whether a durable failure reason carries the server-owned deterministic evidence marker. */
  public static boolean isDeterministicEvidence(String failureReason) {
    return failureReason != null && failureReason.startsWith(DETERMINISTIC_PREFIX);
  }

  /** Whether the persisted lifecycle reason can represent a deterministic plan rejection. */
  public static boolean isPlanValidationStatusReason(String statusReason) {
    return "PLANNER_UNAVAILABLE".equals(statusReason)
        || "PLAN_VALIDATION_REJECTED".equals(statusReason);
  }

  /** Whether the final worker-brief compiler deliberately rejected its assembled contract. */
  public static boolean isExecutableContractRejection(Throwable failure) {
    return failure instanceof TaskExecutionBriefCompiler.ExecutablePlanContractException;
  }

  public static String projectStatusReason(String persistedReason, String failureReason) {
    return "PLANNER_UNAVAILABLE".equals(persistedReason) && isDeterministicEvidence(failureReason)
        ? "PLAN_VALIDATION_REJECTED"
        : persistedReason;
  }
}
