package com.navneet.executespec.standards.enforcement.lint;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * E3.4 — wraps {@code checkstyle -f xml} (Checkstyle CLI). Parses Checkstyle's XML output via regex
 * (avoids pulling in a heavy XML parser for what is a flat error/warning element list). Each {@code
 * <error>} element becomes one {@link LintViolation}.
 *
 * <p>The XML format is stable across Checkstyle versions; the regex extracts {@code line}, {@code
 * column}, {@code source} (rule id), {@code message}, and groups violations by enclosing {@code
 * <file>}.
 *
 * <p>Same UNAVAILABLE-on-CLI-missing degradation as {@link EslintAdapter}.
 *
 * <p>RR-1051 — admitted by {@link LintExecutionPolicy} before execution, for the same reason as
 * ESLint: a Checkstyle config names check classes to load, so the repository still influences what
 * runs even though the config format is XML rather than a script.
 */
@Component
@Slf4j
public class CheckstyleAdapter implements LintAdapter {

  private static final Pattern FILE_PATTERN =
      Pattern.compile("<file\\s+name=\"([^\"]+)\">([\\s\\S]*?)</file>");
  private static final Pattern ERROR_PATTERN = Pattern.compile("<(?:error|warning)\\s+([^/>]+)/>");
  private static final Pattern ATTR_PATTERN = Pattern.compile("(\\w+)=\"([^\"]*)\"");

  private final LintProcessRunner runner;
  private final LintExecutionPolicy policy;

  public CheckstyleAdapter(LintProcessRunner runner, LintExecutionPolicy policy) {
    this.runner = runner;
    this.policy = policy;
  }

  @Override
  public String tool() {
    return "checkstyle";
  }

  @Override
  public LintResult run(String runId, Path repoRoot, Path configPath, List<Path> changedFiles) {
    LintExecutionPolicy.Admission admission =
        policy.admit(tool(), repoRoot, configPath, changedFiles);
    if (!admission.allowed()) {
      return new LintResult(
          tool(), LintResult.Status.UNAVAILABLE, List.of(), admission.denialReason());
    }

    List<String> argv = new ArrayList<>(List.of(admission.executable(), "-f", "xml"));
    if (admission.configArgument() != null) {
      argv.add("-c");
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
    // RR-1610 — SUCCESS requires a structurally complete report, not merely the absence of stderr.
    // The old three-way conjunction let a silently-killed process (exit=137, stdout="", stderr="")
    // and a non-checkstyle binary on PATH (exit=0, stdout="") fall through to a clean SUCCESS.
    int exit = res.exitCode();
    String stdout = res.stdout();
    // A real `checkstyle -f xml` run always writes a complete <checkstyle>...</checkstyle>
    // document,
    // even with zero violations. Blank stdout is never a valid clean result — it means the tool did
    // not audit (exit=0 wrong binary) or was killed mid-run (exit=137).
    if (stdout == null || stdout.isBlank()) {
      return new LintResult(
          tool(),
          LintResult.Status.FAILED,
          List.of(),
          "checkstyle exited " + exit + " with no XML report" + describeStderr(res.stderr()));
    }
    // Require BOTH the opening and closing root tags so a truncated stream (process killed
    // mid-write) cannot be scored as a complete, passing audit.
    if (!stdout.contains("<checkstyle") || !stdout.contains("</checkstyle>")) {
      return new LintResult(
          tool(),
          LintResult.Status.FAILED,
          List.of(),
          "checkstyle output was not a complete XML report (exit " + exit + ")");
    }
    try {
      return new LintResult(tool(), LintResult.Status.SUCCESS, parseXmlOutput(stdout), null);
    } catch (Exception ex) {
      return new LintResult(
          tool(),
          LintResult.Status.FAILED,
          List.of(),
          "could not parse checkstyle xml (exit " + exit + "): " + ex.getMessage());
    }
  }

  private static String describeStderr(String stderr) {
    return stderr == null || stderr.isBlank() ? "" : ": " + truncate(stderr);
  }

  List<LintViolation> parseXmlOutput(String stdout) {
    List<LintViolation> out = new ArrayList<>();
    if (stdout == null || stdout.isBlank()) return out;
    Matcher fileMatcher = FILE_PATTERN.matcher(stdout);
    while (fileMatcher.find()) {
      String filePath = fileMatcher.group(1);
      String body = fileMatcher.group(2);
      Matcher errorMatcher = ERROR_PATTERN.matcher(body);
      while (errorMatcher.find()) {
        java.util.Map<String, String> attrs = parseAttrs(errorMatcher.group(1));
        out.add(
            new LintViolation(
                filePath,
                parseInt(attrs.get("line"), 0),
                parseInt(attrs.get("column"), 0),
                attrs.getOrDefault("source", "(unknown)"),
                attrs.getOrDefault("message", "")));
      }
    }
    return out;
  }

  static java.util.Map<String, String> parseAttrs(String attrFragment) {
    java.util.Map<String, String> m = new java.util.HashMap<>();
    Matcher am = ATTR_PATTERN.matcher(attrFragment);
    while (am.find()) m.put(am.group(1), am.group(2));
    return m;
  }

  private static int parseInt(String s, int def) {
    if (s == null) return def;
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException ex) {
      return def;
    }
  }

  private static String truncate(String s) {
    return s.length() > 200 ? s.substring(0, 200) + "…" : s;
  }
}
