package com.navneet.executespec.scoring;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.navneet.executespec.scoring.entity.RunRubricRow;
import com.navneet.executespec.scoring.repository.RunRubricRowRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scoring Revamp Phase A (2026-05-14) — verification engine that walks the planner-emitted rubric
 * ({@link RunRubricRow}) after task execution and flips each row's status from {@code PENDING} to
 * {@code PASS} / {@code FAIL} / {@code UNKNOWN} with evidence.
 *
 * <p>Per the design doc (Layer 2):
 *
 * <ul>
 *   <li>Walk the rubric in stored order
 *   <li>Dispatch each row to a handler chosen by {@code verificationType}
 *   <li>Mark PASS / FAIL / UNKNOWN with one-line evidence
 *   <li>Persist; emit Micrometer counters tagged by category + status
 * </ul>
 *
 * <p>Phase A scope — two native handlers shipped:
 *
 * <ul>
 *   <li>{@code shell} — runs a command in the workspace dir, asserts exit code 0
 *   <li>{@code file-exists} — checks the workspace tree for a relative path
 *   <li>{@code file-content} — checks a file for a regex match
 * </ul>
 *
 * <p>Other verification types ({@code endpoint}, {@code pr-state}, {@code manual}) are scaffolded
 * with stub handlers that mark UNKNOWN + record an evidence string explaining which follow-up will
 * wire them. This keeps Phase A non-breaking: a planner-emitted rubric with those types doesn't
 * fail the run, it just doesn't add value yet.
 *
 * <p>Empty-rubric short-circuit: {@link #verifyRubric} returns immediately when no rows exist for
 * the run, so legacy runs (planner doesn't emit a rubric yet) pay zero cost.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunRubricVerifier {

  /** Wall-clock cap on a single shell verification, in seconds. */
  static final int SHELL_TIMEOUT_SECONDS = 60;

  private final RunRubricRowRepository repo;
  private final MeterRegistry meterRegistry;

  @Value("${agent.workspace:./workspace}")
  private String workspaceRoot;

  /**
   * Walks every {@code PENDING} rubric row for {@code runId}, verifies each, persists the result.
   * Returns the number of rows that flipped to PASS — caller can use this for logging / metrics /
   * future score derivation.
   *
   * <p>Best-effort: per-row try/catch isolates one bad handler from poisoning the rest of the
   * rubric. Failures are recorded as {@code UNKNOWN} with the exception message as evidence.
   */
  @Transactional
  public int verifyRubric(String runId) {
    if (runId == null || runId.isBlank()) return 0;
    if (!repo.existsByRunId(runId)) {
      // No rubric for this run (planner didn't emit one) — short-circuit at zero cost.
      return 0;
    }

    Path runWorkspace = workspaceFor(runId);
    List<RunRubricRow> rows = repo.findByRunIdOrderByRubricRowIdAsc(runId);
    int passed = 0;
    int failed = 0;
    int unknown = 0;
    for (RunRubricRow row : rows) {
      if (!RunRubricRow.Status.PENDING.equals(row.getStatus())) {
        continue; // already verified (retry / re-entry)
      }
      Verdict v;
      try {
        v = dispatch(row, runWorkspace);
      } catch (Exception e) {
        v = new Verdict(RunRubricRow.Status.UNKNOWN, "verifier crashed: " + e.getMessage());
      }
      row.setStatus(v.status());
      row.setEvidence(v.evidence());
      row.setVerifiedAt(Instant.now());
      repo.save(row);

      switch (v.status()) {
        case "PASS" -> passed++;
        case "FAIL" -> failed++;
        default -> unknown++;
      }
      Counter.builder("executespec.rubric.verified")
          .tag("category", row.getCategory() != null ? row.getCategory() : "UNKNOWN")
          .tag("status", v.status())
          .register(meterRegistry)
          .increment();
    }
    log.info(
        "[RunRubricVerifier] run={} rows={} pass={} fail={} unknown={}",
        runId,
        rows.size(),
        passed,
        failed,
        unknown);
    return passed;
  }

  /** Result of a single verification — package-private for tests. */
  record Verdict(String status, String evidence) {}

  /**
   * Verification handler dispatch — exposed package-private so tests can exercise each verification
   * type without a Spring context.
   */
  Verdict dispatch(RunRubricRow row, Path workspace) {
    String type = row.getVerificationType();
    if (type == null) {
      return new Verdict(RunRubricRow.Status.UNKNOWN, "no verification type set");
    }
    return switch (type) {
      case RunRubricRow.VerificationType.SHELL -> verifyShell(row, workspace);
      case RunRubricRow.VerificationType.FILE_EXISTS -> verifyFileExists(row, workspace);
      case RunRubricRow.VerificationType.FILE_CONTENT -> verifyFileContent(row, workspace);
      case RunRubricRow.VerificationType.ENDPOINT,
              RunRubricRow.VerificationType.PR_STATE,
              RunRubricRow.VerificationType.MANUAL ->
          new Verdict(
              RunRubricRow.Status.UNKNOWN,
              "verification type '"
                  + type
                  + "' is Phase B (preview-endpoint / GitHub-API"
                  + " / LLM-judge) — handler not yet implemented");
      default -> new Verdict(RunRubricRow.Status.UNKNOWN, "unknown verification type: " + type);
    };
  }

  // ── shell ────────────────────────────────────────────────────────────────

  Verdict verifyShell(RunRubricRow row, Path workspace) {
    String cmd = row.getVerificationSpec();
    if (cmd == null || cmd.isBlank()) {
      return new Verdict(RunRubricRow.Status.UNKNOWN, "shell: no command specified");
    }
    try {
      ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd);
      pb.directory(workspace.toFile());
      pb.redirectErrorStream(true);
      Process p = pb.start();
      boolean finished = p.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!finished) {
        p.destroyForcibly();
        return new Verdict(
            RunRubricRow.Status.FAIL,
            "shell: command exceeded " + SHELL_TIMEOUT_SECONDS + "s timeout");
      }
      int exit = p.exitValue();
      if (exit == 0) {
        return new Verdict(RunRubricRow.Status.PASS, "shell: exit 0");
      }
      return new Verdict(RunRubricRow.Status.FAIL, "shell: exit " + exit);
    } catch (IOException ioe) {
      return new Verdict(RunRubricRow.Status.UNKNOWN, "shell: io error: " + ioe.getMessage());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return new Verdict(RunRubricRow.Status.UNKNOWN, "shell: interrupted");
    }
  }

  // ── file-exists ──────────────────────────────────────────────────────────

  Verdict verifyFileExists(RunRubricRow row, Path workspace) {
    String relPath = row.getVerificationSpec();
    if (relPath == null || relPath.isBlank()) {
      return new Verdict(RunRubricRow.Status.UNKNOWN, "file-exists: no path specified");
    }
    Path target = safeResolveUnder(workspace, relPath);
    if (target == null) {
      return new Verdict(
          RunRubricRow.Status.FAIL, "file-exists: path escapes workspace: " + relPath);
    }
    if (Files.exists(target)) {
      return new Verdict(RunRubricRow.Status.PASS, "file-exists: " + relPath + " present");
    }
    return new Verdict(RunRubricRow.Status.FAIL, "file-exists: " + relPath + " missing");
  }

  // ── file-content ─────────────────────────────────────────────────────────

  /**
   * Spec format: {@code <relativePath>::<regex>} (double-colon separator avoids colliding with
   * regexes that contain a single colon).
   */
  Verdict verifyFileContent(RunRubricRow row, Path workspace) {
    String spec = row.getVerificationSpec();
    if (spec == null || !spec.contains("::")) {
      return new Verdict(RunRubricRow.Status.UNKNOWN, "file-content: spec must be 'path::regex'");
    }
    String[] parts = spec.split("::", 2);
    String relPath = parts[0];
    String regex = parts[1];
    Path target = safeResolveUnder(workspace, relPath);
    if (target == null) {
      return new Verdict(
          RunRubricRow.Status.FAIL, "file-content: path escapes workspace: " + relPath);
    }
    if (!Files.exists(target)) {
      return new Verdict(RunRubricRow.Status.FAIL, "file-content: " + relPath + " missing");
    }
    try {
      String content = Files.readString(target);
      Pattern p = Pattern.compile(regex);
      if (p.matcher(content).find()) {
        return new Verdict(RunRubricRow.Status.PASS, "file-content: regex matched in " + relPath);
      }
      return new Verdict(RunRubricRow.Status.FAIL, "file-content: regex not found in " + relPath);
    } catch (IOException ioe) {
      return new Verdict(
          RunRubricRow.Status.UNKNOWN, "file-content: read error: " + ioe.getMessage());
    } catch (java.util.regex.PatternSyntaxException pse) {
      return new Verdict(
          RunRubricRow.Status.UNKNOWN, "file-content: invalid regex: " + pse.getMessage());
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  Path workspaceFor(String runId) {
    return Path.of(workspaceRoot).resolve(runId).toAbsolutePath().normalize();
  }

  /**
   * Resolve {@code rel} under {@code root}, returning {@code null} if the result escapes the
   * workspace (path traversal attempt). Defence-in-depth — the planner shouldn't emit {@code ../}
   * paths, but verification specs come from an LLM so we treat them as untrusted.
   */
  static Path safeResolveUnder(Path root, String rel) {
    if (rel == null) return null;
    Path normalized = root.resolve(rel).toAbsolutePath().normalize();
    if (!normalized.startsWith(root)) return null;
    return normalized;
  }
}
