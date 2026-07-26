package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isFrontendOnlyTask;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ensures final page artifacts can replace route stubs created by an earlier frontend scaffold. */
final class PlanFrontendRouteBindingGate {
  private static final Pattern ROUTE_LITERAL = Pattern.compile("`(/[^`\\s]*)`");

  private PlanFrontendRouteBindingGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    PlannedTask scaffold =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .filter(PlanFrontendRouteBindingGate::declaresPlaceholderRoutes)
            .findFirst()
            .orElse(null);
    if (scaffold == null) return;
    String router = routerArtifact(scaffold);
    if (router == null) return;
    Set<String> declaredRoutes = declaredRoutes(scaffold);
    List<PlannedTask> routerOwners =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .filter(task -> !task.id().equals(scaffold.id()))
            .filter(task -> touches(task, router))
            .toList();

    for (PlannedTask task : plan.tasks()) {
      if (task == null
          || task.id().equals(scaffold.id())
          || !isFrontendOnlyTask(task)
          || touches(task, router)) continue;
      List<String> unbound = new ArrayList<>();
      for (String raw : task.touchesFiles() == null ? List.<String>of() : task.touchesFiles()) {
        String path = normalized(raw);
        if (!path.matches(".*\\/pages\\/[^/]+\\.(?:tsx|jsx)$")) continue;
        String component = basename(raw).replaceFirst("\\.(?i:tsx|jsx)$", "");
        String expected = expectedRoute(component, declaredRoutes);
        if (expected != null
            && routerOwners.stream()
                .noneMatch(owner -> explicitlyBinds(owner, component, expected)))
          unbound.add(component + " -> " + expected);
      }
      if (!unbound.isEmpty()) {
        violations.add(
            task.id()
                + ": final page routes "
                + unbound
                + " remain bound to scaffold placeholders because the task does not update "
                + router);
      }
    }
  }

  private static boolean declaresPlaceholderRoutes(PlannedTask task) {
    if (!isFrontendOnlyTask(task) || routerArtifact(task) == null) return false;
    String evidence = evidence(task);
    return (evidence.contains("placeholder") || evidence.contains("route stub"))
        && !declaredRoutes(task).isEmpty();
  }

  private static String routerArtifact(PlannedTask task) {
    return (task.touchesFiles() == null ? List.<String>of() : task.touchesFiles())
        .stream()
            .map(value -> safe(value).replace('\\', '/'))
            .filter(
                path ->
                    path.matches(
                        "(?i).*frontend/src/(?:App|routes?|router)(?:\\.[A-Za-z0-9_-]+)?\\.(?:tsx|jsx)$"))
            .findFirst()
            .orElse(null);
  }

  private static Set<String> declaredRoutes(PlannedTask task) {
    LinkedHashSet<String> routes = new LinkedHashSet<>();
    Matcher matcher = ROUTE_LITERAL.matcher(safe(task.description()));
    while (matcher.find()) routes.add(matcher.group(1));
    return Set.copyOf(routes);
  }

  private static String expectedRoute(String component, Set<String> declaredRoutes) {
    String words =
        component.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).trim();
    String role = "";
    for (String suffix : List.of(" detail", " create", " new", " history")) {
      if (!words.endsWith(suffix)) continue;
      role = suffix.trim();
      words = words.substring(0, words.length() - suffix.length()).trim();
      break;
    }
    if (words.equals("dashboard") && declaredRoutes.contains("/")) return "/";
    String resource = words;
    String collection =
        declaredRoutes.stream()
            .filter(route -> !route.equals("/") && !route.contains(":") && !route.contains("{"))
            .filter(route -> resourceMatches(lastSegment(route), resource))
            .findFirst()
            .orElse(null);
    if (collection == null) return null;
    return switch (role) {
      case "detail" -> collection + "/:id";
      case "create", "new" -> collection + "/new";
      default -> collection;
    };
  }

  private static boolean resourceMatches(String routeResource, String componentResource) {
    return singular(routeResource).equals(singular(componentResource));
  }

  private static String singular(String value) {
    if (value.endsWith("ies") && value.length() > 3)
      return value.substring(0, value.length() - 3) + "y";
    if (value.endsWith("s") && value.length() > 3) return value.substring(0, value.length() - 1);
    return value;
  }

  private static String lastSegment(String route) {
    return route.substring(route.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
  }

  private static boolean touches(PlannedTask task, String artifact) {
    return task.touchesFiles() != null
        && task.touchesFiles().stream()
            .map(value -> safe(value).replace('\\', '/'))
            .anyMatch(artifact::equals);
  }

  private static boolean explicitlyBinds(
      PlannedTask routerOwner, String component, String expectedRoute) {
    String evidence = evidence(routerOwner);
    String normalizedComponent = component.toLowerCase(Locale.ROOT);
    boolean namesComponent =
        evidence.matches("(?s).*\\b" + Pattern.quote(normalizedComponent) + "(?:page)?\\b.*");
    boolean namesRoute =
        Pattern.compile(
                "(?i)(?:`|'|\")?"
                    + Pattern.quote(expectedRoute)
                    + "(?:`|'|\")?(?:\\s|\\p{Punct}|$)")
            .matcher(evidence)
            .find();
    return namesComponent
        && namesRoute
        && evidence.matches("(?s).*\\b(?:bind|route|router|wire|mount|replace|integrat\\w*)\\b.*");
  }

  private static String basename(String path) {
    String normalized = safe(path).replace('\\', '/');
    return normalized.substring(normalized.lastIndexOf('/') + 1);
  }

  private static String normalized(String value) {
    return safe(value).replace('\\', '/').toLowerCase(Locale.ROOT);
  }

  private static String evidence(PlannedTask task) {
    return (safe(task.title())
            + "\n"
            + safe(task.description())
            + "\n"
            + String.join(
                "\n", task.hardConstraints() == null ? List.of() : task.hardConstraints()))
        .toLowerCase(Locale.ROOT);
  }
}
