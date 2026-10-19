package com.navneet.executespec.agent;

import java.util.List;
import java.util.Locale;

/** Preflight for a Python bootstrap that otherwise cannot meet the frozen TEST proof contract. */
final class PlanBootstrapVerificationContract {
  private PlanBootstrapVerificationContract() {}

  static List<String> violations(Plan plan) {
    if (!"python".equalsIgnoreCase(plan.projectType()) || plan.tasks().isEmpty()) return List.of();
    var task = plan.tasks().getFirst();
    String title = task.title().toLowerCase(Locale.ROOT);
    boolean manifestSetup =
        task.touchesFiles().stream()
            .anyMatch(
                path ->
                    path.endsWith("requirements.txt")
                        || path.endsWith("pyproject.toml")
                        || path.endsWith("setup.cfg"));
    boolean featureCode =
        task.touchesFiles().stream()
            .anyMatch(path -> path.endsWith(".py") && !path.contains("test"));
    if (!(title.contains("bootstrap")
        || title.contains("scaffold")
        || (manifestSetup && !featureCode))) return List.of();
    boolean tests =
        task.touchesFiles().stream()
            .anyMatch(
                path -> {
                  String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
                  return !normalized.startsWith(".executespec/")
                      && normalized.endsWith(".py")
                      && (normalized.startsWith("test_")
                          || normalized.contains("/test_")
                          || normalized.endsWith("_test.py"));
                });
    if (!tests || task.requiredArtifacts().isEmpty()) {
      return List.of(
          task.id()
              + ": Python bootstrap must own its dependency manifest and maintained pytest"
              + " configuration tests in touchesFiles; test the manifest/bootstrap configuration"
              + " without importing not-yet-implemented feature modules. Keep feature"
              + " implementation and its functional tests in the later feature task. Do not count"
              + " server-owned acceptance tests as candidate tests.");
    }
    return List.of();
  }
}
