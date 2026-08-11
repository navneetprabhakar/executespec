package com.navneet.executespec.workspacegeneration;

import java.util.List;
import java.util.Locale;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;

/** Provider-free extraction of HTTP method/path requirements owned by one planned task. */
public final class HttpEndpointContract {

  private HttpEndpointContract() {}

  public static List<String> from(PlannedTask task, Plan plan) {
    if (task == null || plan == null || plan.runtimeSurface() != Plan.RuntimeSurface.HTTP) {
      return List.of();
    }
    StringBuilder source = new StringBuilder();
    source.append(task.title()).append('\n').append(task.description()).append('\n');
    task.hardConstraints().forEach(value -> source.append(value).append('\n'));
    if (plan.requirements() != null) {
      java.util.Set<String> owned = java.util.Set.copyOf(task.meetsRequirements());
      plan.requirements().stream()
          .filter(requirement -> owned.isEmpty() || owned.contains(requirement.id()))
          .map(com.navneet.executespec.agent.RequirementSpec::text)
          .filter(java.util.Objects::nonNull)
          .forEach(value -> source.append(value).append('\n'));
    }
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile(
                "(?i)\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+(/[A-Za-z0-9._~!$&'()*+,;=:@%{}/-]*)")
            .matcher(source);
    java.util.Set<String> endpoints = new java.util.TreeSet<>();
    while (matcher.find()) {
      String path = matcher.group(2).replaceFirst("[,.;:]+$", "");
      endpoints.add(matcher.group(1).toUpperCase(Locale.ROOT) + " " + path);
    }
    return List.copyOf(endpoints);
  }

  /** HTTP path-parameter names do not change the route's externally reachable URL shape. */
  static String canonicalPath(String path) {
    if (path == null) return null;
    return path.replaceAll("\\{[^/{}]+}", "{}");
  }
}
