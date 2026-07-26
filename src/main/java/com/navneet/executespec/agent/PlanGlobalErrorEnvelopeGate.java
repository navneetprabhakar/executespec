package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cross-task consistency checks for a plan that claims one global HTTP error contract. */
final class PlanGlobalErrorEnvelopeGate {
  private static final Pattern NESTED_ERROR_ENVELOPE =
      Pattern.compile(
          "(?is)(?:\\{\\s*[\"']?error[\"']?\\s*:\\s*\\{|error\\.(?:code|message))"
              + ".{0,240}\\bcode\\b.{0,160}\\bmessage\\b");
  private static final Pattern RAW_DETAIL =
      Pattern.compile(
          "(?is)(?:\\{\\s*[\"']?detail[\"']?\\s*:|response(?:\\.data)?\\.detail\\b|"
              + "\\bdetail\\s+string\\s+from\\s+the\\s+response\\s+body\\b)");
  private static final Pattern FLAT_NORMALIZED_ERROR =
      Pattern.compile(
          "(?is)normaliz\\w*\\s+(?:all\\s+)?errors?.{0,180}"
              + "\\{\\s*code\\s*:\\s*string\\s*,\\s*message\\s*:\\s*string");
  private static final Pattern DIRECT_NESTED_FRONTEND_READ =
      Pattern.compile(
          "(?is)(?:response(?:\\.data)?\\.error\\.(?:code|message)|"
              + "\\bconsume\\w*.{0,80}\\{\\s*[\"']?error[\"']?\\s*:\\s*\\{)");

  private PlanGlobalErrorEnvelopeGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.tasks() == null) return;
    List<PlannedTask> tasks = plan.tasks().stream().filter(java.util.Objects::nonNull).toList();
    List<PlannedTask> globalOwners =
        tasks.stream().filter(PlanGlobalErrorEnvelopeGate::ownsGlobalNestedEnvelope).toList();
    if (globalOwners.isEmpty()) return;

    addFrameworkCoverageViolations(globalOwners, violations);
    addRawBackendProducerViolations(tasks, globalOwners, violations);
    addFrontendNormalizationViolations(tasks, violations);
  }

  private static void addFrameworkCoverageViolations(
      List<PlannedTask> globalOwners, List<String> violations) {
    for (PlannedTask owner : globalOwners) {
      String evidence = evidence(owner).toLowerCase(Locale.ROOT);
      if (!coversFrameworkHttpErrors(evidence)) {
        violations.add(
            owner.id()
                + ": global error-envelope owner does not cover framework HTTP errors such as"
                + " HTTPException");
      }
      if (!coversFrameworkValidationErrors(evidence)) {
        violations.add(
            owner.id()
                + ": global error-envelope owner does not cover framework request-validation"
                + " errors");
      }
    }
  }

  private static void addRawBackendProducerViolations(
      List<PlannedTask> tasks, List<PlannedTask> globalOwners, List<String> violations) {
    String owners =
        globalOwners.stream()
            .map(PlannedTask::id)
            .collect(java.util.stream.Collectors.joining(","));
    for (PlannedTask task : tasks) {
      if (globalOwners.contains(task) || isFrontendTask(task)) continue;
      Matcher matcher = RAW_DETAIL.matcher(evidence(task));
      if (!hasPositiveMatch(evidence(task), matcher)) continue;
      violations.add(
          task.id()
              + ": backend producer declares raw framework {detail: ...} despite global nested"
              + " error envelope owned by "
              + owners);
    }
  }

  private static void addFrontendNormalizationViolations(
      List<PlannedTask> tasks, List<String> violations) {
    List<PlannedTask> normalizers =
        tasks.stream()
            .filter(PlanGlobalErrorEnvelopeGate::isFrontendTask)
            .filter(task -> FLAT_NORMALIZED_ERROR.matcher(evidence(task)).find())
            .toList();
    if (normalizers.isEmpty()) return;
    String owners =
        normalizers.stream().map(PlannedTask::id).collect(java.util.stream.Collectors.joining(","));
    for (PlannedTask task : tasks) {
      if (!isFrontendTask(task) || normalizers.contains(task)) continue;
      String taskEvidence = evidence(task);
      if (hasPositiveMatch(taskEvidence, RAW_DETAIL.matcher(taskEvidence))) {
        violations.add(
            task.id()
                + ": frontend consumer reads raw response detail instead of the normalized"
                + " {code,message,details?} contract owned by "
                + owners);
      }
      if (hasPositiveMatch(taskEvidence, DIRECT_NESTED_FRONTEND_READ.matcher(taskEvidence))) {
        violations.add(
            task.id()
                + ": frontend consumer bypasses the normalized {code,message,details?} contract"
                + " owned by "
                + owners
                + " and reads the nested wire envelope directly");
      }
    }
  }

  private static boolean ownsGlobalNestedEnvelope(PlannedTask task) {
    String evidence = evidence(task);
    String lower = evidence.toLowerCase(Locale.ROOT);
    boolean ownsHandler =
        taskFiles(task).stream()
                .anyMatch(
                    path ->
                        path.contains("exception")
                            || path.contains("error_handler")
                            || path.contains("error-handler"))
            || lower.matches("(?s).*\\b(?:global error|exception handler|error middleware)\\b.*");
    boolean globalClaim =
        lower.matches(
            "(?s).*\\b(?:global error|every error|all errors|every non-2xx|all non-2xx|"
                + "one error (?:shape|envelope)|canonical error (?:shape|envelope))\\b.*");
    return ownsHandler && globalClaim && NESTED_ERROR_ENVELOPE.matcher(evidence).find();
  }

  private static boolean coversFrameworkHttpErrors(String evidence) {
    return evidence.contains("httpexception")
        || evidence.matches("(?s).*\\bframework http (?:error|exception)s?\\b.*")
        || evidence.matches("(?s).*\\bfastapi\\b.{0,80}\\b(?:http error|http exception)s?\\b.*");
  }

  private static boolean coversFrameworkValidationErrors(String evidence) {
    return evidence.contains("requestvalidationerror")
        || evidence.matches("(?s).*\\bframework (?:request )?validation errors?\\b.*")
        || evidence.matches(
            "(?s).*\\b(?:fastapi|pydantic)\\b.{0,100}\\b(?:request )?validation errors?\\b.*");
  }

  private static boolean hasPositiveMatch(String evidence, Matcher matcher) {
    while (matcher.find()) {
      String clause =
          localClause(evidence, matcher.start(), matcher.end()).toLowerCase(Locale.ROOT);
      if (clause.matches(
          "(?s).*\\b(?:supersed(?:e|es|ed)|forbid(?:den)?|must not|never|no longer|"
              + "instead of|do not|does not)\\b.*")) {
        continue;
      }
      return true;
    }
    return false;
  }

  private static boolean isFrontendTask(PlannedTask task) {
    String evidence = evidence(task).toLowerCase(Locale.ROOT);
    return taskFiles(task).stream()
            .anyMatch(
                path ->
                    path.startsWith("frontend/")
                        || path.endsWith(".tsx")
                        || path.endsWith(".jsx")
                        || path.endsWith(".ts"))
        || evidence.contains("frontend")
        || evidence.contains("react ");
  }

  private static List<String> taskFiles(PlannedTask task) {
    List<String> files = new ArrayList<>();
    if (task.touchesFiles() != null) files.addAll(task.touchesFiles());
    return files.stream()
        .filter(java.util.Objects::nonNull)
        .map(value -> value.replace('\\', '/').toLowerCase(Locale.ROOT))
        .toList();
  }

  private static String evidence(PlannedTask task) {
    return String.join(
        "\n",
        safe(task.title()),
        safe(task.description()),
        task.hardConstraints() == null ? "" : String.join("\n", task.hardConstraints()),
        task.touchesFiles() == null ? "" : String.join("\n", task.touchesFiles()));
  }

  private static String localClause(String text, int start, int end) {
    int clauseStart =
        Math.max(0, Math.max(text.lastIndexOf('\n', start), text.lastIndexOf('.', start)));
    int newlineEnd = text.indexOf('\n', end);
    int sentenceEnd = text.indexOf('.', end);
    int clauseEnd = text.length();
    if (newlineEnd >= 0) clauseEnd = Math.min(clauseEnd, newlineEnd);
    if (sentenceEnd >= 0) clauseEnd = Math.min(clauseEnd, sentenceEnd + 1);
    return text.substring(clauseStart, clauseEnd);
  }
}
