package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agent.PlanApiContractEvidence.ApiEvidence;

/** Recognizes framework-provided routes that are explicitly required by the plan. */
final class PlanApiFrameworkRouteSupport {
  private static final Pattern NEGATED_DOCUMENTATION =
      Pattern.compile(
          "(?is)(?:\\b(?:do not|don't|must not|never|without|disable|disabled|no)\\b"
              + ".{0,60}\\b(?:openapi|docs?|documentation|swagger)\\b|"
              + "\\b(?:openapi|docs?|documentation|swagger)\\b.{0,40}"
              + "\\b(?:must not|disabled|off|none)\\b)");
  private static final Pattern OPENAPI_ROUTE_AUTHORITY =
      Pattern.compile(
          "(?is)(?:/openapi\\.json\\b|\\bopenapi\\b.{0,30}\\b(?:endpoints?|routes?)\\b|"
              + "\\b(?:endpoints?|routes?)\\b.{0,30}\\bopenapi\\b)");
  private static final Pattern DOCS_ROUTE_AUTHORITY =
      Pattern.compile(
          "(?is)(?:/docs\\b|\\bdocs?\\b.{0,30}\\b(?:endpoints?|routes?)\\b|"
              + "\\b(?:swagger|interactive\\s+api)\\s+(?:ui|docs?|documentation)\\b)");
  private static final Pattern ROUTE_CONFIGURATION =
      Pattern.compile("(?i)\\b(openapi_url|docs_url)\\s*=\\s*([\"']?)([^,\"')\\s]+)\\2");

  private PlanApiFrameworkRouteSupport() {}

  static String canonicalizePrefixedRoute(Plan plan, ApiEvidence consumer) {
    String rawPath = consumer.rawPath();
    int queryStart = rawPath.indexOf('?');
    String path =
        (queryStart < 0 ? rawPath : rawPath.substring(0, queryStart)).toLowerCase(Locale.ROOT);
    if (!Set.of("/api/openapi.json", "/api/docs").contains(path)
        || !isExplicitFrameworkRoute(plan, consumer)) {
      return null;
    }
    return path.substring(4) + (queryStart < 0 ? "" : rawPath.substring(queryStart));
  }

  static boolean isExplicitFrameworkRoute(Plan plan, ApiEvidence consumer) {
    if (!consumer.method().equals("GET")
        || !Set.of("/openapi.json", "/docs").contains(consumer.path())) return false;
    String requirements =
        plan.requirements() == null
            ? ""
            : plan.requirements().stream()
                .map(RequirementSpec::text)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
    String planEvidence =
        (safe(plan.planSummary())
                + " "
                + plan.tasks().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(task -> safe(task.title()) + " " + safe(task.description()))
                    .collect(java.util.stream.Collectors.joining(" ")))
            .toLowerCase(Locale.ROOT);
    if (!planEvidence.contains("fastapi")
        || NEGATED_DOCUMENTATION.matcher(requirements).find()
        || routeDisabledOrCustomized(planEvidence, consumer.path())) {
      return false;
    }
    return consumer.path().equals("/openapi.json")
        ? OPENAPI_ROUTE_AUTHORITY.matcher(requirements).find()
        : DOCS_ROUTE_AUTHORITY.matcher(requirements).find();
  }

  private static boolean routeDisabledOrCustomized(String evidence, String path) {
    String setting = path.equals("/openapi.json") ? "openapi_url" : "docs_url";
    String expected = path.equals("/openapi.json") ? "/openapi.json" : "/docs";
    Matcher configuration = ROUTE_CONFIGURATION.matcher(evidence);
    while (configuration.find()) {
      if (!configuration.group(1).equalsIgnoreCase(setting)) continue;
      String configured = configuration.group(3);
      return configured.equalsIgnoreCase("none") || !configured.equals(expected);
    }
    return false;
  }
}
