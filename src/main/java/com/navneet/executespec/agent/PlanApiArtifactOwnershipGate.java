package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanApiContractEvidence.displayPath;
import static com.navneet.executespec.agent.PlanApiContractEvidence.extract;
import static com.navneet.executespec.agent.PlanApiContractEvidence.mergeEvidence;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isFrontendOnlyTask;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isTestOnlyTask;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.navneet.executespec.agent.PlanApiContractEvidence.ApiEvidence;

/** Rejects implementation operations claimed outside a uniquely owned route artifact. */
final class PlanApiArtifactOwnershipGate {
  private PlanApiArtifactOwnershipGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return;
    Map<String, String> routeArtifactOwners =
        PlanApiProducerContractReconciler.uniqueRouteArtifactOwnerIds(plan.tasks());
    if (routeArtifactOwners.isEmpty()) return;
    Map<String, PlannedTask> taskById =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .collect(
                Collectors.toMap(
                    PlannedTask::id,
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    List<ApiEvidence> producers =
        mergeEvidence(
            plan.tasks().stream()
                .filter(java.util.Objects::nonNull)
                .filter(task -> !isFrontendOnlyTask(task) && !isTestOnlyTask(task))
                .filter(PlanApiContractEvidence::isImplementationProducerTask)
                .flatMap(task -> extract(task).stream())
                .toList());
    for (ApiEvidence producer : producers) {
      String artifactOwner =
          routeArtifactOwners.get(PlanApiProducerContractReconciler.routeResource(producer.path()));
      if (artifactOwner == null
          || artifactOwner.equals(producer.taskId())
          || !claimsOperationOwnership(taskById.get(producer.taskId()), producer)) continue;
      violations.add(
          producer.taskId()
              + ": declares "
              + producer.method()
              + " "
              + displayPath(producer.path())
              + " but concrete route artifact ownership belongs to "
              + artifactOwner);
    }
  }

  private static boolean claimsOperationOwnership(PlannedTask task, ApiEvidence endpoint) {
    if (safe(endpoint.context()).contains("api producer contracts")) return true;
    if (task == null) return false;
    String evidence =
        safe(task.title())
            + "\n"
            + safe(task.description())
            + "\n"
            + String.join(
                "\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
    String routeTemplate = displayPath(endpoint.path()).replace("{id}", "PARAMETER_TOKEN");
    String operation =
        Pattern.quote(endpoint.method())
            + "\\s+"
            + Pattern.quote(routeTemplate).replace("PARAMETER_TOKEN", "\\E\\{[^}]+\\}\\Q");
    return evidence.matches(
        "(?is).*\\b(?:api\\s+producer\\s+contracts?|expose|implement|provide|declare|register|"
            + "mount|handle|route|endpoint|controller)\\b[^.\\n]{0,180}"
            + operation
            + ".*");
  }
}
