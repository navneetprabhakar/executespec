package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;
import static com.navneet.executespec.agent.TaskExpansionSemanticContract.containsAny;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Detects one literal read route being assigned incompatible collection and item semantics. */
final class TaskExpansionRouteContract {

  private TaskExpansionRouteContract() {}

  static List<String> ambiguousDuplicateReadRoutes(String description) {
    String source = safe(description).toLowerCase(Locale.ROOT);
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\\bget\\s+(/api/[a-z0-9_{}?=&./:-]+)").matcher(source);
    Map<String, List<String>> contexts = new LinkedHashMap<>();
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
      contexts
          .computeIfAbsent(path, ignored -> new ArrayList<>())
          .add(source.substring(start, end));
    }
    List<String> ambiguous = new ArrayList<>();
    contexts.forEach(
        (path, occurrences) -> {
          if (occurrences.size() < 2) return;
          boolean collection = false;
          boolean item = false;
          for (String context : occurrences) {
            boolean collectionContext =
                containsAny(context, "list ", "list all", "collection", "all reservations");
            boolean itemContext =
                containsAny(
                    context,
                    "single ",
                    "by id",
                    "by reservation_id",
                    "reservation detail",
                    "item detail");
            if (collectionContext == itemContext) continue;
            collection |= collectionContext;
            item |= itemContext;
          }
          if (collection && item) ambiguous.add(path);
        });
    return List.copyOf(ambiguous);
  }

  private static int firstBoundary(int fallback, int... candidates) {
    int boundary = fallback;
    for (int candidate : candidates) {
      if (candidate >= 0 && candidate < boundary) boundary = candidate;
    }
    return boundary;
  }
}
