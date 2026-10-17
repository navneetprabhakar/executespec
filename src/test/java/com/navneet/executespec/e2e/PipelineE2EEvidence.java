package com.navneet.executespec.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.state.StateStore;

/** Minimal credential-free live-fixture diagnostics; generated source stays in its ignored tree. */
final class PipelineE2EEvidence {
  private PipelineE2EEvidence() {}

  static void save(
      StateStore stateStore,
      String runId,
      com.fasterxml.jackson.databind.JsonNode report,
      com.fasterxml.jackson.databind.JsonNode detail,
      org.springframework.jdbc.core.JdbcTemplate jdbc)
      throws IOException {
    var evidence = new LinkedHashMap<String, Object>();
    stateStore
        .findRun(runId)
        .ifPresent(
            run -> {
              evidence.put("runId", run.getRunId());
              evidence.put("status", run.getStatus());
              evidence.put("statusReason", run.getStatusReason());
              evidence.put("capturedCostUsd", run.getActualCostUsd());
              evidence.put("acceptedTaskIndex", run.getAcceptedTaskIndex());
              evidence.put("workspaceGeneration", run.getAcceptedWorkspaceGeneration());
              evidence.put("buildProtocol", run.getBuildProtocolVersion());
              evidence.put("goalDecision", run.getRunGoalDonenessJson());
            });
    if (report != null) {
      var fields = new LinkedHashMap<String, Object>();
      for (String key :
          java.util.List.of(
              "pipelineResult",
              "issues",
              "accuracyScore",
              "accuracyJson",
              "finalBuildPassed",
              "testsPassed",
              "testsTotal",
              "reviewerVerdict",
              "verificationLedger")) {
        fields.put(key, report.get(key));
      }
      evidence.put("report", fields);
    }
    if (detail != null) {
      evidence.put("failureReason", detail.get("failureReason"));
      evidence.put("tasks", detail.get("tasks"));
    }
    evidence.put(
        "verificationReceipts",
        jdbc.queryForList(
            "SELECT a.task_id,a.state,a.task_contract_json::text AS"
                + " contract,r.outcome,r.evidence_json::text AS evidence FROM"
                + " workspace_generation_attempt a LEFT JOIN"
                + " workspace_generation_verification_receipt r ON r.attempt_id=a.id WHERE"
                + " a.run_id=? ORDER BY a.task_index,a.attempt_number,r.receipt_version LIMIT 30",
            runId));
    Path target = Path.of("target/live-e2e-evidence", runId + ".json");
    Files.createDirectories(target.getParent());
    Files.writeString(
        target, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
  }
}
