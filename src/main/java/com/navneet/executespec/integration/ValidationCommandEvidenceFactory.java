package com.navneet.executespec.integration;

import java.nio.file.Path;

import com.navneet.executespec.report.model.CommandExecutionEvidence;
import com.navneet.executespec.reviewer.ReviewerResult;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;

/** Builds bounded, reproducible command evidence for the durable validation report. */
final class ValidationCommandEvidenceFactory {

  private static final int OUTPUT_LIMIT = 6_000;

  private ValidationCommandEvidenceFactory() {}

  static CommandExecutionEvidence build(ReviewerResult reviewResult, Path workspace) {
    if (reviewResult == null || reviewResult.finalBuildOutput() == null) return null;
    String raw = reviewResult.finalBuildOutput();
    String workingDirectory = field(raw, "cwd");
    return new CommandExecutionEvidence(
        field(raw, "command"),
        workingDirectory == null
            ? workspace.toAbsolutePath().normalize().toString()
            : workingDirectory,
        reviewResult.finalBuildExitCode(),
        bounded(raw));
  }

  static CommandExecutionEvidence tests(WorkspaceTestRunner.DetailedResult result, Path workspace) {
    if (result == null) return null;
    return new CommandExecutionEvidence(
        result.command(),
        workspace.toAbsolutePath().normalize().toString(),
        result.exitCode(),
        bounded(result.outputTail()));
  }

  private static String field(String output, String field) {
    if (output == null) return null;
    String prefix = field + "=";
    return output
        .lines()
        .filter(line -> line.startsWith(prefix))
        .map(line -> line.substring(prefix.length()))
        .findFirst()
        .orElse(null);
  }

  private static String bounded(String output) {
    if (output == null) return null;
    return output.length() <= OUTPUT_LIMIT
        ? output
        : output.substring(output.length() - OUTPUT_LIMIT);
  }
}
