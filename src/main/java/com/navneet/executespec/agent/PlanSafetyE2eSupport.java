package com.navneet.executespec.agent;

import java.util.List;
import java.util.Locale;

/**
 * Repairs browser-harness setup so a clean database is migrated and business setup stays UI-only.
 */
final class PlanSafetyE2eSupport {
  private PlanSafetyE2eSupport() {}

  static boolean isPythonPlaywrightRunner(PlannedTask task, String evidence) {
    if (evidence.contains("pytest-playwright")) return true;
    if (task.touchesFiles() == null) return false;
    List<String> files =
        task.touchesFiles().stream()
            .map(
                value ->
                    PlanSafetyEvidenceSupport.safe(value)
                        .replace('\\', '/')
                        .toLowerCase(Locale.ROOT))
            .toList();
    boolean ownsPythonTest =
        files.stream()
            .anyMatch(
                path ->
                    path.endsWith(".py")
                        && (path.startsWith("tests/")
                            || path.contains("/tests/")
                            || path.startsWith("e2e/")
                            || path.contains("/e2e/")));
    if (!ownsPythonTest) return false;
    boolean ownsDedicatedPythonBrowserTest =
        files.stream()
            .anyMatch(
                path ->
                    path.endsWith(".py")
                        && (path.startsWith("e2e/")
                            || path.contains("/e2e/")
                            || path.contains("playwright")
                            || path.contains("browser")));
    boolean ownsNodeRunner =
        files.stream()
            .anyMatch(
                path ->
                    path.endsWith("package.json")
                        || path.contains("playwright.config.")
                        || path.endsWith(".spec.ts")
                        || path.endsWith(".spec.js"));
    if (!ownsNodeRunner) return true;
    return ownsDedicatedPythonBrowserTest
        && (evidence.contains("pytest") || evidence.contains("conftest.py"));
  }

  static boolean launchesRealServerSubprocess(String evidence) {
    String lower = PlanSafetyEvidenceSupport.safe(evidence).toLowerCase(Locale.ROOT);
    boolean subprocess =
        lower.contains("subprocess")
            || lower.contains("popen")
            || lower.contains("processbuilder")
            || lower.contains("child_process.spawn");
    return subprocess
        && lower.matches(
            "(?s).*\\b(?:start|launch|run)\\w*\\b.{0,140}"
                + "\\b(?:server|backend|application|service|uvicorn|gunicorn|spring-boot|"
                + "express|node)\\b.*");
  }

  static boolean hasBoundedReadinessProbe(String evidence) {
    String lower = PlanSafetyEvidenceSupport.safe(evidence).toLowerCase(Locale.ROOT);
    boolean readiness =
        lower.matches(
            "(?s).*\\b(?:readiness|ready|poll|wait until|health check|health/readiness)\\b.*");
    boolean bounded =
        lower.matches(
            "(?s).*\\b(?:bounded|timeout|time limit|deadline|\\d+\\s*(?:second|minute)s?)\\b.*");
    return readiness && bounded;
  }

  static String normalizeBrowserHarness(String value) {
    return PlanSafetyEvidenceSupport.safe(value)
        .replaceAll(
            "(?i)(cd\\s+\\.\\./backend\\s*&&\\s*)(?!alembic upgrade head\\s*&&\\s*)"
                + "(uvicorn\\s+app\\.main:app)",
            "$1alembic upgrade head && $2")
        .replaceAll(
            "(?i)seed(?:s|ing)? its own data via the UI/API", "seeds its own data via the UI")
        .replaceAll(
            "(?i)seed(?:s|ing)? its own data via the UI or API", "seeds its own data via the UI");
  }

  static boolean usesFastApiViteStack(Plan plan) {
    String evidence = planEvidence(plan);
    return (evidence.contains("fastapi") || evidence.contains("uvicorn"))
        && (evidence.contains("vite") || evidence.contains("vite.config"));
  }

  static boolean usesAlembic(Plan plan) {
    return planEvidence(plan).contains("alembic");
  }

  static boolean usesSqlite(Plan plan) {
    return planEvidence(plan).contains("sqlite");
  }

  static boolean hasBackendFrontendLifecycle(String evidence, boolean fastApiVite) {
    if (fastApiVite) return evidence.contains("uvicorn") && evidence.contains("vite");
    return evidence.contains("backend")
        && evidence.contains("frontend")
        && evidence.matches(
            "(?s).*\\b(?:start|launch|run|command|process|webserver|subprocess)\\w*\\b.*");
  }

  static boolean hasIsolatedTestState(String evidence, boolean fastApiVite) {
    if (fastApiVite) {
      return evidence.contains("database_url")
          && evidence.matches("(?s).*\\b(?:fresh|isolated|temporary|unique)\\b.*database_url.*");
    }
    return evidence.matches(
        "(?s).*\\b(?:fresh|isolated|temporary|unique)\\b.{0,100}"
            + "\\b(?:test\\s+(?:state|database|data|environment)|database|data\\s+store)\\b.*");
  }

  private static String planEvidence(Plan plan) {
    if (plan == null || plan.tasks() == null) return "";
    return plan.tasks().stream()
        .filter(java.util.Objects::nonNull)
        .map(
            task ->
                PlanSafetyEvidenceSupport.safe(task.title())
                    + "\n"
                    + PlanSafetyEvidenceSupport.safe(task.description())
                    + "\n"
                    + String.join(
                        "\n", task.touchesFiles() == null ? List.of() : task.touchesFiles())
                    + "\n"
                    + String.join(
                        "\n", task.hardConstraints() == null ? List.of() : task.hardConstraints()))
        .collect(java.util.stream.Collectors.joining("\n"))
        .toLowerCase(Locale.ROOT);
  }
}
