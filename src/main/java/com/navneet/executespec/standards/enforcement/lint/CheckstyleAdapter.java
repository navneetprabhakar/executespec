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
 */
@Component
@Slf4j
public class CheckstyleAdapter implements LintAdapter {

  private static final Pattern FILE_PATTERN =
      Pattern.compile("<file\\s+name=\"([^\"]+)\">([\\s\\S]*?)</file>");
  private static final Pattern ERROR_PATTERN = Pattern.compile("<(?:error|warning)\\s+([^/>]+)/>");
  private static final Pattern ATTR_PATTERN = Pattern.compile("(\\w+)=\"([^\"]*)\"");

  private final LintProcessRunner runner;

  public CheckstyleAdapter(LintProcessRunner runner) {
    this.runner = runner;
  }

  @Override
  public String tool() {
    return "checkstyle";
  }

  @Override
  public LintResult run(Path repoRoot, Path configPath, List<Path> changedFiles) {
    List<String> argv = new ArrayList<>(List.of("checkstyle", "-f", "xml"));
    if (configPath != null) {
      argv.add("-c");
      argv.add(configPath.toString());
    }
    if (changedFiles != null && !changedFiles.isEmpty()) {
      for (Path p : changedFiles) argv.add(p.toString());
    } else {
      argv.add(".");
    }

    LintProcessRunner.Result res = runner.run(repoRoot, argv, 60);
    if (!res.executed()) {
      return new LintResult(
          tool(), LintResult.Status.UNAVAILABLE, List.of(), res.unavailableReason());
    }
    // Checkstyle exits non-zero when violations found — same parse-anyway
    // pattern as eslint.
    if (res.exitCode() != 0
        && (res.stdout() == null || res.stdout().isBlank())
        && res.stderr() != null
        && !res.stderr().isBlank()) {
      return new LintResult(
          tool(),
          LintResult.Status.FAILED,
          List.of(),
          "checkstyle stderr: " + truncate(res.stderr()));
    }
    try {
      return new LintResult(tool(), LintResult.Status.SUCCESS, parseXmlOutput(res.stdout()), null);
    } catch (Exception ex) {
      return new LintResult(
          tool(),
          LintResult.Status.FAILED,
          List.of(),
          "could not parse checkstyle xml: " + ex.getMessage());
    }
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
