package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rejects route spellings that cannot be implemented as an unambiguous HTTP contract. */
final class PlanApiSyntaxGate {
  private static final Pattern HTTP_ROUTE =
      Pattern.compile("(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s+`?(/[^\\s`'\"]+)");
  private static final Pattern ANGLE_PARAMETER = Pattern.compile("<[^>]+>");
  private static final Pattern INVENTORY_HEADING =
      Pattern.compile(
          "(?i).*\\b(?:routes? inventory|endpoints? inventory|api endpoints(?: to"
              + " (?:implement|expose|provide|call|cover))?)\\b.*");
  private static final Pattern MARKDOWN_SECTION =
      Pattern.compile("^\\s*(?:#{1,6}\\s+|\\*\\*[^*]+\\*\\*)");
  private static final Pattern INVENTORY_BULLET =
      Pattern.compile("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+).+");
  private static final Pattern VERSIONED_ROUTER_PREFIX =
      Pattern.compile(
          "(?i)\\b(?:register|mount|use|configure)\\s+(?:the\\s+)?(?:router|route)\\s+prefix\\s+"
              + "`?(/[^\\s`)]+)");
  private static final Pattern VERSION_SEGMENT = Pattern.compile("(?:^|/)v\\d+(?:$|/)");
  private static final Set<String> FRAMEWORK_ROUTE_LEAVES =
      Set.of("health", "ready", "readiness", "live", "liveness", "docs", "redoc", "metrics");

  private PlanApiSyntaxGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.tasks() == null) return;
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      Matcher route = HTTP_ROUTE.matcher(evidence(task));
      while (route.find()) {
        String raw = route.group(2).replaceAll("[.,;:]+$", "");
        String path = raw;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        boolean danglingAssignment = path.endsWith("=") || path.matches("(?s).*/[^/?&]+=.+");
        boolean angleParameter = ANGLE_PARAMETER.matcher(path).find();
        boolean queryWithoutQuestion = raw.matches("(?s).*/[^?&]+<[^>]+>&.+");
        if (!danglingAssignment && !angleParameter && !queryWithoutQuestion) continue;
        violations.add(
            task.id()
                + ": malformed HTTP route "
                + route.group(1).toUpperCase(Locale.ROOT)
                + " "
                + raw
                + "; use literal path segments, {parameter} placeholders, and '?' before query"
                + " parameters");
      }
      addInventoryViolations(task, violations);
    }
  }

  private static void addInventoryViolations(PlannedTask task, List<String> violations) {
    String description = safe(task.description());
    String routerPrefix = versionedRouterPrefix(description);
    boolean inInventory = false;
    Set<String> declarations = new HashSet<>();

    for (String line : description.split("\\R")) {
      if (INVENTORY_HEADING.matcher(line).matches()) {
        inInventory = true;
        declarations.clear();
        continue;
      }
      if (inInventory && MARKDOWN_SECTION.matcher(line).find()) {
        inInventory = false;
        continue;
      }
      if (!inInventory || !INVENTORY_BULLET.matcher(line).matches()) continue;

      Matcher route = HTTP_ROUTE.matcher(line);
      if (!route.find()) continue;
      String method = route.group(1).toUpperCase(Locale.ROOT);
      String path = operationPath(route.group(2));

      if (opensRouteCodeSpan(line, route) && !closesRouteCodeSpan(line, route)) {
        violations.add(
            task.id()
                + ": malformed Markdown route inventory entry "
                + method
                + " "
                + path
                + "; close the route code span before its description");
      }

      String operation = method + " " + path;
      if (!declarations.add(operation)) {
        violations.add(task.id() + ": duplicate route inventory declaration " + operation);
      }

      if (routerPrefix != null
          && path.startsWith("/api/")
          && !withinPrefix(path, routerPrefix)
          && !isFrameworkRoute(path)) {
        violations.add(
            task.id()
                + ": route namespace drift "
                + operation
                + "; declared router prefix is "
                + routerPrefix);
      }
    }
  }

  private static String versionedRouterPrefix(String description) {
    Matcher prefix = VERSIONED_ROUTER_PREFIX.matcher(description);
    while (prefix.find()) {
      String path = operationPath(prefix.group(1));
      if (VERSION_SEGMENT.matcher(path).find()) return path;
    }
    return null;
  }

  private static String operationPath(String rawPath) {
    String path = rawPath.replaceAll("[.,;:]+$", "");
    int query = path.indexOf('?');
    return query >= 0 ? path.substring(0, query) : path;
  }

  private static boolean opensRouteCodeSpan(String line, Matcher route) {
    int cursor = route.start(1) - 1;
    while (cursor >= 0 && Character.isWhitespace(line.charAt(cursor))) cursor--;
    return cursor >= 0 && line.charAt(cursor) == '`';
  }

  private static boolean closesRouteCodeSpan(String line, Matcher route) {
    return route.end(2) < line.length() && line.charAt(route.end(2)) == '`';
  }

  private static boolean withinPrefix(String path, String prefix) {
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }

  private static boolean isFrameworkRoute(String path) {
    String leaf = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    return FRAMEWORK_ROUTE_LEAVES.contains(leaf) || "openapi.json".equals(leaf);
  }

  private static String evidence(PlannedTask task) {
    return safe(task.title())
        + "\n"
        + safe(task.description())
        + "\n"
        + String.join("\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
  }
}
