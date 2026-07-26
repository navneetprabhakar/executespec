package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanApiContractEvidence.domainTokens;
import static com.navneet.executespec.agent.PlanApiContractEvidence.normalizedPath;
import static com.navneet.executespec.agent.PlanApiContractEvidence.overlap;
import static com.navneet.executespec.agent.PlanApiContractEvidence.producerOwnershipScore;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agent.PlanApiContractEvidence.ApiEvidence;

/** Resolves a contradictory mutation literal to one unambiguous read operation. */
final class PlanApiReadIntentResolver {
  private static final Pattern API_PATH_LITERAL = Pattern.compile("(?i)/api(?:/[a-z0-9_{}.-]+)+");

  private PlanApiReadIntentResolver() {}

  static ApiEvidence resolveRouteSpecific(
      List<ApiEvidence> producers, PlannedTask task, ApiEvidence consumer) {
    ApiEvidence candidate = resolve(producers, task);
    if (candidate == null || !exactPathConstraintMatches(task, candidate)) return null;
    Set<String> titleTokens = domainTokens(safe(task.title()));
    return overlap(candidate.domainTokens(), titleTokens) > 0
            && overlap(consumer.domainTokens(), titleTokens) == 0
        ? candidate
        : null;
  }

  static ApiEvidence resolveContextSpecific(
      List<ApiEvidence> producers,
      ApiEvidence consumer,
      java.util.Map<String, String> ownedRoutePrefixes) {
    if (!isMutationMethod(consumer.method())) return null;
    if (hasReadIntent(consumer)) {
      return producers.stream()
          .filter(producer -> producer.method().equals("GET"))
          .filter(producer -> producer.path().equals(consumer.path()))
          .max(
              java.util.Comparator.comparingInt(
                  producer -> producerOwnershipScore(producer, consumer, ownedRoutePrefixes)))
          .orElse(null);
    }
    return null;
  }

  static boolean isMutationMethod(String method) {
    return Set.of("POST", "PUT", "PATCH", "DELETE").contains(method);
  }

  static boolean hasReadIntent(ApiEvidence consumer) {
    String context = safe(consumer.context()).toLowerCase(Locale.ROOT);
    boolean mutation =
        consumer.path().matches(".*/(?:reserve|commit|cancel|deactivate|adjust|transfer)$")
            || context.matches(
                "(?s).*\\b(?:create|submit|save|edit|update|delete|reserve|commit|cancel|"
                    + "deactivate|adjust|transfer)\\w*\\b.*");
    return !mutation
        && context.matches(
            "(?s).*\\b(?:list|fetch|load|read|view|detail|history|search|filter|paginate)\\w*\\b.*");
  }

  static ApiEvidence resolve(List<ApiEvidence> producers, PlannedTask task) {
    String title = safe(task.title()).toLowerCase(Locale.ROOT);
    if (!title.matches(
            "(?s).*\\b(?:history|list|dashboard|detail|view|fetch|browse|search|report)\\w*\\b.*")
        || title.matches(
            "(?s).*\\b(?:create|submit|save|edit|update|delete|reserve|commit|cancel|deactivate|"
                + "adjust|transfer)\\w*\\b.*")) {
      return null;
    }
    Set<String> titleTokens = domainTokens(title);
    List<String> constraints = task.hardConstraints() == null ? List.of() : task.hardConstraints();
    List<ApiEvidence> candidates =
        producers.stream()
            .filter(producer -> producer.method().equals("GET"))
            .filter(
                producer ->
                    overlap(producer.domainTokens(), titleTokens) > 0
                        || constraints.stream()
                            .map(value -> safe(value))
                            .filter(
                                constraint ->
                                    constraint
                                        .toLowerCase(Locale.ROOT)
                                        .contains("exact public api path"))
                            .anyMatch(
                                constraint -> exactPathConstraintMatches(constraint, producer)))
            .toList();
    if (candidates.stream().map(ApiEvidence::path).distinct().count() != 1) return null;
    return candidates.stream()
        .max(
            java.util.Comparator.comparingInt(
                    (ApiEvidence producer) -> producer.declaredQueryParams().size())
                .thenComparingInt(producer -> producer.responseFields().size()))
        .orElse(null);
  }

  private static boolean exactPathConstraintMatches(String constraint, ApiEvidence producer) {
    Matcher paths = API_PATH_LITERAL.matcher(constraint);
    while (paths.find()) {
      String literal = paths.group().replaceAll("[.,;:]+$", "");
      if (normalizedPath(literal).equals(producer.path())) return true;
    }
    return false;
  }

  private static boolean exactPathConstraintMatches(PlannedTask task, ApiEvidence producer) {
    return (task.hardConstraints() == null ? List.<String>of() : task.hardConstraints())
        .stream()
            .filter(
                constraint ->
                    safe(constraint).toLowerCase(Locale.ROOT).contains("exact public api path"))
            .anyMatch(constraint -> exactPathConstraintMatches(constraint, producer));
  }
}
