package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanApiContractEvidence.canonicalIdentityPath;
import static com.navneet.executespec.agent.PlanApiContractEvidence.displayPath;
import static com.navneet.executespec.agent.PlanApiContractEvidence.extract;
import static com.navneet.executespec.agent.PlanApiContractEvidence.normalizedPath;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isFrontendOnlyTask;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isTestOnlyTask;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agent.PlanApiContractEvidence.ApiEvidence;

/** Canonicalizes route identity and rejects incompatible cardinality for one logical operation. */
final class PlanApiRouteIdentityContract {
  private static final Pattern OPERATION =
      Pattern.compile(
          "(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s+(`?)(/(?:api(?:/|\\b))?[a-z0-9_{}?=&./:-]*)");
  private static final Set<String> ITEM_MARKERS =
      Set.of(
          "single ",
          "single-resource",
          "single resource",
          "by id",
          "by identifier",
          "item detail",
          "resource detail");
  private static final Set<String> COLLECTION_MARKERS =
      Set.of("list ", "list/", "list all", "collection", "all reservations", "all resources");

  private PlanApiRouteIdentityContract() {}

  /**
   * Rewrites path-parameter spellings only inside explicit HTTP operations.
   *
   * <p>Route identity is stack-neutral: {@code :reservation_id}, {@code {reservation_id}}, and
   * {@code {id}} compare as the same path. The unique producer's spelling is authoritative; query
   * strings and non-route prose remain untouched.
   */
  static Plan canonicalizeParameterNames(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return plan;
    Authorities authorities = authorities(plan);
    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      if (task == null) {
        tasks.add(null);
        continue;
      }
      String description = canonicalizeOperations(task.description(), authorities);
      List<String> constraints =
          (task.hardConstraints() == null ? List.<String>of() : task.hardConstraints())
              .stream().map(value -> canonicalizeOperations(value, authorities)).toList();
      tasks.add(copyTask(task, description, constraints));
    }
    return copyPlan(plan, tasks);
  }

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return;
    List<ApiEvidence> producers = new ArrayList<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      List<ApiEvidence> evidence = extract(task);
      addMissingItemPathViolations(task, evidence, violations);
      if (!isFrontendOnlyTask(task)
          && !isTestOnlyTask(task)
          && PlanApiContractEvidence.isImplementationProducerTask(task)) {
        producers.addAll(evidence);
      }
    }
    addAmbiguousProducerCardinalityViolations(producers, violations);
  }

  private static Authorities authorities(Plan plan) {
    Map<String, LinkedHashSet<String>> exactCandidates = new LinkedHashMap<>();
    Map<String, List<ApiEvidence>> actionCandidates = new LinkedHashMap<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null
          || isFrontendOnlyTask(task)
          || isTestOnlyTask(task)
          || !PlanApiContractEvidence.isImplementationProducerTask(task)) continue;
      for (ApiEvidence operation : extract(task)) {
        exactCandidates
            .computeIfAbsent(
                exactKey(operation.method(), operation.rawPath()), ignored -> new LinkedHashSet<>())
            .add(authoritativeRoute(operation));
        String actionKey = logicalActionKey(operation.method(), operation.rawPath());
        if (actionKey != null)
          actionCandidates.computeIfAbsent(actionKey, ignored -> new ArrayList<>()).add(operation);
      }
    }
    Map<String, String> exact = new LinkedHashMap<>();
    exactCandidates.forEach((key, paths) -> exact.put(key, paths.getFirst()));
    Map<String, String> actions = new LinkedHashMap<>();
    actionCandidates.forEach(
        (key, candidates) -> {
          Set<String> identities = new LinkedHashSet<>();
          for (ApiEvidence candidate : candidates) {
            identities.add(canonicalIdentityPath(candidate.rawPath()));
          }
          if (identities.size() == 1) actions.put(key, authoritativeRoute(candidates.getFirst()));
        });
    return new Authorities(Map.copyOf(exact), Map.copyOf(actions));
  }

  private static String canonicalizeOperations(String value, Authorities authorities) {
    String source = safe(value);
    Matcher matcher = OPERATION.matcher(source);
    StringBuffer canonical = new StringBuffer(source.length());
    while (matcher.find()) {
      String quote = matcher.group(2);
      String path = matcher.group(3);
      String punctuation = path.replaceFirst("(?s)^.*?([.,;:]+)$", "$1");
      if (punctuation.equals(path)) punctuation = "";
      if (!punctuation.isEmpty()) path = path.substring(0, path.length() - punctuation.length());
      int query = path.indexOf('?');
      String route = query < 0 ? path : path.substring(0, query);
      String suffix = query < 0 ? "" : path.substring(query);
      String method = matcher.group(1).toUpperCase(Locale.ROOT);
      String normalized = canonicalIdentityPath(route);
      String authoritative = authorities.exact().get(exactKey(method, normalized));
      if (authoritative == null) {
        String actionKey = logicalActionKey(method, normalized);
        if (actionKey != null) authoritative = authorities.actions().get(actionKey);
      }
      if (authoritative != null) route = authoritative;
      matcher.appendReplacement(
          canonical, Matcher.quoteReplacement(method + " " + quote + route + suffix + punctuation));
    }
    matcher.appendTail(canonical);
    return canonical.toString();
  }

  private static void addMissingItemPathViolations(
      PlannedTask task, List<ApiEvidence> evidence, List<String> violations) {
    for (ApiEvidence operation : evidence) {
      if (!operation.method().equals("GET") || parameterCount(operation.path()) > 0) continue;
      boolean item = containsAny(operation.context(), ITEM_MARKERS);
      boolean collection = containsAny(operation.context(), COLLECTION_MARKERS);
      if (item && !collection) {
        violations.add(
            task.id()
                + ": GET "
                + displayPath(operation.path())
                + " claims single-resource semantics but has no path parameter; declare a"
                + " distinct item route");
      }
    }
  }

  private static void addAmbiguousProducerCardinalityViolations(
      List<ApiEvidence> producers, List<String> violations) {
    Map<String, List<ApiEvidence>> byLogicalAction = new LinkedHashMap<>();
    for (ApiEvidence producer : producers) {
      String key = logicalActionKey(producer);
      if (key != null) {
        byLogicalAction.computeIfAbsent(key, ignored -> new ArrayList<>()).add(producer);
      }
    }
    for (List<ApiEvidence> candidates : byLogicalAction.values()) {
      Set<Integer> cardinalities = new LinkedHashSet<>();
      Set<String> paths = new LinkedHashSet<>();
      Set<String> owners = new LinkedHashSet<>();
      for (ApiEvidence candidate : candidates) {
        cardinalities.add(parameterCount(candidate.path()));
        paths.add(displayPath(candidate.path()));
        owners.add(candidate.taskId());
      }
      if (cardinalities.size() < 2 || paths.size() < 2) continue;
      violations.add(
          "API producer route cardinality is ambiguous for "
              + candidates.getFirst().method()
              + " "
              + String.join(" versus ", paths)
              + " across "
              + owners
              + "; choose one authoritative operation path");
    }
  }

  /**
   * Groups only action routes whose difference is an optional item parameter immediately before the
   * action. Collection/item reads are complementary and are intentionally not grouped here.
   */
  private static String logicalActionKey(ApiEvidence evidence) {
    return logicalActionKey(evidence.method(), evidence.rawPath());
  }

  private static String logicalActionKey(String method, String rawPath) {
    if (method.equals("GET")) return null;
    String path = canonicalIdentityPath(rawPath);
    String[] segments = path.split("/");
    if (segments.length < 3) return null;
    String action = segments[segments.length - 1];
    if (action.equals("{id}") || action.isBlank()) return null;
    int parameterBeforeAction = segments[segments.length - 2].equals("{id}") ? 1 : 0;
    int familyEnd = segments.length - 1 - parameterBeforeAction;
    if (familyEnd <= 1) return null;
    String family = String.join("/", java.util.Arrays.copyOfRange(segments, 1, familyEnd));
    return method + "\u0000" + family + "\u0000" + action;
  }

  private static String exactKey(String method, String path) {
    return method + "\u0000" + canonicalIdentityPath(path);
  }

  private static String authoritativeRoute(ApiEvidence evidence) {
    String raw = safe(evidence.rawPath());
    int query = raw.indexOf('?');
    if (query >= 0) raw = raw.substring(0, query);
    return raw.isBlank() ? displayPath(evidence.path()) : raw;
  }

  private static int parameterCount(String path) {
    int count = 0;
    Matcher matcher = Pattern.compile("\\{id}").matcher(normalizedPath(path));
    while (matcher.find()) count++;
    return count;
  }

  private static boolean containsAny(String value, Set<String> needles) {
    String normalized = safe(value).toLowerCase(Locale.ROOT);
    return needles.stream().anyMatch(normalized::contains);
  }

  private static PlannedTask copyTask(
      PlannedTask source, String description, List<String> constraints) {
    return PlannedTask.builder()
        .id(source.id())
        .title(source.title())
        .description(description)
        .complexity(source.complexity())
        .touchesFiles(source.touchesFiles())
        .hardConstraints(constraints)
        .dependsOn(source.dependsOn())
        .taskType(source.taskType())
        .meetsRequirements(source.meetsRequirements())
        .build();
  }

  private static Plan copyPlan(Plan source, List<PlannedTask> tasks) {
    return Plan.builder()
        .planTitle(source.planTitle())
        .planSummary(source.planSummary())
        .projectType(source.projectType())
        .buildCommand(source.buildCommand())
        .smokeTestStrategy(source.smokeTestStrategy())
        .runtimeSurface(source.runtimeSurface())
        .tasks(tasks)
        .classification(source.classification())
        .requirements(source.requirements())
        .build();
  }

  private record Authorities(Map<String, String> exact, Map<String, String> actions) {}
}
