package com.navneet.executespec.contracts.breaking;

import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * E2.7 — wraps {@code buf breaking} (https://buf.build) for deterministic Proto breaking-change
 * detection.
 *
 * <p>Invocation pattern: {@code buf breaking <new> --against <old>}. {@code buf} prints one error
 * per line — every line becomes a {@link ContractDiff.Change} with {@code BREAKING} severity (buf's
 * breaking-change check is binary by design).
 */
@Component
@Slf4j
public class ProtoBreakingChangeClassifier implements BreakingChangeClassifier {

  private final CliBreakingChangeRunner runner;

  public ProtoBreakingChangeClassifier(CliBreakingChangeRunner runner) {
    this.runner = runner;
  }

  @Override
  public ContractRegistry.ContractType type() {
    return ContractRegistry.ContractType.PROTO;
  }

  @Override
  public String name() {
    return "ProtoBreakingChangeClassifier";
  }

  @Override
  public ContractDiff classify(String oldContent, String newContent) {
    return classifyInternal(null, oldContent, newContent);
  }

  @Override
  public ContractDiff classify(ExecutionContext context, String oldContent, String newContent) {
    return classifyInternal(context, oldContent, newContent);
  }

  private ContractDiff classifyInternal(
      ExecutionContext context, String oldContent, String newContent) {
    CliBreakingChangeRunner.CliResult result =
        context == null
            ? runner.run(
                oldContent,
                newContent,
                "buf-breaking",
                (oldPath, newPath) ->
                    List.of("buf", "breaking", newPath.toString(), "--against", oldPath.toString()),
                30)
            : runner.run(
                context,
                oldContent,
                newContent,
                "buf-breaking",
                (oldPath, newPath) ->
                    List.of("buf", "breaking", newPath.toString(), "--against", oldPath.toString()),
                30);
    if (!result.executed()) {
      return new ContractDiff(
          "buf unavailable: " + result.unavailableReason(),
          List.of(),
          ContractDiff.Confidence.UNCLASSIFIED,
          name());
    }
    // A hard tool error (invalid proto, missing buf.yaml, parse failure) exits non-zero with the
    // message on stderr and nothing on stdout. Without this guard the loop below iterated empty
    // stdout and returned "0 breaking changes … DETERMINISTIC" — reporting a tool failure as an
    // authoritative all-clear, the strongest possible false negative (RR-1652). Mirrors the
    // oasdiff guard in OpenApiBreakingChangeClassifier.
    if (result.exitCode() != 0
        && (result.stdout() == null || result.stdout().isBlank())
        && result.stderr() != null
        && !result.stderr().isBlank()) {
      return new ContractDiff(
          "buf stderr: " + result.stderr().strip(),
          List.of(),
          ContractDiff.Confidence.UNCLASSIFIED,
          name());
    }
    List<ContractDiff.Change> changes = new ArrayList<>();
    // Empty stdout + exit 0 = no breaking changes.
    if (result.exitCode() == 0 && (result.stdout() == null || result.stdout().isBlank())) {
      return new ContractDiff(
          "No breaking changes (buf)", changes, ContractDiff.Confidence.DETERMINISTIC, name());
    }
    // Each non-empty stdout line is one breaking change reported by buf.
    for (String raw : (result.stdout() == null ? "" : result.stdout()).split("\\r?\\n")) {
      String line = raw.trim();
      if (line.isEmpty()) continue;
      changes.add(
          new ContractDiff.Change(
              ContractDiff.Change.Severity.BREAKING, line, locationFromBufLine(line)));
    }
    long breaking = changes.size();
    return new ContractDiff(
        breaking + " breaking change" + (breaking == 1 ? "" : "s") + " (buf)",
        changes,
        ContractDiff.Confidence.DETERMINISTIC,
        name());
  }

  private static String locationFromBufLine(String line) {
    // buf line shape: "<file>:<line>:<col>: BUF_RULE description"
    int firstColon = line.indexOf(':');
    if (firstColon < 0) return "(unknown)";
    int secondColon = line.indexOf(':', firstColon + 1);
    return secondColon > firstColon
        ? line.substring(0, secondColon)
        : line.substring(0, firstColon);
  }
}
