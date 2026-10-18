package com.navneet.executespec.integration;

import java.util.List;

import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;

/** Produces bounded attribution claims from successful worker mutation-tool logs. */
final class WorkerProvenanceClaims {
  private WorkerProvenanceClaims() {}

  static List<WorkerReport> from(ExecutionResult result) {
    if (result == null || result.perTask() == null) return List.of();
    java.util.regex.Pattern pathPattern =
        java.util.regex.Pattern.compile("\\\"path\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    List<WorkerReport> claims = new java.util.ArrayList<>();
    for (TaskOutcome outcome : result.perTask()) {
      List<String> paths = new java.util.ArrayList<>();
      for (var worker :
          new com.navneet.executespec.engine.WorkerResult[] {
            outcome.workerOutcome(), outcome.retryWorkerOutcome()
          }) {
        if (worker == null || worker.toolCallLog() == null) continue;
        worker.toolCallLog().stream()
            .filter(line -> line != null && line.endsWith("-> OK"))
            .filter(line -> line.startsWith("writeFile(") || line.startsWith("editFile("))
            .forEach(
                line -> {
                  java.util.regex.Matcher matcher = pathPattern.matcher(line);
                  if (matcher.find()) paths.add(matcher.group(1));
                });
      }
      WorkerReport claim = new WorkerReport();
      claim.setTaskId(outcome.taskId());
      claim.setFilesCreated(paths);
      claim.setFilesModified(paths);
      claims.add(claim);
    }
    return claims;
  }
}
