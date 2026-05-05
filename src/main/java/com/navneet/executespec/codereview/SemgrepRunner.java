package com.navneet.executespec.codereview;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs <a href="https://semgrep.dev">semgrep</a> against a workspace and parses its JSON output
 * into structured {@link Finding}s. Multi-language out of the box — semgrep ships rule packs for
 * Java, Python, JavaScript / TypeScript, Go, Ruby, C, C#, PHP, Kotlin, Rust, Scala, Swift, Bash,
 * YAML, Terraform, Dockerfile (covers everything we currently support + more).
 *
 * <p>Default ruleset: {@code p/ci} — semgrep's curated CI bundle (security + bug-prone idioms,
 * low-noise). Operator can swap via {@code codereview.semgrep.ruleset} property.
 *
 * <p>Graceful degradation: if semgrep isn't installed on the host, returns an empty result with
 * {@code available=false} so the caller can still run the LLM pass. The LLM pass alone is more
 * useful than no scan at all.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SemgrepRunner {

  /**
   * Result of a single scan invocation. {@code available=false} means semgrep wasn't on the PATH;
   * the {@link CodeReviewService} treats that as a no-op static phase rather than failing the whole
   * scan.
   */
  public record ScanResult(
      boolean available,
      List<Finding> findings,
      int totalFilesScanned,
      String unavailableReason,
      int durationMs) {}

  /**
   * One finding from semgrep — already normalized to our own shape so the caller doesn't have to
   * deal with semgrep's JSON quirks.
   */
  public record Finding(
      String ruleId,
      String severity, // CRITICAL | MAJOR | MINOR | INFO
      String category, // BUG | SECURITY | PERFORMANCE | STYLE | LOGIC | TEST_GAP | DUPLICATION
      String filePath, // workspace-relative
      int lineStart,
      int lineEnd,
      String message,
      String snippet) {}

  private final ObjectMapper objectMapper;

  /**
   * Default to {@code p/ci} — semgrep's curated CI bundle (security + bug-prone idioms, low-noise).
   * Operator can swap via {@code codereview.semgrep.ruleset} property if they want something
   * stricter (e.g. {@code p/owasp-top-ten}) or vendor-tuned (their own GitHub rules URL).
   */
  @Value("${codereview.semgrep.ruleset:p/ci}")
  private String ruleset;

  @Value("${codereview.semgrep.timeout-seconds:600}")
  private int timeoutSeconds;

  @Value("${codereview.semgrep.binary:semgrep}")
  private String semgrepBinary;

  /**
   * Scan the workspace at {@code workspaceRoot} with the configured ruleset. Returns empty findings
   * on first failure mode — never throws.
   */
  public ScanResult scan(Path workspaceRoot) {
    long start = System.currentTimeMillis();
    if (workspaceRoot == null) {
      return new ScanResult(false, List.of(), 0, "workspaceRoot=null", 0);
    }
    if (!Probe.binaryAvailable(semgrepBinary)) {
      return new ScanResult(
          false,
          List.of(),
          0,
          "semgrep not on PATH (host=" + semgrepBinary + ")",
          (int) (System.currentTimeMillis() - start));
    }

    // semgrep --config <ruleset> --json --quiet --metrics=off --error . (we run in workspace)
    // --no-rewrite-rule-ids keeps rule names stable in our DB across semgrep upgrades.
    ProcessBuilder pb =
        new ProcessBuilder(
                semgrepBinary,
                "--config",
                ruleset,
                "--json",
                "--quiet",
                "--metrics=off",
                "--no-rewrite-rule-ids",
                ".")
            .directory(workspaceRoot.toFile())
            .redirectErrorStream(false);
    pb.environment().put("SEMGREP_SEND_METRICS", "off");

    try {
      Process proc = pb.start();
      // Read stdout & stderr concurrently to avoid deadlock when output exceeds OS pipe buffer.
      // semgrep's JSON for a medium-size repo can hit several MB.
      Thread stdoutThread =
          new Thread(
              () -> {
                try {
                  String body =
                      new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                  capturedStdout.set(body);
                } catch (IOException ignored) {
                  // Process exit will surface the issue; nothing to do here.
                }
              },
              "semgrep-stdout");
      Thread stderrThread =
          new Thread(
              () -> {
                try {
                  String body =
                      new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                  capturedStderr.set(body);
                } catch (IOException ignored) {
                  // ignored
                }
              },
              "semgrep-stderr");
      capturedStdout.remove();
      capturedStderr.remove();
      stdoutThread.start();
      stderrThread.start();

      boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      if (!finished) {
        proc.destroyForcibly();
        log.warn(
            "[SemgrepRunner] timed out after {}s on {} — returning partial findings",
            timeoutSeconds,
            workspaceRoot);
        return new ScanResult(
            true,
            List.of(),
            0,
            "timeout after " + timeoutSeconds + "s",
            (int) (System.currentTimeMillis() - start));
      }
      stdoutThread.join(2000);
      stderrThread.join(2000);

      String stdout = capturedStdout.get() == null ? "" : capturedStdout.get();
      // exit 0 = no findings; 1 = findings present; 2+ = invalid args / load error.
      // We tolerate 0 + 1, but log + return empty on 2+.
      int exit = proc.exitValue();
      if (exit > 1) {
        log.warn(
            "[SemgrepRunner] non-finding exit={} stderr={}",
            exit,
            capturedStderr.get() == null ? "" : capturedStderr.get());
        return new ScanResult(
            true,
            List.of(),
            0,
            "semgrep exited with " + exit,
            (int) (System.currentTimeMillis() - start));
      }
      ParsedResult parsed = parse(stdout);
      return new ScanResult(
          true,
          parsed.findings(),
          parsed.scanned(),
          null,
          (int) (System.currentTimeMillis() - start));
    } catch (IOException e) {
      log.warn("[SemgrepRunner] IO error: {}", e.getMessage());
      return new ScanResult(
          false, List.of(), 0, "io: " + e.getMessage(), (int) (System.currentTimeMillis() - start));
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return new ScanResult(
          false, List.of(), 0, "interrupted", (int) (System.currentTimeMillis() - start));
    }
  }

  /**
   * Parse semgrep's JSON output (schema documented at semgrep.dev/docs/cli-reference). Extracts the
   * {@code results[]} array; everything else (errors, paths.scanned, etc) we either ignore or
   * surface via the parsed counts.
   */
  ParsedResult parse(String json) {
    if (json == null || json.isBlank()) {
      return new ParsedResult(List.of(), 0);
    }
    try {
      JsonNode root = objectMapper.readTree(json);
      JsonNode results = root.path("results");
      JsonNode scanned = root.path("paths").path("scanned");
      int scannedCount = scanned.isArray() ? scanned.size() : 0;
      List<Finding> out = new ArrayList<>();
      if (results.isArray()) {
        for (JsonNode r : results) {
          String ruleId = r.path("check_id").asText("");
          String filePath = r.path("path").asText("");
          int lineStart =
              r.path("start").path("line").isMissingNode()
                  ? r.path("location").path("start_line").asInt(0)
                  : r.path("start").path("line").asInt(0);
          int lineEnd =
              r.path("end").path("line").isMissingNode()
                  ? r.path("location").path("end_line").asInt(lineStart)
                  : r.path("end").path("line").asInt(lineStart);
          JsonNode extra = r.path("extra");
          String message = extra.path("message").asText("");
          String snippet = extra.path("lines").asText("");
          String semgrepSeverity = extra.path("severity").asText("INFO").toUpperCase();
          // semgrep uses ERROR/WARNING/INFO; we map to our 4-level severity scale.
          String severity =
              switch (semgrepSeverity) {
                case "ERROR", "CRITICAL" -> "MAJOR";
                case "WARNING" -> "MINOR";
                default -> "INFO";
              };
          // Rule metadata may carry a category; semgrep sometimes lowercases it.
          String category = extra.path("metadata").path("category").asText("BUG").toUpperCase();
          if (category.isBlank()) category = "BUG";
          // Map common semgrep cwe / category buckets to our enum-like set.
          if (category.startsWith("SECURITY") || category.contains("OWASP")) {
            category = "SECURITY";
          }
          out.add(
              new Finding(
                  ruleId, severity, category, filePath, lineStart, lineEnd, message, snippet));
        }
      }
      return new ParsedResult(out, scannedCount);
    } catch (Exception e) {
      log.warn("[SemgrepRunner] failed to parse semgrep JSON: {}", e.getMessage());
      return new ParsedResult(List.of(), 0);
    }
  }

  record ParsedResult(List<Finding> findings, int scanned) {}

  // Per-thread capture buffers for stdout / stderr — keyed off the current Thread so that
  // concurrent scans (multiple repos in one project, or scheduled cron + manual run) don't
  // overwrite each other.
  private final ThreadLocal<String> capturedStdout = new ThreadLocal<>();
  private final ThreadLocal<String> capturedStderr = new ThreadLocal<>();

  /** Static "is this binary on the PATH?" probe — keeps the main path uncluttered. */
  static class Probe {
    static boolean binaryAvailable(String binary) {
      try {
        Process p = new ProcessBuilder(binary, "--version").redirectErrorStream(true).start();
        boolean done = p.waitFor(5, TimeUnit.SECONDS);
        if (!done) {
          p.destroyForcibly();
          return false;
        }
        return p.exitValue() == 0;
      } catch (IOException | InterruptedException e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        return false;
      }
    }
  }
}
