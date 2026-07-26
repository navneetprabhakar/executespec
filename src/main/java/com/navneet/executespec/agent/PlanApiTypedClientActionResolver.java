package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agent.PlanApiContractEvidence.ApiEvidence;

/**
 * Resolves a typed-client function's action name to one declared mutation route.
 *
 * <p>The function name is stronger evidence than a copied endpoint literal on the same client
 * declaration. Resolution remains fail-closed: exactly one producer must expose the named action.
 */
final class PlanApiTypedClientActionResolver {
  private static final Pattern TYPED_ACTION_CALL =
      Pattern.compile(
          "(?i)\\b(reserve|commit|cancel|deactivate|adjust|transfer)[a-z0-9_]*\\s*\\([^)]*\\)"
              + "\\s*(?:->|[-—:])?\\s*(?:GET|POST|PUT|PATCH|DELETE)\\b");
  private static final Pattern ACTION_FUNCTION =
      Pattern.compile(
          "(?i)\\b(reserve|commit|cancel|deactivate|adjust|transfer)[a-z0-9_]*\\s*\\([^)]*\\)");
  private static final Pattern ACTION_ENDPOINT =
      Pattern.compile(
          "(?i)\\b(?:POST|PUT|PATCH|DELETE)\\s*(?:/|`/)"
              + "(?:[a-z0-9_{}.-]+/)*(reserve|commit|cancel|deactivate|adjust|transfer)\\b");
  private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final Map<String, Set<String>> ACTION_PATH_ALIASES =
      Map.of(
          "adjust", Set.of("adjust", "adjustment", "adjustments"),
          "reserve", Set.of("reserve", "reservation", "reservations"),
          "cancel", Set.of("cancel", "cancellation", "cancellations"),
          "deactivate", Set.of("deactivate", "deactivation", "deactivations"),
          "commit", Set.of("commit", "commitment", "commitments"),
          "transfer", Set.of("transfer", "transfers"));

  private PlanApiTypedClientActionResolver() {}

  static ApiEvidence resolve(List<ApiEvidence> producers, ApiEvidence consumer, PlannedTask task) {
    Matcher actionCall = TYPED_ACTION_CALL.matcher(consumer.context());
    String action =
        actionCall.find()
            ? actionCall.group(1).toLowerCase(Locale.ROOT)
            : compactInventoryAction(task, consumer);
    if (action == null) return null;
    List<ApiEvidence> candidates =
        producers.stream()
            .filter(producer -> MUTATION_METHODS.contains(producer.method()))
            .filter(producer -> actionPathMatches(producer.path(), action))
            .toList();
    return candidates.size() == 1 ? candidates.getFirst() : null;
  }

  private static String compactInventoryAction(PlannedTask task, ApiEvidence consumer) {
    String evidence = task.description().toLowerCase(Locale.ROOT);
    String context = consumer.context().trim().toLowerCase(Locale.ROOT);
    int contextStart = evidence.indexOf(context);
    if (contextStart < 0) return null;
    int lineStart = evidence.lastIndexOf('\n', contextStart);
    lineStart = lineStart < 0 ? 0 : lineStart + 1;
    int lineEnd = evidence.indexOf('\n', contextStart);
    lineEnd = lineEnd < 0 ? evidence.length() : lineEnd;
    String line = evidence.substring(lineStart, lineEnd);

    List<String> actions = new ArrayList<>();
    Matcher functions = ACTION_FUNCTION.matcher(line);
    while (functions.find()) actions.add(functions.group(1).toLowerCase(Locale.ROOT));
    if (actions.isEmpty()) return null;

    List<Integer> endpointStarts = new ArrayList<>();
    Matcher endpoints = ACTION_ENDPOINT.matcher(line);
    while (endpoints.find()) endpointStarts.add(lineStart + endpoints.start());
    int actionIndex = -1;
    for (int index = 0; index < endpointStarts.size(); index++) {
      int endpointStart = endpointStarts.get(index);
      if (endpointStart >= contextStart && endpointStart < contextStart + context.length() + 2) {
        actionIndex = index;
        break;
      }
    }
    return actionIndex >= 0 && actionIndex < actions.size() ? actions.get(actionIndex) : null;
  }

  private static boolean actionPathMatches(String path, String action) {
    String segment = path.substring(path.lastIndexOf('/') + 1);
    return ACTION_PATH_ALIASES.getOrDefault(action, Set.of(action)).contains(segment);
  }
}
