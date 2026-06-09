package com.navneet.executespec.tool;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.programbench.ProgramBenchContainerStore;
import com.navneet.executespec.swebench.SwebenchContainerStore;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * B1 (RL-2026-06, vix-leverage plan W1.1) — the standout differentiator: after a successful {@code
 * editFile}/{@code writeFile}, run a <b>fast, scoped</b> syntax/compile check on the touched file
 * and append the first diagnostics to the tool result the model sees. This closes the
 * edit&rarr;"here's what you just broke" loop that vix (and the external audit) leave open,
 * directly attacking the <i>wrong-half-fix</i> class that dominates the SWE-bench residuals
 * (RL-2026-05-061): a broken edit now reports its own syntax error on the same turn instead of
 * surfacing later (or never).
 *
 * <p><b>Scope (first cut): Python.</b> {@code python3 -m py_compile} is fast, file-scoped,
 * deterministic, and produces zero false positives — and Python is the SWE-bench target. A
 * single-file {@code javac} is deliberately NOT done: without the project classpath it floods with
 * false "cannot find symbol" errors (net noise). Other languages return empty (skip) until a
 * reliable per-file check exists; the dispatch in {@link #languageOf} is the extension point.
 *
 * <p><b>Runtime selection.</b> When the run has a registered cleanroom container (SWE-bench /
 * ProgramBench), the check runs <i>in that container</i> ({@code docker exec}) so it uses the
 * task's own Python — avoiding host-vs-task version skew (e.g. host 3.9 false-flagging 3.10+ {@code
 * match}). Otherwise it runs against the bind-mounted file on the host.
 *
 * <p><b>Safety.</b> Flag-gated ({@link #FLAG}, V232, default OFF &rarr; prod byte-identical),
 * latency-bounded ({@link #TIMEOUT_SECONDS}), diagnostic-count-bounded ({@link #MAX_LINES}), and
 * <b>fail-open</b> on every error path (timeout / no python / parse miss &rarr; empty, never a
 * failed edit).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EditDiagnosticsService {

  public static final String FLAG = "worker.edit-diagnostics.enabled"; // V232, default OFF
  private static final int TIMEOUT_SECONDS = 15;
  private static final int MAX_LINES = 6;

  private final FeatureFlagService flags;
  private final ProgramBenchContainerStore programBenchContainers;
  private final SwebenchContainerStore swebenchContainers;

  /**
   * @param ctx the run/task/workspace context of the edit
   * @param relPath the worker-relative file path (exactly the arg {@code editFile}/{@code
   *     writeFile} received)
   * @return a short diagnostics block to append to the tool result, or empty when the file is
   *     clean, the language is unsupported, the flag is off, or any error occurs (fail-open)
   */
  public Optional<String> diagnose(ExecutionContext ctx, String relPath) {
    if (relPath == null || ctx == null || !flags.isEnabled(FLAG)) {
      return Optional.empty();
    }
    String lang = languageOf(relPath);
    if (lang == null) {
      return Optional.empty();
    }
    try {
      return switch (lang) {
        case "python" -> pyCompile(ctx, relPath);
        default -> Optional.empty();
      };
    } catch (Exception e) {
      log.debug("edit-diagnostics fail-open path={}: {}", relPath, e.toString());
      return Optional.empty();
    }
  }

  /** null = unsupported (skip). Only languages with a reliable, classpath-free per-file check. */
  static String languageOf(String relPath) {
    return relPath.toLowerCase().endsWith(".py") ? "python" : null;
  }

  private Optional<String> pyCompile(ExecutionContext ctx, String relPath)
      throws Exception { // NOSONAR fail-open caught by diagnose()
    String cid = null;
    String workdir = null;
    if (swebenchContainers != null) {
      String s = swebenchContainers.get(ctx.runId());
      if (s != null) {
        cid = s;
        workdir = SwebenchContainerStore.WORKDIR;
      }
    }
    if (cid == null && programBenchContainers != null) {
      String p = programBenchContainers.get(ctx.runId());
      if (p != null) {
        cid = p;
        workdir = "/workspace";
      }
    }
    ProcessBuilder pb;
    if (cid != null) {
      pb =
          new ProcessBuilder(
              List.of(
                  "docker", "exec", "-w", workdir, cid, "python3", "-m", "py_compile", relPath));
    } else {
      pb = new ProcessBuilder(List.of("python3", "-m", "py_compile", relPath));
      pb.directory(ctx.workspaceRoot().toFile());
    }
    pb.redirectErrorStream(true);
    Process proc = pb.start();
    if (!proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      proc.destroyForcibly();
      return Optional.empty(); // timeout → fail-open
    }
    if (proc.exitValue() == 0) {
      return Optional.empty(); // clean compile
    }
    String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String diag = formatPyCompile(out, relPath);
    return diag.isBlank() ? Optional.empty() : Optional.of(diag);
  }

  /**
   * Extract the salient lines from {@code python3 -m py_compile} error output (the {@code File
   * "...", line N} locus + the {@code SyntaxError:}/{@code IndentationError:} message), bounded to
   * {@link #MAX_LINES}. Package-private for unit testing.
   */
  static String formatPyCompile(String out, String relPath) {
    if (out == null || out.isBlank()) {
      return "";
    }
    List<String> keep = new ArrayList<>();
    for (String line : out.split("\n")) {
      String t = line.strip();
      if (t.isEmpty()) {
        continue;
      }
      if (t.contains("Error:") || t.startsWith("File \"") || t.contains(", line ")) {
        keep.add(t);
        if (keep.size() >= MAX_LINES) {
          break;
        }
      }
    }
    if (keep.isEmpty()) {
      return "";
    }
    return "⚠ post-edit syntax check (py_compile) failed for "
        + relPath
        + ":\n  "
        + String.join("\n  ", keep)
        + "\n  Fix the syntax before continuing — the file will not import/run as written.";
  }
}
