package com.navneet.executespec.contracts.breaking;

import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.contracts.ContractRegistry;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * E2.7 — wraps {@code oasdiff} (https://github.com/oasdiff/oasdiff) for deterministic OpenAPI
 * breaking-change detection. Falls back to UNCLASSIFIED when oasdiff isn't on PATH; the worker
 * container can install it and the same code path activates without redeploy.
 *
 * <p>Invocation: {@code oasdiff breaking <old> <new> --format text}. Parsing is line-based — every
 * line beginning with {@code "warning"} or {@code "error"} becomes a {@link ContractDiff.Change};
 * we mark {@code error} lines BREAKING and {@code warning} lines NON_BREAKING.
 */
@Component
@Slf4j
public class OpenApiBreakingChangeClassifier implements BreakingChangeClassifier {

  private final CliBreakingChangeRunner runner;

  public OpenApiBreakingChangeClassifier(CliBreakingChangeRunner runner) {
    this.runner = runner;
  }

  @Override
  public ContractRegistry.ContractType type() {
    return ContractRegistry.ContractType.OPENAPI;
  }

  @Override
  public String name() {
    return "OpenApiBreakingChangeClassifier";
  }

  @Override
  public ContractDiff classify(String oldContent, String newContent) {
    CliBreakingChangeRunner.CliResult result =
        runner.run(
            oldContent,
            newContent,
            "oasdiff",
            (oldPath, newPath) ->
                List.of(
                    "oasdiff",
                    "breaking",
                    oldPath.toString(),
                    newPath.toString(),
                    "--format",
                    "text"),
            30);
    if (!result.executed()) {
      return unclassified(result.unavailableReason());
    }
    // oasdiff exits non-zero when it finds breaking changes — DON'T treat
    // that as failure. We only treat exit when stderr is set + stdout empty.
    if (result.exitCode() != 0
        && (result.stdout() == null || result.stdout().isBlank())
        && result.stderr() != null
        && !result.stderr().isBlank()) {
      return unclassified("oasdiff stderr: " + truncate(result.stderr()));
    }
    return parseLines(result.stdout() == null ? "" : result.stdout());
  }

  ContractDiff parseLines(String stdout) {
    List<ContractDiff.Change> changes = new ArrayList<>();
    for (String raw : stdout.split("\\r?\\n")) {
      String line = raw.trim();
      if (line.isEmpty()) continue;
      String lower = line.toLowerCase();
      if (lower.startsWith("error")) {
        changes.add(
            new ContractDiff.Change(
                ContractDiff.Change.Severity.BREAKING, line, locationFromLine(line)));
      } else if (lower.startsWith("warning")) {
        changes.add(
            new ContractDiff.Change(
                ContractDiff.Change.Severity.NON_BREAKING, line, locationFromLine(line)));
      } else if (lower.contains("info")) {
        changes.add(
            new ContractDiff.Change(
                ContractDiff.Change.Severity.COSMETIC, line, locationFromLine(line)));
      }
    }
    long breaking =
        changes.stream().filter(c -> c.severity() == ContractDiff.Change.Severity.BREAKING).count();
    String summary =
        breaking > 0
            ? breaking + " breaking change" + (breaking == 1 ? "" : "s") + " (oasdiff)"
            : changes.isEmpty()
                ? "No breaking changes (oasdiff)"
                : changes.size() + " non-breaking change(s) (oasdiff)";
    return new ContractDiff(summary, changes, ContractDiff.Confidence.DETERMINISTIC, name());
  }

  private static String locationFromLine(String line) {
    // oasdiff lines often look like:
    //   error at /paths/~1pay/post/responses/200: response field 'currency' was removed
    int at = line.indexOf(" at ");
    int colon = at >= 0 ? line.indexOf(':', at) : -1;
    return colon > at ? line.substring(at + 4, colon).trim() : "(unknown)";
  }

  private ContractDiff unclassified(String reason) {
    return new ContractDiff(
        "oasdiff unavailable: " + reason, List.of(), ContractDiff.Confidence.UNCLASSIFIED, name());
  }

  private static String truncate(String s) {
    return s.length() > 200 ? s.substring(0, 200) + "…" : s;
  }
}
