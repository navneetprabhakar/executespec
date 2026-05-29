package com.navneet.executespec.lint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Auto-format / lint-fix pass that runs between {@code SequentialExecutor} and {@code
 * ReviewerAgent}.
 *
 * <p>Why: the worker writes code that often has trailing-comma drift, missing newlines, slightly
 * off indentation, etc. — issues a deterministic formatter ({@code prettier --write}, {@code
 * spotless:apply}, {@code black}, {@code gofmt -w}, {@code cargo fmt}) fixes in &lt;200ms but the
 * LLM reviewer "fixes" by re-typing the file at 5–15K tokens per task. Running the formatter once
 * before the reviewer drops both the token cost and the "lint nit" noise floor that would otherwise
 * feed back into the review verdict.
 *
 * <p>Each detected formatter runs against the run's workspace with a 5-minute per-tool timeout and
 * a 10-minute total budget. Failures are non-fatal — we log them and continue. The reviewer still
 * runs even if every fixer fails, since this is purely additive.
 *
 * <p>Detection is filesystem-only — we don't shell out to ask "is prettier installed?". A tool is
 * picked when its config file exists in the workspace ({@code .prettierrc*} / {@code package.json}
 * for prettier; {@code pom.xml} for spotless-maven; {@code build.gradle*} for spotless-gradle;
 * {@code pyproject.toml} or {@code requirements.txt} for black; {@code go.mod} for gofmt; {@code
 * Cargo.toml} for rustfmt). Tools that aren't installed on the host fail with exit code != 0 —
 * still logged at WARN, never aborts the run.
 *
 * <p>Behaviour is gated by {@code lint.autofix.enabled} — defaults to {@code true}, but ops can
 * disable per-environment (e.g. {@code dev} that doesn't have prettier on PATH) via
 * application-*.yml.
 */
@Component
@Slf4j
public class LintPass {

  private static final Duration PER_TOOL_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration TOTAL_BUDGET = Duration.ofMinutes(10);

  /** Run every detected fixer against the workspace. */
  public LintReport run(Path workspace) {
    if (workspace == null || !Files.isDirectory(workspace)) {
      return LintReport.skipped("workspace missing");
    }

    Set<Tool> tools = detect(workspace);
    if (tools.isEmpty()) {
      return LintReport.skipped("no formatter detected");
    }

    long start = System.nanoTime();
    long budgetNanos = TOTAL_BUDGET.toNanos();
    List<ToolResult> results = new ArrayList<>();
    for (Tool tool : tools) {
      long elapsed = System.nanoTime() - start;
      if (elapsed > budgetNanos) {
        log.warn("LintPass total budget exceeded after {} tools — stopping", results.size());
        results.add(
            new ToolResult(tool, false, "skipped (total budget exceeded)", Duration.ZERO, 0));
        continue;
      }
      results.add(applyOne(workspace, tool));
    }
    int filesChanged = results.stream().mapToInt(ToolResult::filesChanged).sum();
    return new LintReport(results, filesChanged, false, null);
  }

  // ── Detection ─────────────────────────────────────────────────────────

  private Set<Tool> detect(Path workspace) {
    Set<Tool> tools = EnumSet.noneOf(Tool.class);
    if (hasPrettierConfig(workspace) || Files.exists(workspace.resolve("package.json"))) {
      // package.json alone is enough to attempt prettier — `npx prettier` will install on demand
      // if a project lists it as a devDep, otherwise the call exits 0 with "no files matched".
      tools.add(Tool.PRETTIER);
    }
    if (Files.exists(workspace.resolve("pom.xml"))) tools.add(Tool.SPOTLESS_MAVEN);
    if (Files.exists(workspace.resolve("build.gradle"))
        || Files.exists(workspace.resolve("build.gradle.kts"))) {
      tools.add(Tool.SPOTLESS_GRADLE);
    }
    if (Files.exists(workspace.resolve("pyproject.toml"))
        || Files.exists(workspace.resolve("requirements.txt"))
        || Files.exists(workspace.resolve("setup.py"))) {
      tools.add(Tool.BLACK);
    }
    if (Files.exists(workspace.resolve("go.mod"))) tools.add(Tool.GOFMT);
    if (Files.exists(workspace.resolve("Cargo.toml"))) tools.add(Tool.RUSTFMT);
    return tools;
  }

  private boolean hasPrettierConfig(Path workspace) {
    return Files.exists(workspace.resolve(".prettierrc"))
        || Files.exists(workspace.resolve(".prettierrc.json"))
        || Files.exists(workspace.resolve(".prettierrc.js"))
        || Files.exists(workspace.resolve(".prettierrc.cjs"))
        || Files.exists(workspace.resolve(".prettierrc.yml"))
        || Files.exists(workspace.resolve(".prettierrc.yaml"))
        || Files.exists(workspace.resolve("prettier.config.js"));
  }

  // ── Per-tool dispatch ─────────────────────────────────────────────────

  private ToolResult applyOne(Path workspace, Tool tool) {
    long beforeMtime = walkLatestMtime(workspace);
    long started = System.nanoTime();
    try {
      List<String> cmd =
          switch (tool) {
            case PRETTIER ->
                List.of("npx", "--no-install", "prettier", "--write", "--ignore-unknown", ".");
            case SPOTLESS_MAVEN -> List.of("mvn", "-q", "spotless:apply");
            case SPOTLESS_GRADLE -> List.of("./gradlew", "--quiet", "spotlessApply");
            case BLACK -> List.of("python3", "-m", "black", "--quiet", ".");
            case GOFMT -> List.of("gofmt", "-w", ".");
            case RUSTFMT -> List.of("cargo", "fmt");
          };
      ProcessBuilder pb =
          new ProcessBuilder(cmd).directory(workspace.toFile()).redirectErrorStream(true);
      // Strip secret-bearing env vars — formatters never need them.
      pb.environment().keySet().removeIf(LintPass::isSecretEnvVar);
      Process p = pb.start();
      boolean exited = p.waitFor(PER_TOOL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
      if (!exited) {
        p.destroyForcibly();
        log.warn(
            "LintPass {} timed out after {}s in {}", tool, PER_TOOL_TIMEOUT.toSeconds(), workspace);
        return new ToolResult(tool, false, "timed out", elapsed, 0);
      }
      int exit = p.exitValue();
      String output = new String(p.getInputStream().readAllBytes()).trim();
      long afterMtime = walkLatestMtime(workspace);
      int filesChanged = afterMtime > beforeMtime ? countTouchedFiles(workspace, beforeMtime) : 0;
      if (exit != 0) {
        log.warn("LintPass {} exit {} (output={})", tool, exit, truncate(output, 400));
        return new ToolResult(tool, false, "exit " + exit, elapsed, filesChanged);
      }
      log.info(
          "LintPass {} success — {} file(s) changed in {}s",
          tool,
          filesChanged,
          elapsed.toSeconds());
      return new ToolResult(tool, true, null, elapsed, filesChanged);
    } catch (Exception e) {
      Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
      log.warn("LintPass {} failed: {}", tool, e.getMessage());
      return new ToolResult(tool, false, e.getMessage(), elapsed, 0);
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────

  /** Recursive max-mtime walk capped at 5K files (defensive against huge node_modules trees). */
  private static long walkLatestMtime(Path workspace) {
    final long[] max = {0L};
    final int[] count = {0};
    try {
      Files.walkFileTree(
          workspace,
          new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(
                Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
              String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
              if (name.equals("node_modules")
                  || name.equals(".git")
                  || name.equals("target")
                  || name.equals("build")
                  || name.equals("dist")
                  || name.equals(".gradle")
                  || name.equals("__pycache__")
                  || name.equals(".next")
                  || name.equals("vendor")) {
                return java.nio.file.FileVisitResult.SKIP_SUBTREE;
              }
              return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(
                Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
              count[0]++;
              if (count[0] > 5000) return java.nio.file.FileVisitResult.TERMINATE;
              long m = attrs.lastModifiedTime().toMillis();
              if (m > max[0]) max[0] = m;
              return java.nio.file.FileVisitResult.CONTINUE;
            }
          });
    } catch (Exception e) {
      log.warn("[LintPass] mtime scan of {} failed: {}", workspace, e.toString());
    }
    return max[0];
  }

  private static int countTouchedFiles(Path workspace, long beforeMtime) {
    final int[] count = {0};
    try {
      Files.walkFileTree(
          workspace,
          new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(
                Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
              String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
              if (name.equals("node_modules")
                  || name.equals(".git")
                  || name.equals("target")
                  || name.equals("build")
                  || name.equals("dist")
                  || name.equals(".gradle")
                  || name.equals("__pycache__")
                  || name.equals(".next")
                  || name.equals("vendor")) {
                return java.nio.file.FileVisitResult.SKIP_SUBTREE;
              }
              return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(
                Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
              if (attrs.lastModifiedTime().toMillis() > beforeMtime) count[0]++;
              return java.nio.file.FileVisitResult.CONTINUE;
            }
          });
    } catch (Exception e) {
      log.warn("[LintPass] touched-file scan of {} failed: {}", workspace, e.toString());
    }
    return count[0];
  }

  private static final List<String> SECRET_ENV_PREFIXES =
      Arrays.asList(
          "AWS_",
          "GOOGLE_",
          "GCP_",
          "GITHUB_",
          "STRIPE_",
          "RAZORPAY_",
          "ANTHROPIC_",
          "OPENAI_",
          "GEMINI_",
          "JWT_",
          "DATABASE_",
          "REDIS_",
          "SMTP_");

  private static boolean isSecretEnvVar(String name) {
    if (name == null) return false;
    String upper = name.toUpperCase();
    if (upper.contains("PASSWORD")
        || upper.contains("SECRET")
        || upper.contains("TOKEN")
        || upper.contains("KEY")
        || upper.contains("API_")
        || upper.contains("CREDENTIAL")) {
      return true;
    }
    for (String prefix : SECRET_ENV_PREFIXES) {
      if (upper.startsWith(prefix)) return true;
    }
    return false;
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }

  // ── Public types ──────────────────────────────────────────────────────

  public enum Tool {
    PRETTIER,
    SPOTLESS_MAVEN,
    SPOTLESS_GRADLE,
    BLACK,
    GOFMT,
    RUSTFMT
  }

  public record ToolResult(
      Tool tool, boolean success, String error, Duration elapsed, int filesChanged) {}

  public record LintReport(
      List<ToolResult> results, int totalFilesChanged, boolean skipped, String skipReason) {
    public static LintReport skipped(String reason) {
      return new LintReport(List.of(), 0, true, reason);
    }

    public boolean changedAnything() {
      return !skipped && totalFilesChanged > 0;
    }

    /** One-line summary for SSE / log: "prettier+spotless · 14 files changed in 3.2s". */
    public String summary() {
      if (skipped) return "skipped (" + skipReason + ")";
      if (results.isEmpty()) return "no formatters ran";
      long totalMillis = results.stream().mapToLong(r -> r.elapsed.toMillis()).sum();
      String tools =
          results.stream()
              .filter(ToolResult::success)
              .map(r -> r.tool.name().toLowerCase().replace("_", "-"))
              .reduce((a, b) -> a + "+" + b)
              .orElse("(none ok)");
      return tools
          + " · "
          + totalFilesChanged
          + " file(s) changed in "
          + (totalMillis / 1000.0)
          + "s";
    }
  }
}
