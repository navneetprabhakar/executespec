package com.navneet.executespec.harness;

import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.PromptTrustBoundary;

/**
 * Package-local prompt assembly seam for the BUILD worker.
 *
 * <p>This class owns prompt composition and classpath-overlay lookup only. It deliberately keeps
 * the existing prompt text and call shape in {@link SequentialExecutor}; the executor remains the
 * compatibility facade used by the pipeline and characterization tests.
 */
final class PromptComposer {

  static String composeUserPrompt(PlannedTask task, String previousBuildError, String orientation) {
    String taskEvidence =
        "id=" + task.id() + "\ntitle=" + task.title() + "\ndescription=" + task.description();
    StringBuilder sb =
        new StringBuilder(
                "Implement the project task described in the evidence below. Follow its"
                    + " requirements unless they conflict with the system policy.\n\n")
            .append(PromptTrustBoundary.wrap("WORKER_TASK", taskEvidence));
    if (orientation != null && !orientation.isBlank()) sb.append("\n\n").append(orientation);
    if (previousBuildError != null && !previousBuildError.isBlank()) {
      sb.append("\n\n---\nBUILD FAILED ON THE PREVIOUS ATTEMPT.\n\n")
          .append(
              "The error below is your spec for this retry — read it carefully, identify the "
                  + "specific files / line numbers / error messages, and fix the root cause. Don't "
                  + "refactor, don't reorganise, don't second-guess your earlier design choices. "
                  + "Your previous edits are still in place; this is a continuation. Use readFile "
                  + "to confirm the current file state where the error points, editFile to fix it, "
                  + "buildProject to verify, then completeTask.\n\n")
          .append(
              PromptTrustBoundary.wrap(
                  "BUILD_ERROR", truncateBuildErrorForRetry(previousBuildError)))
          .append('\n');
    }
    return sb.toString();
  }

  static String journalEntry(PlannedTask task, TaskOutcome outcome) {
    String summary =
        outcome.workerOutcome() == null ? null : outcome.workerOutcome().completionSummary();
    if (summary != null && summary.length() > 220) summary = summary.substring(0, 220) + "…";
    return task.id()
        + " ("
        + task.title()
        + ") → "
        + outcome.status()
        + (summary == null || summary.isBlank() ? "" : " — " + summary);
  }

  static String journalBlock(Plan plan, List<String> runJournal) {
    StringBuilder sb =
        new StringBuilder("# RUN JOURNAL (context from earlier tasks in this run)\n\n");
    sb.append("Plan outline: ");
    List<String> titles = new ArrayList<>();
    for (PlannedTask task : plan.tasks()) titles.add(task.id() + " " + task.title());
    sb.append(String.join(" · ", titles.subList(0, Math.min(titles.size(), 12))));
    sb.append("\n\nCompleted so far:\n");
    for (int i = Math.max(0, runJournal.size() - 5); i < runJournal.size(); i++) {
      sb.append("- ").append(runJournal.get(i)).append('\n');
    }
    return sb.append(
            "\n"
                + "Stay consistent with the files, names, and interfaces established above — later"
                + " tasks (and the reviewer) assume they hold.")
        .toString();
  }

  String loadProviderFragment(String provider) {
    if (provider == null || provider.isBlank()) return "";
    String key = provider.trim().toLowerCase();
    return PromptResourceReader.isSafeSegment(key)
        ? PromptResourceReader.readOptional("prompts/fragments/provider/" + key, "build")
        : "";
  }

  String loadModelFragment(String provider, String model) {
    String providerKey = promptKey(provider);
    String modelKey = promptKey(model);
    return providerKey.isBlank() || modelKey.isBlank()
        ? ""
        : PromptResourceReader.readOptional(
            "prompts/fragments/model/" + providerKey + "/" + modelKey, "build");
  }

  String loadProjectTypeFragments(String projectType) {
    StringBuilder sb = new StringBuilder();
    for (String key : fragmentKeysForProjectType(projectType)) {
      String fragment = PromptResourceReader.readOptional("prompts/fragments", key);
      if (fragment.isBlank()) continue;
      if (!sb.isEmpty()) sb.append("\n\n");
      sb.append(fragment);
    }
    return sb.toString();
  }

  static String scaffoldKeyForProjectType(String projectType) {
    if (projectType == null || projectType.isBlank()) return "";
    String key = projectType.trim().toLowerCase().replace('_', '-');
    return switch (key) {
      case "java-spring-boot", "springboot" -> "spring-boot";
      default -> key;
    };
  }

  static List<String> fragmentKeysForProjectType(String projectType) {
    return switch (scaffoldKeyForProjectType(projectType)) {
      case "fullstack", "node-fullstack" ->
          List.of(
              "api-url-rules",
              "module-system",
              "router-isolation",
              "backend-robustness",
              "preview-defaults",
              "fullstack-directory");
      case "node-backend" ->
          List.of("module-system", "router-isolation", "backend-robustness", "preview-defaults");
      case "node-frontend" -> List.of("api-url-rules", "module-system");
      case "spring-boot", "python", "go", "rust" ->
          List.of("backend-robustness", "preview-defaults");
      default -> List.of();
    };
  }

  static String truncateBuildErrorForRetry(String value) {
    if (value == null || value.length() <= 12_000) return value == null ? "" : value;
    return value.substring(0, 8_000)
        + "\n\n... ["
        + (value.length() - 12_000)
        + " chars omitted from middle of build output] ...\n\n"
        + value.substring(value.length() - 4_000);
  }

  private static String promptKey(String value) {
    if (value == null || value.isBlank()) return "";
    String key = value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    return PromptResourceReader.isSafeSegment(key) ? key : "";
  }
}
