package com.navneet.executespec.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.reviewer.ReviewerResult;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.state.entity.PipelineRun;

import lombok.extern.slf4j.Slf4j;

/**
 * Stateless helpers extracted from {@link RunOrchestrator} (god-class split, increment 1 — audit
 * #43). Pure {@code static} functions with no run-lifecycle state and no injected collaborators:
 * repo-id (de)serialisation, V1 status mapping, workspace file walking, string truncation, provider
 * coercion. Behaviour is byte-identical to the originals — RunOrchestrator now reaches them via
 * static import, shrinking the god-class with zero behaviour change.
 */
@Slf4j
final class RunPipelineSupport {

  private RunPipelineSupport() {}

  /** Inverse of {@code serializeRepoIds} used at submit-time. */
  public static List<UUID> deserializeRepoIds(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
      java.util.List<UUID> out = new java.util.ArrayList<>();
      if (arr.isArray()) {
        for (var n : arr) {
          out.add(UUID.fromString(n.asText()));
        }
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  /** Serialise repo IDs to a compact JSON array string, or null when empty. */
  public static String serializeRepoIds(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) return null;
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < ids.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append('"').append(ids.get(i).toString()).append('"');
    }
    return sb.append(']').toString();
  }

  /**
   * Best-effort repo count for the cost estimate. For project-scoped runs the count is the number
   * of selected/linked repos in the workspace. For standalone runs the count is 1 when a repo URL
   * is set, 0 otherwise. Audit 2026-05-13 Stage 1 — feeds the reviewer's per-extra- repo wiring
   * overhead in the estimator.
   */
  public static int computeRepoCountForEstimate(PipelineRun runRow) {
    if (runRow == null) return 1;
    if (runRow.getProjectId() != null) {
      List<UUID> ids = deserializeRepoIds(runRow.getSelectedRepoIds());
      if (!ids.isEmpty()) return ids.size();
      // Project run with no explicit selectedRepoIds — fall back to 1 to avoid a
      // 0-cost codemap term that hides per-repo overhead on legacy rows.
      return 1;
    }
    return (runRow.getRepoUrl() != null && !runRow.getRepoUrl().isBlank()) ? 1 : 0;
  }

  public static boolean isTerminal(String status) {
    return "COMPLETE".equals(status)
        || "FAILED".equals(status)
        || "CANCELLED".equals(status)
        || "PARTIAL".equals(status);
  }

  public static String mapOverallToV1Status(ExecutionResult.Overall overall) {
    return switch (overall) {
      case ALL_SUCCESS -> "COMPLETE";
      case PARTIAL_WITH_WARNINGS -> "PARTIAL";
      case FAILED -> "FAILED";
      case ABORTED -> "FAILED";
    };
  }

  public static String mapTaskStatusToV1(TaskOutcome.Status status) {
    return switch (status) {
      case SUCCESS, SUCCESS_AFTER_RETRY -> "COMPLETE";
      case COMPLETED_WITH_WARNING -> "PARTIAL";
      case WORKER_FAILED -> "FAILED";
      case SKIPPED -> "BLOCKED";
    };
  }

  public static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  /**
   * Build a short user-facing description of what the reviewer auto-fixed during this run. Prefers
   * the reviewer's own pass-summary text (the model's completeTask() output) so the description
   * reads like a human-written changelog line. Falls back to a generic message if both passes were
   * silent.
   */
  public static String describeReviewerFixes(ReviewerResult result) {
    String general = result.generalPassSummary();
    String wiring = result.wiringPassSummary();
    StringBuilder sb = new StringBuilder("Reviewer auto-fixed issues during the review pass.");
    if (general != null && !general.isBlank()) {
      sb.append(" General pass: ").append(truncate(general.trim(), 160));
    }
    if (wiring != null && !wiring.isBlank()) {
      sb.append(" Wiring pass: ").append(truncate(wiring.trim(), 160));
    }
    return truncate(sb.toString(), 480);
  }

  public static String lastPathSegment(String fullName) {
    if (fullName == null) return "repo";
    int slash = fullName.lastIndexOf('/');
    return slash < 0 ? fullName : fullName.substring(slash + 1);
  }

  /**
   * Fallback when sub-outline fails: collapse the EPIC into a single LEAF carrying the title +
   * description so the worker still sees the work.
   */
  public static PlannedTask coerceToLeafFallback(PlannedTask epic) {
    return PlannedTask.builder()
        .id(epic.id())
        .title(epic.title())
        .description(
            epic.description() == null || epic.description().isBlank()
                ? "Implement: " + epic.title() + " (sub-outline failed; treat as a single task)"
                : epic.description())
        .complexity(epic.complexity() == null ? PlannedTask.Complexity.HIGH : epic.complexity())
        .touchesFiles(epic.touchesFiles())
        .dependsOn(epic.dependsOn())
        .hardConstraints(epic.hardConstraints())
        .meetsRequirements(epic.meetsRequirements())
        .taskType(PlannedTask.TaskType.LEAF)
        .build();
  }

  public static String shortMsg(Exception e) {
    String msg = e.getMessage();
    String s = e.getClass().getSimpleName() + ": " + (msg == null ? "" : msg);
    return s.length() > 500 ? s.substring(0, 500) + "..." : s;
  }

  @SuppressWarnings("unused")
  public static String readWorkspaceFromLog(IOException ignored) {
    return null;
  }

  /**
   * Walks the run workspace and returns relative paths of all non-hidden, non-build files. Used to
   * populate {@link com.navneet.executespec.agent.model.WorkerReport#getFilesCreated()} so the
   * validation report's {@code filesGenerated} and {@code languages} fields have real data.
   */
  public static List<String> listWorkspaceFiles(Path workspace) {
    if (workspace == null || !Files.isDirectory(workspace)) return List.of();
    java.util.Set<String> skipDirs =
        java.util.Set.of(
            ".git",
            ".agent-context",
            "node_modules",
            "target",
            "build",
            "dist",
            ".gradle",
            ".idea",
            ".vscode",
            "__pycache__",
            "venv",
            ".venv");
    List<String> out = new java.util.ArrayList<>();
    try (var stream = Files.walk(workspace)) {
      stream
          .filter(Files::isRegularFile)
          .filter(
              p -> {
                for (Path part : workspace.relativize(p)) {
                  String name = part.toString();
                  if (skipDirs.contains(name)) return false;
                  if (name.startsWith(".") && !".env.example".equals(name)) return false;
                }
                return true;
              })
          .forEach(p -> out.add(workspace.relativize(p).toString()));
    } catch (IOException e) {
      log.debug("listWorkspaceFiles failed at {}: {}", workspace, e.getMessage());
    }
    return out;
  }

  public static LlmProvider providerForAccuracy(String provider) {
    if (provider == null) return LlmProvider.OPENAI;
    String up = provider.toUpperCase();
    if ("GEMINI".equals(up)) return LlmProvider.GEMINI;
    try {
      return LlmProvider.valueOf(up);
    } catch (IllegalArgumentException e) {
      return LlmProvider.OPENAI;
    }
  }

  /**
   * Map a detected (language, framework) pair to a short worker-actionable convention hint.
   * Conservative: when the language doesn't match any known mapping, return a generic "match the
   * existing repo conventions" line so the planner at least sees the language name in the parity
   * block. (Relocated from RunOrchestrator — god-class split inc-3b; pinned by {@code
   * LanguageParityBlockTest}.)
   */
  public static String languageConventionHint(String language, String framework) {
    String l = language == null ? "" : language.trim().toLowerCase();
    return switch (l) {
      case "python" ->
          "use snake_case `.py` filenames, `os.getenv()` / pydantic-settings for config,"
              + " pytest for tests.";
      case "typescript", "typescript/js", "javascript", "node", "nodejs" ->
          "use kebab-case or camelCase `.ts` / `.tsx` filenames (match what's already in the"
              + " repo), `process.env` for config, vitest / jest for tests.";
      case "go", "golang" ->
          "use lower_snake_case `.go` filenames, `os.Getenv` for config, `_test.go` for tests.";
      case "rust" ->
          "use snake_case `.rs` filenames, `std::env::var` for config, `#[cfg(test)]` modules"
              + " or `tests/` dir.";
      case "java" ->
          "use PascalCase `.java` filenames, Spring `@Value` / `application.yml` for config,"
              + " JUnit for tests.";
      case "kotlin" ->
          "use PascalCase `.kt` filenames, Spring `@Value` / `application.yml` for config,"
              + " JUnit / Kotest for tests.";
      case "ruby" -> "use snake_case `.rb` filenames, `ENV[]` for config, RSpec for tests.";
      case "csharp", "c#", "cs" ->
          "use PascalCase `.cs` filenames, `IConfiguration` / `appsettings.json` for config,"
              + " xUnit for tests.";
      default -> "match the existing repo's filename + config + test conventions.";
    };
  }
}
