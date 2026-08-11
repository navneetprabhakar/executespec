package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic artifact contract extracted from explicit customer text, never model output. */
public final class SingleShotArtifactContract {

  private static final Pattern BACKTICK = Pattern.compile("`([^`]+)`");
  private static final Pattern SAFE_PATH =
      Pattern.compile("[A-Za-z0-9._@+-]+(?:/[A-Za-z0-9._@+-]+)*\\.[A-Za-z0-9]{1,12}");

  private SingleShotArtifactContract() {}

  /**
   * Returns only paths explicitly named by the customer. Commands, route paths, package names and
   * prose inside backticks are deliberately excluded.
   */
  public static List<String> explicitRequiredPaths(String specification) {
    if (specification == null || specification.isBlank()) return List.of();
    Set<String> paths = new LinkedHashSet<>();
    Matcher matcher = BACKTICK.matcher(specification);
    while (matcher.find()) {
      String candidate = matcher.group(1).trim().replace('\\', '/');
      if (candidate.startsWith("/") || candidate.contains("..") || candidate.contains(" ")) {
        continue;
      }
      if (SAFE_PATH.matcher(candidate).matches()) paths.add(candidate);
    }
    return List.copyOf(paths);
  }

  /**
   * Binds customer-named outputs, or qualified platform-adapter defaults, to the task most likely
   * to own them. Planner-emitted artifact authority is deliberately ignored: a model may suggest
   * files, but it cannot strengthen the deterministic acceptance contract. When no task advertises
   * the exact path as a touch hint, the first executable task owns it.
   */
  public static Plan bindRequiredPaths(Plan plan, String specification) {
    if (plan == null || plan.tasks().isEmpty()) return plan;
    List<String> required = requiredPaths(plan, specification);
    if (required.isEmpty()) {
      return plan.toBuilder()
          .tasks(
              plan.tasks().stream()
                  .map(task -> task.toBuilder().requiredArtifacts(List.of()).build())
                  .toList())
          .build();
    }

    List<List<String>> assignments = new ArrayList<>(plan.tasks().size());
    for (int i = 0; i < plan.tasks().size(); i++) assignments.add(new ArrayList<>());
    for (String artifact : required) {
      int owner = 0;
      for (int i = 0; i < plan.tasks().size(); i++) {
        if (plan.tasks().get(i).touchesFiles().stream()
            .map(SingleShotArtifactContract::normalize)
            .anyMatch(normalize(artifact)::equals)) {
          owner = i;
          break;
        }
      }
      assignments.get(owner).add(artifact);
    }

    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (int i = 0; i < plan.tasks().size(); i++) {
      tasks.add(
          plan.tasks().get(i).toBuilder()
              .requiredArtifacts(List.copyOf(assignments.get(i)))
              .build());
    }
    return plan.toBuilder().tasks(List.copyOf(tasks)).build();
  }

  /** Platform-owned defaults exist only for a qualified runtime adapter. */
  public static List<String> requiredPaths(Plan plan, String specification) {
    return requiredPaths(
        plan == null ? null : plan.projectType(),
        plan == null ? null : plan.runtimeSurface(),
        specification);
  }

  public static List<String> requiredPaths(
      String projectType, Plan.RuntimeSurface runtimeSurface, String specification) {
    List<String> explicit = explicitRequiredPaths(specification);
    if (!explicit.isEmpty()) return explicit;
    if ("python".equalsIgnoreCase(projectType)
        && runtimeSurface != Plan.RuntimeSurface.STATIC_WEB
        && runtimeSurface != Plan.RuntimeSurface.MOBILE_APP) {
      return List.of("main.py", "requirements.txt");
    }
    return List.of();
  }

  private static String normalize(String path) {
    return path == null ? "" : path.trim().replace('\\', '/');
  }
}
