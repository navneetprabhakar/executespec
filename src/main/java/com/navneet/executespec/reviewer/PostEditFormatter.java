package com.navneet.executespec.reviewer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Post-edit formatter pass — runs language-appropriate formatters across the workspace AFTER the
 * reviewer's general + wiring passes finish, BEFORE the final build smoke + auto-push.
 *
 * <p>Closes Fix-1 from the 2026-05-13 audit (run 6024f510). The reviewer's general pass can make
 * surgical edits to Python / JS / TS files (e.g. {@code async def → def}) that satisfy the "fix
 * bug" goal but introduce whitespace/indentation drift the customer's CI's {@code black --check} /
 * {@code prettier --check} catches. Workers are now told to format-after-edit (see {@link
 * com.navneet.executespec.harness.SequentialExecutor#SYSTEM_PROMPT_BASE} 2026-05-13 patch), but the
 * reviewer agent does not run that same prompt — so its edits sneak past.
 *
 * <p>This component invokes the formatters as host-side subprocesses (60s timeout, env-stripped
 * ProcessBuilder, best-effort: missing binary = WARN + skip). It does NOT install formatters; if
 * the customer's machine doesn't have {@code black} on PATH, the customer's CI is the fallback.
 *
 * <p>Idempotent: running the formatter on already-formatted code is a no-op. Safe to call
 * unconditionally at reviewer-end.
 */
@Component
@Slf4j
public class PostEditFormatter {

  /** Wall-clock cap per formatter invocation. */
  private static final long FORMATTER_TIMEOUT_SECONDS = 60;

  /** Skip directories that contain dependencies / build artefacts — never reformat those. */
  private static final List<String> SKIP_DIR_NAMES =
      List.of(
          "node_modules",
          ".git",
          ".venv",
          "venv",
          "__pycache__",
          ".pytest_cache",
          ".mypy_cache",
          "target",
          "dist",
          "build",
          ".next",
          ".nuxt",
          ".gradle",
          ".m2",
          ".idea",
          ".vscode",
          ".executespec");

  public record Result(int filesReformatted, List<String> formattersRun, List<String> errors) {}

  /**
   * Run formatters across {@code workspace}. Returns a non-null {@link Result} describing what
   * happened. Never throws — formatter failures are logged + listed in {@link Result#errors}.
   *
   * @param workspace path to walk
   * @param runId for log correlation
   */
  public Result run(Path workspace, String runId) {
    if (workspace == null || !Files.isDirectory(workspace)) {
      return new Result(0, List.of(), List.of("workspace missing: " + workspace));
    }
    java.util.List<String> formatters = new java.util.ArrayList<>();
    java.util.List<String> errors = new java.util.ArrayList<>();
    int totalReformatted = 0;

    // For each (formatter binary, file extensions) tuple: if any matching files exist AND the
    // binary is on PATH, run the formatter against the directory roots that contain those files.
    // We aim the formatter at directory roots (not individual files) so it can resolve project-
    // local config (.pyproject.toml, .prettierrc, etc).

    if (hasFiles(workspace, ".py") && isOnPath("black")) {
      java.util.List<Path> pyRoots = pythonRoots(workspace);
      for (Path root : pyRoots) {
        try {
          int n = invokeFormatter(runId, "black", root, List.of("--quiet", root.toString()));
          totalReformatted += n;
          if (n > 0) formatters.add("black on " + root);
        } catch (Exception e) {
          errors.add("black failed at " + root + ": " + shortMsg(e));
        }
      }
    }

    if ((hasFiles(workspace, ".js")
            || hasFiles(workspace, ".jsx")
            || hasFiles(workspace, ".ts")
            || hasFiles(workspace, ".tsx"))
        && (isOnPath("prettier") || isOnPath("npx"))) {
      String bin = isOnPath("prettier") ? "prettier" : "npx";
      java.util.List<Path> jsRoots = jsRoots(workspace);
      for (Path root : jsRoots) {
        try {
          List<String> args =
              "prettier".equals(bin)
                  ? List.of(
                      "--write",
                      "--log-level=warn",
                      root.toString() + "/**/*.{js,jsx,ts,tsx,json,css,md}")
                  : List.of(
                      "prettier",
                      "--write",
                      "--log-level=warn",
                      root.toString() + "/**/*.{js,jsx,ts,tsx,json,css,md}");
          int n = invokeFormatter(runId, bin, root, args);
          totalReformatted += n;
          if (n > 0) formatters.add("prettier on " + root);
        } catch (Exception e) {
          errors.add("prettier failed at " + root + ": " + shortMsg(e));
        }
      }
    }

    if (hasFiles(workspace, ".go") && isOnPath("gofmt")) {
      try {
        int n = invokeFormatter(runId, "gofmt", workspace, List.of("-w", workspace.toString()));
        totalReformatted += n;
        if (n > 0) formatters.add("gofmt on " + workspace);
      } catch (Exception e) {
        errors.add("gofmt failed: " + shortMsg(e));
      }
    }

    log.info(
        "[PostEditFormatter] run {} — reformatted ~{} file(s); formatters={}; errors={}",
        runId,
        totalReformatted,
        formatters,
        errors);
    return new Result(totalReformatted, formatters, errors);
  }

  /**
   * Returns true if at least one file with the given extension exists in {@code workspace} (skips
   * SKIP_DIR_NAMES dirs). Short-circuits on first hit.
   */
  private static boolean hasFiles(Path workspace, String ext) {
    try (Stream<Path> walk = Files.walk(workspace, 6)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(ext))
          .anyMatch(p -> !inSkippedDir(p));
    } catch (IOException e) {
      return false;
    }
  }

  /** Return the top-level dirs that contain at least one .py file (excluding SKIP_DIR_NAMES). */
  private static List<Path> pythonRoots(Path workspace) {
    return scopedRoots(workspace, ".py");
  }

  /** Return the top-level dirs that contain at least one JS/TS file. */
  private static List<Path> jsRoots(Path workspace) {
    java.util.LinkedHashSet<Path> all = new java.util.LinkedHashSet<>();
    all.addAll(scopedRoots(workspace, ".js"));
    all.addAll(scopedRoots(workspace, ".jsx"));
    all.addAll(scopedRoots(workspace, ".ts"));
    all.addAll(scopedRoots(workspace, ".tsx"));
    return new java.util.ArrayList<>(all);
  }

  private static List<Path> scopedRoots(Path workspace, String ext) {
    java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
    try (Stream<Path> walk = Files.walk(workspace, 4)) {
      walk.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(ext))
          .filter(p -> !inSkippedDir(p))
          .forEach(
              p -> {
                // For each matching file, pick its top-most non-skipped ancestor inside workspace.
                Path rel = workspace.relativize(p);
                if (rel.getNameCount() > 0) {
                  roots.add(workspace.resolve(rel.getName(0)));
                }
              });
    } catch (IOException e) {
      log.debug("scopedRoots walk failed for ext={}: {}", ext, e.getMessage());
    }
    return new java.util.ArrayList<>(roots);
  }

  private static boolean inSkippedDir(Path p) {
    for (int i = 0; i < p.getNameCount(); i++) {
      String name = p.getName(i).toString();
      if (SKIP_DIR_NAMES.contains(name)) return true;
    }
    return false;
  }

  /**
   * Returns true if {@code binary} can be found via {@code command -v}. Cheap PATH lookup; no env
   * variables leaked.
   */
  private static boolean isOnPath(String binary) {
    try {
      ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", "command -v " + binary);
      pb.redirectErrorStream(true);
      Process p = pb.start();
      boolean finished = p.waitFor(2, TimeUnit.SECONDS);
      if (!finished) {
        p.destroyForcibly();
        return false;
      }
      return p.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Run {@code bin args} in {@code workdir} with a stripped environment + 60s timeout. Returns
   * approximate count of reformatted files (parsed from stdout where the formatter prints it;
   * best-effort, fallback to 1 if exit=0 + stdout non-empty).
   */
  private static int invokeFormatter(String runId, String bin, Path workdir, List<String> args)
      throws IOException, InterruptedException {
    java.util.List<String> cmd = new java.util.ArrayList<>();
    cmd.add(bin);
    cmd.addAll(args.subList(0, Math.min(args.size(), 1))); // already includes bin if npx
    if ("npx".equals(bin)) {
      // For npx, args already starts with "prettier <flags>"; replace the entry list.
      cmd.clear();
      cmd.add(bin);
      cmd.addAll(args);
    } else {
      cmd.clear();
      cmd.add(bin);
      cmd.addAll(args);
    }
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.directory(workdir.toFile());
    // Strip env — same hardening as TestCompilationGate (audit 2026-05-12 J #59).
    var env = pb.environment();
    String path = env.get("PATH");
    String home = env.get("HOME");
    String user = env.get("USER");
    env.clear();
    if (path != null) env.put("PATH", path);
    if (home != null) env.put("HOME", home);
    if (user != null) env.put("USER", user);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String output;
    try (var stream = p.getInputStream()) {
      output = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    boolean finished = p.waitFor(FORMATTER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (!finished) {
      p.destroyForcibly();
      log.warn(
          "[PostEditFormatter] run {} — {} TIMEOUT (>{}s)", runId, bin, FORMATTER_TIMEOUT_SECONDS);
      return 0;
    }
    int exit = p.exitValue();
    if (exit != 0) {
      log.warn(
          "[PostEditFormatter] run {} — {} exit={} (continuing). Output head: {}",
          runId,
          bin,
          exit,
          output.length() > 240 ? output.substring(0, 240) : output);
      return 0;
    }
    // black-style: "reformatted X files" / "X files reformatted"
    // prettier --write: prints filenames one per line for reformatted files
    int approxCount = parseReformattedCount(bin, output);
    log.info("[PostEditFormatter] run {} — {} ok (≈{} file(s))", runId, bin, approxCount);
    return approxCount;
  }

  /**
   * Best-effort parse of how many files the formatter touched. Used for log telemetry only — does
   * NOT drive any decision.
   */
  static int parseReformattedCount(String bin, String output) {
    if (output == null || output.isBlank()) return 0;
    if ("black".equals(bin)) {
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("(?im)^.*?(\\d+)\\s+files?\\s+reformatted")
              .matcher(output);
      if (m.find()) {
        try {
          return Integer.parseInt(m.group(1));
        } catch (NumberFormatException ignored) {
        }
      }
      return output.contains("reformatted") ? 1 : 0;
    }
    // prettier prints one path per reformatted file
    long lines =
        output
            .lines()
            .filter(s -> !s.isBlank() && !s.startsWith("[") && !s.contains(" ms"))
            .count();
    return (int) Math.min(lines, Integer.MAX_VALUE);
  }

  private static String shortMsg(Throwable t) {
    String m = t.getMessage();
    if (m == null) return t.getClass().getSimpleName();
    return m.length() > 200 ? m.substring(0, 200) + "…" : m;
  }
}
