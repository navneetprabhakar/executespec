package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Rejects browser-test tasks that combine mutually incompatible Playwright runner contracts. */
final class PlanE2eToolchainGate {
  private static final Pattern CONFTEST_NODE_WEBSERVER_ASSIGNMENT =
      Pattern.compile(
          "(?is)(?:conftest\\.py.{0,240}\\b(?:configure|define|own|use|set\\s+up|provide)s?\\b"
              + ".{0,120}\\bwebserver\\b|\\bwebserver\\b.{0,120}"
              + "\\b(?:in|inside|from|through)\\s+conftest\\.py)");

  private PlanE2eToolchainGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    boolean fastApiVite = PlanSafetyE2eSupport.usesFastApiViteStack(plan);
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      String evidence = evidence(task);
      if (!evidence.contains("playwright")) continue;

      boolean pythonRunner = PlanSafetyE2eSupport.isPythonPlaywrightRunner(task, evidence);
      boolean nodeRunner =
          containsFile(task, "package.json")
              || containsFile(task, "playwright.config.ts")
              || containsFile(task, "playwright.config.js")
              || evidence.contains("@playwright/test")
              || evidence.contains("npx playwright");
      boolean assignsNodeWebServerToConftest =
          pythonRunner && CONFTEST_NODE_WEBSERVER_ASSIGNMENT.matcher(evidence).find();
      boolean browserE2e = isBrowserE2eTask(task, evidence);

      if (pythonRunner && nodeRunner) {
        violations.add(
            task.id()
                + ": Playwright E2E task mixes Python pytest/.py runner artifacts with Node"
                + " package/config artifacts; select one runner with matching dependencies and"
                + " process lifecycle");
      }
      if (assignsNodeWebServerToConftest) {
        violations.add(
            task.id()
                + ": Python conftest.py cannot own Playwright's Node webServer configuration;"
                + " use a Python subprocess fixture or a Node Playwright config, not both");
      }
      if (browserE2e && !(pythonRunner && nodeRunner)) {
        List<String> missing =
            missingRunnerContractParts(task, evidence, pythonRunner, nodeRunner, fastApiVite);
        if (missing.isEmpty()) continue;
        violations.add(
            task.id()
                + ": Playwright E2E task lacks executable contract parts "
                + missing
                + "; declare one runner, dependency, script, isolated environment, readiness,"
                + " process lifecycle, and teardown");
      }
    }
  }

  private static boolean isBrowserE2eTask(PlannedTask task, String evidence) {
    return evidence.contains("end-to-end")
        || evidence.contains("browser journey")
        || (task.touchesFiles() != null
            && task.touchesFiles().stream()
                .map(PlanE2eToolchainGate::normalized)
                .anyMatch(path -> path.contains("/e2e/") || path.startsWith("e2e/")));
  }

  private static List<String> missingRunnerContractParts(
      PlannedTask task,
      String evidence,
      boolean pythonRunner,
      boolean nodeRunner,
      boolean fastApiVite) {
    List<String> missing = new ArrayList<>();
    if (pythonRunner) {
      if (!evidence.contains("pytest-playwright")) missing.add("dependency");
      if (!evidence.matches("(?s).*\\b(?:python\\s+-m\\s+pytest|pytest\\s+[^.\\n]+).*")) {
        missing.add("runner script");
      }
      if (!(evidence.contains("subprocess") || evidence.contains("popen"))
          || !PlanSafetyE2eSupport.hasBackendFrontendLifecycle(evidence, fastApiVite)) {
        missing.add("backend/frontend process lifecycle");
      }
    } else if (nodeRunner) {
      if (!evidence.contains("@playwright/test")) missing.add("dependency");
      if (!evidence.contains("test:e2e") || !evidence.contains("playwright test")) {
        missing.add("runner script");
      }
      if (!evidence.contains("webserver")
          || !PlanSafetyE2eSupport.hasBackendFrontendLifecycle(evidence, fastApiVite)) {
        missing.add("backend/frontend process lifecycle");
      }
    } else {
      missing.add("runner");
    }
    if (!evidence.matches("(?s).*\\b(?:readiness|ready|health|wait\\s+for)\\b.*")) {
      missing.add("readiness probe");
    }
    if (!PlanSafetyE2eSupport.hasIsolatedTestState(evidence, fastApiVite)) {
      missing.add(fastApiVite ? "isolated DATABASE_URL" : "isolated test state");
    }
    if (!evidence.matches("(?s).*\\b(?:teardown|terminate|stop|kill|cleanup)\\b.*")) {
      missing.add("teardown");
    }
    return List.copyOf(missing);
  }

  private static boolean containsFile(PlannedTask task, String suffix) {
    return task.touchesFiles() != null
        && task.touchesFiles().stream()
            .map(PlanE2eToolchainGate::normalized)
            .anyMatch(path -> path.endsWith(suffix));
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

  private static String normalized(String value) {
    return safe(value).replace('\\', '/').toLowerCase(Locale.ROOT);
  }
}
