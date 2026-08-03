package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanApiContractEvidence.canonicalIdentityPath;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isFrontendOnlyTask;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isTestOnlyTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.navneet.executespec.contractsurface.ExactHttpConstraintParser;
import com.navneet.executespec.contractsurface.ExactHttpConstraintParser.Polarity;

/** Fails closed when plan normalization changes an exact source HTTP route namespace. */
final class PlanSourceHttpRouteGate {
  private PlanSourceHttpRouteGate() {}

  static List<String> violations(String originalRequest, Plan plan) {
    SourceOperations source = sourceOperations(originalRequest);
    if ((source.required().isEmpty() && source.forbidden().isEmpty())
        || plan == null
        || plan.tasks() == null) return List.of();

    Set<Operation> producerOperations = new LinkedHashSet<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null || isFrontendOnlyTask(task) || isTestOnlyTask(task)) continue;
      String evidence =
          task.title()
              + "\n"
              + task.description()
              + "\n"
              + String.join("\n", task.hardConstraints());
      producerOperations.addAll(sourceOperations(evidence).required());
    }

    List<String> violations = new ArrayList<>();
    for (Operation operation : source.required()) {
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
    for (Operation operation : source.forbidden()) {
      if (producerOperations.contains(operation)) {
        violations.add(
            "SOURCE_HTTP_FORBIDDEN_METHOD_PRESENT: executable producer plan must preserve the "
                + "source's method-not-allowed contract for "
                + operation.display());
      }
    }
    return List.copyOf(new LinkedHashSet<>(violations));
  }

  private static SourceOperations sourceOperations(String evidence) {
    Set<Operation> required = new LinkedHashSet<>();
    Set<Operation> forbidden = new LinkedHashSet<>();
    for (var constraint : ExactHttpConstraintParser.parse(evidence)) {
      Operation operation = new Operation(constraint.method(), constraint.path());
      if (constraint.polarity() == Polarity.METHOD_NOT_ALLOWED) {
        forbidden.add(operation);
        required.remove(operation);
      } else if (!forbidden.contains(operation)) {
        required.add(operation);
      }
    }
    return new SourceOperations(Set.copyOf(required), Set.copyOf(forbidden));
  }

  private record SourceOperations(Set<Operation> required, Set<Operation> forbidden) {}

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
