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
  public LintResult run(Path repoRoot, Path configPath, List<Path> changedFiles) {
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

    LintProcessRunner.Result res = runner.run(repoRoot, argv, policy.timeoutSeconds());
    if (!res.executed()) {
      return new LintResult(
          tool(), LintResult.Status.UNAVAILABLE, List.of(), res.unavailableReason());
    }
    // eslint exits non-zero when violations found — still parse the
    // stdout. Only treat empty-stdout-with-stderr as adapter failure.
    if (res.exitCode() != 0
        && (res.stdout() == null || res.stdout().isBlank())
        && res.stderr() != null
        && !res.stderr().isBlank()) {
      return new LintResult(
          tool(), LintResult.Status.FAILED, List.of(), "eslint stderr: " + truncate(res.stderr()));
    }
    try {
      return new LintResult(tool(), LintResult.Status.SUCCESS, parseJsonOutput(res.stdout()), null);
    } catch (Exception ex) {
      return new LintResult(
          tool(),
          LintResult.Status.FAILED,
          List.of(),
          "could not parse eslint json: " + ex.getMessage());
    }
  }

  List<LintViolation> parseJsonOutput(String stdout) throws Exception {
    if (stdout == null || stdout.isBlank()) return List.of();
    JsonNode arr = json.readTree(stdout);
    if (!arr.isArray()) return List.of();
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
