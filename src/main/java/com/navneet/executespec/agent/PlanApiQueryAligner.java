package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanApiContractEvidence.PARAM_BLOCK;
import static com.navneet.executespec.agent.PlanApiContractEvidence.displayPath;
import static com.navneet.executespec.agent.PlanApiContractEvidence.typedParams;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agent.PlanApiContractEvidence.ApiEvidence;

/** Aligns consumer query syntax to the selected producer's declared endpoint contract. */
final class PlanApiQueryAligner {
  private PlanApiQueryAligner() {}

  static final class Occurrences {
    private final java.util.Map<String, Integer> byOperation = new java.util.LinkedHashMap<>();

    int next(ApiEvidence consumer, ApiEvidence producer) {
      String key = consumer.method() + "\u0000" + consumer.rawPath() + "\u0000" + producer.path();
      int occurrence = byOperation.getOrDefault(key, 0);
      byOperation.put(key, occurrence + 1);
      return occurrence;
    }
  }

  static String align(String evidence, ApiEvidence consumer, ApiEvidence producer) {
    return align(evidence, consumer, producer, 0);
  }

  /**
   * Aligns one concrete occurrence of a repeated consumer operation.
   *
   * <p>Typed clients commonly mention the same collection route in more than one helper or compact
   * acceptance summary. Replacing every regex match, or always selecting the first one, lets a
   * later consumer occurrence rewrite an earlier helper's parameter block. The caller preserves
   * extraction order and supplies the zero-based occurrence identity.
   */
  static String align(
      String evidence, ApiEvidence consumer, ApiEvidence producer, int occurrenceIndex) {
    if (evidence == null || evidence.isBlank()) return evidence;
    Pattern operation =
        Pattern.compile(
            "(?i)\\b"
                + Pattern.quote(consumer.method())
                + "\\s*(?:"
                + Pattern.quote(consumer.rawPath())
                + "|"
                + Pattern.quote(displayPath(producer.path()))
                + "|"
                + Pattern.quote(producer.path())
                + ")");
    Match occurrence = findOccurrence(operation, evidence, occurrenceIndex);
    if (occurrence == null) return evidence;

    int lineStart = evidence.lastIndexOf('\n', occurrence.start());
    int searchStart = Math.max(lineStart < 0 ? 0 : lineStart + 1, occurrence.start() - 500);
    String before = evidence.substring(searchStart, occurrence.start());
    Matcher params = PARAM_BLOCK.matcher(before);
    int paramsStart = -1;
    int paramsEnd = -1;
    while (params.find()) {
      paramsStart = params.start();
      paramsEnd = params.end();
    }
    String aligned = evidence;
    if (paramsStart >= 0) {
      String replacement = "params?: {" + typedParams(producer.declaredQueryParams()) + "}";
      aligned =
          evidence.substring(0, searchStart + paramsStart)
              + replacement
              + evidence.substring(searchStart + paramsEnd);
    }

    aligned = removeUnsupportedMentions(aligned, operation, occurrenceIndex, consumer, producer);
    String rawPath = consumer.rawPath();
    int queryStart = rawPath.indexOf('?');
    if (queryStart < 0) return aligned;
    List<String> retained = new ArrayList<>();
    for (String component : rawPath.substring(queryStart + 1).split("&")) {
      int equals = component.indexOf('=');
      String name = component.substring(0, equals < 0 ? component.length() : equals);
      if (producer.declaredQueryParams().contains(name)) retained.add(component);
      else if (name.equalsIgnoreCase("limit")
          && producer.declaredQueryParams().contains("page_size")) {
        retained.add("page_size" + component.substring(name.length()));
      }
    }
    String replacementPath =
        displayPath(producer.path()) + (retained.isEmpty() ? "" : "?" + String.join("&", retained));
    return replaceOccurrence(
        operation, aligned, occurrenceIndex, consumer.method() + " " + replacementPath);
  }

  private static String removeUnsupportedMentions(
      String evidence,
      Pattern operation,
      int occurrenceIndex,
      ApiEvidence consumer,
      ApiEvidence producer) {
    LinkedHashSet<String> unsupported = new LinkedHashSet<>(consumer.declaredQueryParams());
    unsupported.removeAll(producer.declaredQueryParams());
    if (unsupported.isEmpty()) return evidence;
    Match occurrence = findOccurrence(operation, evidence, occurrenceIndex);
    if (occurrence == null) return evidence;
    int lineStart = evidence.lastIndexOf('\n', occurrence.start());
    lineStart = lineStart < 0 ? 0 : lineStart + 1;
    int lineEnd = evidence.indexOf('\n', occurrence.end());
    lineEnd = lineEnd < 0 ? evidence.length() : lineEnd;
    String line = evidence.substring(lineStart, lineEnd);
    for (String parameter : unsupported) {
      line =
          line.replaceAll(
              "(?i)`?\\?"
                  + Pattern.quote(parameter)
                  + "(?![a-zA-Z0-9_])"
                  + "(?:=[^\\s`.,;]+)?`?(?:\\s+or\\s+(?:an?\\s+)?equivalent\\s+filter)?",
              "");
    }
    return evidence.substring(0, lineStart) + line + evidence.substring(lineEnd);
  }

  private static Match findOccurrence(Pattern operation, String evidence, int occurrenceIndex) {
    if (occurrenceIndex < 0) return null;
    Matcher matcher = operation.matcher(evidence);
    int index = 0;
    while (matcher.find()) {
      if (index++ == occurrenceIndex) return new Match(matcher.start(), matcher.end());
    }
    return null;
  }

  private static String replaceOccurrence(
      Pattern operation, String evidence, int occurrenceIndex, String replacement) {
    Match occurrence = findOccurrence(operation, evidence, occurrenceIndex);
    if (occurrence == null) return evidence;
    return evidence.substring(0, occurrence.start())
        + replacement
        + evidence.substring(occurrence.end());
  }

  private record Match(int start, int end) {}
}
