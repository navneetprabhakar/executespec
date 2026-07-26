package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Validates explicitly declared first-execution versus replay response semantics. */
final class IdempotencyResponseSemanticValidator {
  static final String FIRST_RESPONSE_MARKED =
      "Idempotency response semantics mark the first execution with the replay indicator";
  static final String INCOMPLETE_REPLAY =
      "Idempotency replay must return the stored status/body with the replay indicator";

  private static final Pattern FIRST_EXECUTION =
      Pattern.compile(
          "(?i)\\b(?:first|initial|fresh|new)\\s+(?:execution|request|response)\\b|"
              + "\\b(?:first|initial)\\s+time\\b");
  private static final Pattern REPLAY = Pattern.compile("(?i)\\breplay(?:ed|ing)?\\b");
  private static final Pattern REPLAY_INDICATOR =
      Pattern.compile(
          "(?i)\\b(?:replay(?:ed)?\\s+(?:indicator|header|flag|marker)|"
              + "(?:[a-z][a-z0-9]*[_-])+(?:replay|replayed)(?:[_-][a-z0-9]+)*|"
              + "replayed\\s*[:=])");
  private static final Pattern POSITIVE_MARKER =
      Pattern.compile(
          "(?i)\\b(?:true|present|set|include[ds]?|add(?:ed|s)?|return(?:ed|s)?|"
              + "emit(?:ted|s)?|with)\\b");
  private static final Pattern NEGATED_MARKER =
      Pattern.compile(
          "(?i)\\b(?:without|omit(?:ted|s)?|absent|false|not\\s+(?:set|present|included|"
              + "returned|emitted))\\b");

  private IdempotencyResponseSemanticValidator() {}

  static void addViolations(String description, String constraints, List<String> violations) {
    String evidence = safe(description) + "\n" + safe(constraints);
    String normalized = evidence.toLowerCase(Locale.ROOT);
    if (!normalized.contains("idempoten")
        || !FIRST_EXECUTION.matcher(evidence).find()
        || !REPLAY.matcher(evidence).find()
        || !REPLAY_INDICATOR.matcher(evidence).find()) return;

    List<String> clauses = clauses(evidence);
    boolean firstMarked =
        clauses.stream()
            .filter(clause -> FIRST_EXECUTION.matcher(clause).find())
            .filter(clause -> REPLAY_INDICATOR.matcher(clause).find())
            .anyMatch(IdempotencyResponseSemanticValidator::positivelyMarksReplay);
    if (firstMarked) violations.add(FIRST_RESPONSE_MARKED);

    boolean storedStatus =
        normalized.matches(
                "(?s).*\\breplay(?:ed|ing)?\\b.{0,240}\\bstored\\b.{0,100}\\bstatus\\b.*")
            || normalized.matches(
                "(?s).*\\bstored\\b.{0,100}\\bstatus\\b.{0,240}\\breplay(?:ed|ing)?\\b.*");
    boolean storedBody =
        normalized.matches("(?s).*\\breplay(?:ed|ing)?\\b.{0,240}\\bstored\\b.{0,100}\\bbody\\b.*")
            || normalized.matches(
                "(?s).*\\bstored\\b.{0,100}\\bbody\\b.{0,240}\\breplay(?:ed|ing)?\\b.*");
    boolean replayMarked =
        clauses.stream()
            .filter(clause -> REPLAY.matcher(clause).find())
            .filter(clause -> REPLAY_INDICATOR.matcher(clause).find())
            .anyMatch(IdempotencyResponseSemanticValidator::positivelyMarksReplay);
    if (!storedStatus || !storedBody || !replayMarked) violations.add(INCOMPLETE_REPLAY);
  }

  private static boolean positivelyMarksReplay(String clause) {
    return POSITIVE_MARKER.matcher(clause).find() && !NEGATED_MARKER.matcher(clause).find();
  }

  private static List<String> clauses(String evidence) {
    List<String> clauses = new ArrayList<>();
    for (String clause : safe(evidence).split("[.;\\n]+")) {
      if (!clause.isBlank()) clauses.add(clause.strip());
    }
    return clauses;
  }
}
