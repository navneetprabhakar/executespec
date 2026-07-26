package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;
import static com.navneet.executespec.agent.TaskExpansionSemanticContract.containsAny;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Detects one literal read route being assigned incompatible collection and item semantics. */
final class TaskExpansionRouteContract {

  private TaskExpansionRouteContract() {}

  static List<String> ambiguousDuplicateReadRoutes(String description) {
    Map<String, List<RouteOccurrence>> contexts = occurrences(description);
    List<String> ambiguous = new ArrayList<>();
    contexts.forEach(
        (path, occurrences) -> {
          if (occurrences.size() < 2) return;
          boolean collection = occurrences.stream().anyMatch(RouteOccurrence::collectionOnly);
          boolean item = occurrences.stream().anyMatch(RouteOccurrence::itemOnly);
          if (collection && item) ambiguous.add(path);
        });
    return List.copyOf(ambiguous);
  }

  /**
   * Removes only later clauses that assign a second, incompatible read cardinality to the same
   * literal parameterized route. The first declaration remains authoritative; adjacent operations
   * and uncertain contexts stay untouched so the final validator can still fail closed.
   */
  static String reconcileAmbiguousDuplicateReadRoutes(String description) {
    String source = safe(description);
    Map<String, List<RouteOccurrence>> byPath = occurrences(source);
    List<RouteOccurrence> removals = new ArrayList<>();
    byPath.forEach(
        (path, values) -> {
          RouteOccurrence authoritative =
              values.stream()
                  .filter(value -> value.collectionOnly() || value.itemOnly())
                  .findFirst()
                  .orElse(null);
          if (authoritative == null) return;
          for (RouteOccurrence candidate : values) {
            if (candidate.start() <= authoritative.start()) continue;
            if ((authoritative.collectionOnly() && candidate.itemOnly())
                || (authoritative.itemOnly() && candidate.collectionOnly())) {
              removals.add(candidate);
            }
          }
        });
    if (removals.isEmpty()) return source;
    removals.sort(Comparator.comparingInt(RouteOccurrence::start).reversed());
    StringBuilder reconciled = new StringBuilder(source);
    for (RouteOccurrence removal : removals) {
      reconciled.delete(removal.start(), removal.end());
    }
    return reconciled
        .toString()
        .replaceAll("[ \\t]+\\n", "\\n")
        .replaceAll("[ \\t]{2,}", " ")
        .replaceAll("(?:\\s+-\\s*){2,}", " - ")
        .strip();
  }

  private static Map<String, List<RouteOccurrence>> occurrences(String description) {
    String original = safe(description);
    String source = original.toLowerCase(Locale.ROOT);
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\\bget\\s+(/api/[a-z0-9_{}?=&./:-]+)").matcher(source);
    Map<String, List<RouteOccurrence>> contexts = new LinkedHashMap<>();
    while (matcher.find()) {
      String path = matcher.group(1).replaceAll("[.,:;]+$", "");
      // A static collection route such as /api/audit can be repeated in implementation and test
      // prose without becoming an item-detail route. The dangerous ambiguity requires a path
      // parameter whose identity is being assigned two incompatible meanings.
      if (!path.contains("{")) continue;
      int pathEnd = matcher.start(1) + path.length();
      int previousSentence =
          Math.max(
              Math.max(
                  source.lastIndexOf('.', matcher.start()),
                  source.lastIndexOf('\n', matcher.start())),
              Math.max(
                  source.lastIndexOf(';', matcher.start()),
                  source.lastIndexOf(" - ", matcher.start())));
      int nextPeriod = source.indexOf('.', pathEnd);
      int nextLine = source.indexOf('\n', pathEnd);
      int nextSemicolon = source.indexOf(';', pathEnd);
      int nextBullet = source.indexOf(" - ", pathEnd);
      int start = previousSentence < 0 ? 0 : previousSentence + 1;
      int end = firstBoundary(source.length(), nextPeriod, nextLine, nextSemicolon, nextBullet);
      String context = source.substring(start, end);
      boolean collectionContext =
          containsAny(context, "list ", "list all", "collection", "all reservations");
      boolean itemContext =
          containsAny(
              context,
              "single ",
              "by id",
              "by reservation_id",
              "reservation detail",
              "item detail",
              "retrieve reservation details",
              "retrieve profile details");
      contexts
          .computeIfAbsent(path, ignored -> new ArrayList<>())
          .add(
              new RouteOccurrence(
                  start,
                  end,
                  collectionContext && !itemContext,
                  itemContext && !collectionContext));
    }
    return contexts;
  }

  private static int firstBoundary(int fallback, int... candidates) {
    int boundary = fallback;
    for (int candidate : candidates) {
      if (candidate >= 0 && candidate < boundary) boundary = candidate;
    }
    return boundary;
  }

  private record RouteOccurrence(int start, int end, boolean collectionOnly, boolean itemOnly) {}
}
