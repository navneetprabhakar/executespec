package com.navneet.executespec.standards.enforcement.lint;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * E3.4 — wraps {@code npx eslint --format json}. Parses the JSON output (an array of {@code
 * {filePath, messages: [{ruleId, severity, line, column, message}]}}) into normalised {@link
 * LintViolation}s.
 *
 * <p>severity is ESLint's own scale (1=warn, 2=error). We pass both through; the standard's own
 * severity (BLOCKING / WARNING / ADVISORY) decides whether the violation gates a run — that mapping
 * happens in E3.10 worker integration.
 *
 * <p>RR-1051 — this adapter no longer shells out to {@code npx}. {@code npx} resolves the binary
 * from the repository's own {@code node_modules} or downloads it from the network, so the
 * repository chose what executed. Every invocation is now admitted by {@link LintExecutionPolicy}
 * first and runs an operator-pinned executable, or reports UNAVAILABLE with the reason.
 *
 * <p>Admission does not make an ESLint run safe: {@code eslint.config.js} is JavaScript and plugins
 * are repository-selected npm packages, so an admitted run is still arbitrary customer code
 * executing on the host. That is why host execution defaults to off.
 */
@Component
@Slf4j
public class EslintAdapter implements LintAdapter {

  private final LintProcessRunner runner;
  private final LintExecutionPolicy policy;
  private final ObjectMapper json;

  public EslintAdapter(LintProcessRunner runner, LintExecutionPolicy policy, ObjectMapper json) {
    this.runner = runner;
    this.policy = policy;
    this.json = json;
  }

  @Override
  public String tool() {
    return "eslint";
  }

  @Override
  public LintResult run(String runId, Path repoRoot, Path configPath, List<Path> changedFiles) {
    LintExecutionPolicy.Admission admission =
        policy.admit(tool(), repoRoot, configPath, changedFiles);
    if (!admission.allowed()) {
      return new LintResult(
          tool(), LintResult.Status.UNAVAILABLE, List.of(), admission.denialReason());
    }

    List<String> argv =
        new ArrayList<>(List.of(admission.executable(), "--format", "json", "--no-color"));
    if (admission.configArgument() != null) {
      argv.add("--config");
      argv.add(admission.configArgument());
    }
    if (!admission.targetArguments().isEmpty()) {
      argv.addAll(admission.targetArguments());
    } else {
      argv.add(".");
    }

    LintProcessRunner.Result res = runner.run(runId, repoRoot, argv, policy.timeoutSeconds());
    if (!res.executed()) {
      return new LintResult(
          tool(), LintResult.Status.UNAVAILABLE, List.of(), res.unavailableReason());
    }
    // RR-1610 — SUCCESS must be inferred from the PRESENCE of a well-formed report, not the ABSENCE
    // of an error signal. The old three-way conjunction (exit != 0 AND stdout blank AND stderr set)
    // let a silently-killed linter (exit=137, stdout="", stderr="") and a non-eslint binary on PATH
    // (exit=0, stdout="") both fall through to a clean SUCCESS with zero violations.
    int exit = res.exitCode();
    String stdout = res.stdout();
    // ESLint documents exactly two normal exit codes: 0 (no problems) and 1 (lint problems found).
    // Any other code — 2 (config/internal error), -1 (timeout), 137 (SIGKILL/OOM) — means it did
    // not
    // run to a verdict, so it is NOT a passing lint regardless of stream contents.
    if (exit != 0 && exit != 1) {
      return new LintResult(
          tool(),
          LintResult.Status.FAILED,
          List.of(),
          "eslint exited " + exit + describeStderr(res.stderr()));
    }
    // A real `eslint --format json` run always emits a JSON array (at minimum `[]`). Blank stdout
    // is
    // never a valid clean result — the linter produced nothing to verify.
    if (stdout == null || stdout.isBlank()) {
      return new LintResult(
          tool(),
          LintResult.Status.FAILED,
          List.of(),
          "eslint exited " + exit + " with no JSON report" + describeStderr(res.stderr()));
    }
    JsonNode root;
    try {
      root = json.readTree(stdout);
    } catch (Exception ex) {
      return new LintResult(
          tool(),
          LintResult.Status.FAILED,
          List.of(),
          "could not parse eslint json (exit " + exit + "): " + ex.getMessage());
    }
    if (!root.isArray()) {
      return new LintResult(
          tool(),
          LintResult.Status.FAILED,
          List.of(),
          "eslint output was not a JSON report array (exit " + exit + ")");
    }
    return new LintResult(tool(), LintResult.Status.SUCCESS, extractViolations(root), null);
  }

  private static String describeStderr(String stderr) {
    return stderr == null || stderr.isBlank() ? "" : ": " + truncate(stderr);
  }

  List<LintViolation> parseJsonOutput(String stdout) throws Exception {
    if (stdout == null || stdout.isBlank()) return List.of();
    return extractViolations(json.readTree(stdout));
  }

  private List<LintViolation> extractViolations(JsonNode arr) {
    if (arr == null || !arr.isArray()) return List.of();
    List<LintViolation> out = new ArrayList<>();
    for (JsonNode fileNode : arr) {
      String filePath = fileNode.path("filePath").asText("");
      JsonNode msgs = fileNode.path("messages");
      if (!msgs.isArray()) continue;
      for (JsonNode msg : msgs) {
        out.add(
            new LintViolation(
                filePath,
                msg.path("line").asInt(0),
                msg.path("column").asInt(0),
                msg.path("ruleId").asText("(unknown)"),
                msg.path("message").asText("")));
      }
    }
    return out;
  }

  private static String truncate(String s) {
    return s.length() > 200 ? s.substring(0, 200) + "…" : s;
  }
}
