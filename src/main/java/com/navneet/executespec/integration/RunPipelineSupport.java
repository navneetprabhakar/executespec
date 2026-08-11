package com.navneet.executespec.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.engine.EngineBudget;
import com.navneet.executespec.engine.EngineOutcome;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.reviewer.ReviewerResult;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.state.BenchmarkSourceType;
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
public final class RunPipelineSupport {

  private RunPipelineSupport() {}

  /**
   * Inverse of {@code serializeRepoIds} used at submit-time.
   *
   * <p>Fails CLOSED. Downstream code reads an empty list as "every linked repository is in scope",
   * so silently returning an empty list for malformed JSON or a malformed element would
   * <em>widen</em> a run's blast radius from the selected subset to the whole project. A stored
   * value we cannot parse means the scope is unknown, which is an error, not "all".
   */
  public static List<UUID> deserializeRepoIds(String json) {
    if (json == null || json.isBlank()) return List.of();
    com.fasterxml.jackson.databind.JsonNode arr;
    try {
      arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("Unparseable persisted repository selection", e);
    }
    if (arr == null || arr.isNull()) return List.of();
    if (!arr.isArray()) {
      throw new IllegalStateException("Persisted repository selection is not a JSON array");
    }
    java.util.List<UUID> out = new java.util.ArrayList<>();
    for (var n : arr) {
      String raw = n.asText();
      try {
        out.add(UUID.fromString(raw));
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException("Persisted repository selection contains a non-UUID entry");
      }
    }
    return out;
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
        .requiredArtifacts(epic.requiredArtifacts())
        .dependsOn(epic.dependsOn())
        .hardConstraints(epic.hardConstraints())
        .meetsRequirements(epic.meetsRequirements())
        .taskType(PlannedTask.TaskType.LEAF)
        .build();
  }

  /** Terminal-status reason stamped when a finding PATCH stops without ever proving its change. */
  public static final String PATCH_STOPPED_UNVERIFIED_REASON = "PATCH_STOPPED_UNVERIFIED";

  /** RR-1566 — wall-clock ceiling (seconds) for a CUSTOMER (finding-triggered) PATCH run. */
  private static final int PATCH_WALL_CLOCK_SECONDS = 3600;

  /** RR-1566 — time reserved before the absolute deadline for cleanup + response delivery. */
  private static final long PATCH_SHUTDOWN_RESERVE_MS = 90_000L;

  /**
   * RR-1566 — the {@link EngineBudget} the CUSTOMER PATCH path must pass. It previously passed
   * none, so {@code wallClockSeconds}/{@code absoluteDeadlineEpochMs} were null and a stuck autofix
   * worker could loop the full per-provider iteration cap with no time bound (the dollar cap the
   * only backstop). Benchmark sources return {@code null} (engine/tier defaults) — they own
   * campaign timing. iterCap stays null (per-provider default) and dollarCap stays null (the run
   * row's hardCapUsd bounds spend); the wall clock + absolute deadline are the new bound.
   */
  public static EngineBudget patchEngineBudget(String sourceType) {
    if (BenchmarkSourceType.isControlledWorkerSource(sourceType)) return null;
    long deadline = System.currentTimeMillis() + PATCH_WALL_CLOCK_SECONDS * 1000L;
    return new EngineBudget(
        null, PATCH_WALL_CLOCK_SECONDS, null, deadline, PATCH_SHUTDOWN_RESERVE_MS);
  }

  /**
   * RR-1567 — publication gate for finding-triggered PATCH runs. The HARD_GATE test-verification
   * check only blocks the {@code completeTask} tool, so a worker that never calls {@code
   * completeTask} ({@code STOPPED_WITHOUT_COMPLETE} → {@link EngineOutcome#STOPPED}) never faced
   * the gate and has proven nothing — yet the old outcome map called that COMPLETE and auto-pushed
   * it to a customer PR head branch. The gate is thus bypassable by doing LESS. This makes the
   * publication decision read the same evidence the gate reads: for a {@code finding:} source, only
   * a genuine completion (a {@code completeTask}, which under {@code requireTestVerification} is
   * refused without a passing {@code runTests}, or a stagnation-forced terminal where writes are
   * present) is publishable. {@code STOPPED} / iteration / budget / wall-clock / error outcomes are
   * not.
   *
   * <p>Non-finding sources return {@code true}: benchmark PATCH runs never reach the auto-push
   * block (their diff is captured externally), so this method does not govern them.
   */
  public static boolean patchResultPublishable(EngineOutcome outcome, String sourceType) {
    if (sourceType == null || !sourceType.startsWith("finding:")) return true;
    return outcome == EngineOutcome.COMPLETED || outcome == EngineOutcome.STAGNATED;
  }

  /**
   * RR-1567 — true when a finding PATCH ended unverified-STOPPED (label demoted, not published).
   */
  public static boolean patchStoppedUnverified(EngineOutcome outcome, String sourceType) {
    return outcome == EngineOutcome.STOPPED
        && sourceType != null
        && sourceType.startsWith("finding:");
  }

  public static String shortMsg(Exception e) {
    String msg = e.getMessage();
    String s = e.getClass().getSimpleName() + ": " + (msg == null ? "" : msg);
    return s.length() > 500 ? s.substring(0, 500) + "..." : s;
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
    // RR-404 — prune ignored dependency/build subtrees at the DIRECTORY boundary with SKIP_SUBTREE
    // instead of Files.walk()-then-filter, which fully descended node_modules / target / .git
    // before
    // discarding their files. On a large repo the old shape did filesystem work proportional to
    // every
    // vendored/build artefact. RR-405's symlink-containment guarantee is preserved: walkFileTree
    // does
    // not follow symbolic links unless FOLLOW_LINKS is passed, and a symlink is not a regular file
    // under BasicFileAttributes.isRegularFile(), so a symlinked file/subtree is still excluded.
    try {
      Files.walkFileTree(
          workspace,
          new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(
                Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
              if (dir.equals(workspace)) return java.nio.file.FileVisitResult.CONTINUE;
              String name = dir.getFileName().toString();
              if (skipDirs.contains(name)
                  || (name.startsWith(".") && !".env.example".equals(name))) {
                return java.nio.file.FileVisitResult.SKIP_SUBTREE;
              }
              return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(
                Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
              if (attrs.isRegularFile()) {
                String name = file.getFileName().toString();
                if (!name.startsWith(".") || ".env.example".equals(name)) {
                  out.add(workspace.relativize(file).toString());
                }
              }
              return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, java.io.IOException e) {
              return java.nio.file.FileVisitResult
                  .CONTINUE; // skip an unreadable entry, keep walking
            }
          });
    } catch (IOException e) {
      log.debug("listWorkspaceFiles failed at {}: {}", workspace, e.getMessage());
    }
    return out;
  }

  /**
   * Resolve the run's stored provider string to an {@link LlmProvider} for the accuracy-assessment
   * route.
   *
   * <p>RR-364 fail-closed: a null or unrecognised provider used to be coerced to OpenAI, which
   * spent against OpenAI and attributed the assessment to a provider the run never selected. It now
   * throws; the single caller ({@code RunValidationKickoff}) already catches and degrades to
   * "accuracy not assessed", so a broken/unknown provider fails the assessment rather than silently
   * rerouting it.
   */
  public static LlmProvider providerForAccuracy(String provider) {
    if (provider == null || provider.isBlank()) {
      throw new IllegalArgumentException("accuracy provider missing on run");
    }
    String up = provider.trim().toUpperCase();
    try {
      return LlmProvider.valueOf(up);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unknown accuracy provider: " + provider, e);
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
