package com.navneet.executespec.engine;

import java.util.List;

/** Builds the restricted shell command that snapshots a verified terminal-task candidate. */
final class TerminalCandidateSnapshotCommand {

  private TerminalCandidateSnapshotCommand() {}

  static String build(String runId, List<String> deliverables) {
    String snapshotDir = "/tmp/es-passing-candidate-" + safeShellToken(runId);
    StringBuilder command =
        new StringBuilder()
            .append("rm -rf '")
            .append(snapshotDir)
            .append("'; mkdir -p '")
            .append(snapshotDir)
            .append("'; ");
    for (String path : deliverables) {
      command
          .append("if [ -e '")
          .append(path)
          .append("' ]; then mkdir -p '")
          .append(snapshotDir)
          .append("'\"$(dirname '")
          .append(path)
          .append("')\"; cp -a '")
          .append(path)
          .append("' '")
          .append(snapshotDir)
          .append(path)
          .append("'; fi; ");
    }
    return command.append("printf 'ES_SNAPSHOT_OK\\n'").toString();
  }

  private static String safeShellToken(String value) {
    if (value == null || value.isBlank()) return "run";
    return value.replaceAll("[^A-Za-z0-9._-]", "-");
  }
}
