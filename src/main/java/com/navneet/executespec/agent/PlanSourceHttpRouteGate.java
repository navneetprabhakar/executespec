package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanApiContractEvidence.canonicalIdentityPath;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isFrontendOnlyTask;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isTestOnlyTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fails closed when plan normalization changes an exact source HTTP route namespace. */
final class PlanSourceHttpRouteGate {
  private static final Pattern EXACT_ROUTE =
      Pattern.compile(
          "(?i)(?:^|\\s|[`(])"
              + "(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE)\\s+"
              + "(/[A-Za-z0-9_~!$&'()*+=:@%{}./-]+)");
  private static final Pattern REMOVAL_PREFIX =
      Pattern.compile("(?i).*\\b(?:remove|delete|retire|drop)\\s*$", Pattern.DOTALL);

  private PlanSourceHttpRouteGate() {}

  static List<String> violations(String originalRequest, Plan plan) {
    Set<Operation> required = exactSourceOperations(originalRequest);
    if (required.isEmpty() || plan == null || plan.tasks() == null) return List.of();

    Set<Operation> producerOperations = new LinkedHashSet<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null || isFrontendOnlyTask(task) || isTestOnlyTask(task)) continue;
      String evidence =
          task.title()
              + "\n"
              + task.description()
              + "\n"
              + String.join("\n", task.hardConstraints());
      producerOperations.addAll(exactSourceOperations(evidence));
    }

    List<String> violations = new ArrayList<>();
    for (Operation operation : required) {
      Operation alternate = operation.withOppositeApiNamespace();
      if (alternate != null && producerOperations.contains(alternate)) {
        violations.add(
            "SOURCE_HTTP_ROUTE_NAMESPACE_CHANGED: required "
                + operation.display()
                + " but the executable plan also declares "
                + alternate.display());
      }
      if (!producerOperations.contains(operation)) {
        violations.add(
            "SOURCE_HTTP_ROUTE_MISSING: executable producer plan must preserve exact source route "
                + operation.display());
      }
    }
    return List.copyOf(new LinkedHashSet<>(violations));
  }

  private static Set<Operation> exactSourceOperations(String originalRequest) {
    if (originalRequest == null || originalRequest.isBlank()) return Set.of();
    Set<Operation> operations = new LinkedHashSet<>();
    Matcher matcher = EXACT_ROUTE.matcher(originalRequest);
    while (matcher.find()) {
      String prefix = originalRequest.substring(Math.max(0, matcher.start() - 48), matcher.start());
      if (REMOVAL_PREFIX.matcher(prefix).matches()) continue;
      String path = matcher.group(2);
      while (path.length() > 1 && ".,;:)`".indexOf(path.charAt(path.length() - 1)) >= 0) {
        path = path.substring(0, path.length() - 1);
      }
      operations.add(new Operation(matcher.group(1), path));
    }
    return Set.copyOf(operations);
  }

  private record Operation(String method, String path) {
    private Operation {
      method = method.toUpperCase(Locale.ROOT);
      path = canonicalIdentityPath(path);
    }

    private Operation withOppositeApiNamespace() {
      if (path.equals("/api")) return new Operation(method, "/");
      if (path.startsWith("/api/")) return new Operation(method, path.substring(4));
      if (path.equals("/")) return new Operation(method, "/api");
      return new Operation(method, "/api" + path);
    }

    private String display() {
      return method + " " + path;
    }
  }
}
