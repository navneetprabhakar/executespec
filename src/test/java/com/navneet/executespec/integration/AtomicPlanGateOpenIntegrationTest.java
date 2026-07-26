package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.agent.requirement.ExactPlanFingerprint;
import com.navneet.executespec.agent.requirement.RequirementGateBindingService.RequestedMode;
import com.navneet.executespec.harness.repository.GateRepository;
import com.navneet.executespec.harness.repository.PlanGateResumeOutboxRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real PostgreSQL proof that a plan gate is visible only as one coherent durable tuple. */
class AtomicPlanGateOpenIntegrationTest extends PostgresIntegrationBase {
  @Autowired PlanGateOpenService opener;
  @Autowired PipelineRunRepository runs;
  @Autowired GateRepository gates;
  @Autowired PlanGateResumeOutboxRepository outbox;
  @Autowired JdbcTemplate jdbc;

  @Test
  void opensGateStatusAndWaitingContinuationInOneCommitAndReplaysExactly() {
    String runId = "atomic-open-" + UUID.randomUUID();
    String planJson = "{\"planTitle\":\"Exact\",\"tasks\":[]}";
    String continuation = "{\"schemaVersion\":1,\"localRun\":false}";
    insertPlanningRun(runId, planJson);
    var command = command(runId, planJson, continuation, null);

    assertThat(opener.open(command)).isEqualTo(PlanGateOpenService.OpenResult.OPENED);

    var run = runs.findById(runId).orElseThrow();
    assertThat(run.getStatus()).isEqualTo("AWAITING_APPROVAL");
    assertThat(run.getCurrentGate()).isEqualTo("PLAN_APPROVAL");
    var gate = gates.findById("gate:plan:" + runId).orElseThrow();
    assertThat(gate.getStatus()).isEqualTo("PENDING");
    assertThat(gate.getBindingMode()).isEqualTo("LEGACY");
    assertThat(gate.getBindingStatus()).isEqualTo("UNBOUND");
    assertThat(gate.getContinuationSha256()).isEqualTo(PlanGateOpenService.sha256(continuation));
    var intent = outbox.findById(runId).orElseThrow();
    assertThat(intent.getStatus()).isEqualTo("WAITING");
    assertThat(intent.getPayload()).isEqualTo(continuation);

    assertThat(opener.open(command)).isEqualTo(PlanGateOpenService.OpenResult.ALREADY_OPEN);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gate_record WHERE run_id=?", Long.class, runId))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM plan_gate_resume_outbox WHERE run_id=?", Long.class, runId))
        .isEqualTo(1L);
  }

  @Test
  void gateInsertFailureRollsBackStatusGateAndOutboxTogether() {
    String runId = "atomic-rollback-" + UUID.randomUUID();
    String planJson = "{\"planTitle\":\"Exact\",\"tasks\":[]}";
    insertPlanningRun(runId, planJson);

    assertThatThrownBy(
            () -> opener.open(command(runId, planJson, "{\"schemaVersion\":1}", "f".repeat(64))))
        .isInstanceOf(DataAccessException.class);

    assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo("PLANNING");
    assertThat(gates.findById("gate:plan:" + runId)).isEmpty();
    assertThat(outbox.findById(runId)).isEmpty();
  }

  @Test
  void finalRunTransitionFailureRollsBackAlreadyInsertedGateAndOutbox() {
    String runId = "atomic-late-rollback-" + UUID.randomUUID();
    String planJson = "{\"planTitle\":\"Exact\",\"tasks\":[]}";
    insertPlanningRun(runId, planJson);
    jdbc.execute(
        """
        CREATE OR REPLACE FUNCTION reject_atomic_gate_run_transition()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF NEW.run_id LIKE 'atomic-late-rollback-%'
             AND NEW.status = 'AWAITING_APPROVAL' THEN
            RAISE EXCEPTION 'injected late plan-gate failure';
          END IF;
          RETURN NEW;
        END;
        $$
        """);
    jdbc.execute(
        """
        CREATE TRIGGER trg_reject_atomic_gate_run_transition
        BEFORE UPDATE ON pipeline_run
        FOR EACH ROW EXECUTE FUNCTION reject_atomic_gate_run_transition()
        """);
    try {
      assertThatThrownBy(() -> opener.open(command(runId, planJson, "{\"schemaVersion\":1}", null)))
          .isInstanceOf(DataAccessException.class);
    } finally {
      jdbc.execute("DROP TRIGGER trg_reject_atomic_gate_run_transition ON pipeline_run");
      jdbc.execute("DROP FUNCTION reject_atomic_gate_run_transition()");
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM pipeline_run WHERE run_id=?", String.class, runId))
        .isEqualTo("PLANNING");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gate_record WHERE run_id=?", Long.class, runId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM plan_gate_resume_outbox WHERE run_id=?", Long.class, runId))
        .isZero();
  }

  @Test
  void conflictingContinuationCannotRewriteAnOpenGate() {
    String runId = "atomic-conflict-" + UUID.randomUUID();
    String planJson = "{\"planTitle\":\"Exact\",\"tasks\":[]}";
    insertPlanningRun(runId, planJson);
    opener.open(command(runId, planJson, "{\"schemaVersion\":1}", null));

    assertThatThrownBy(
            () ->
                opener.open(
                    command(runId, planJson, "{\"schemaVersion\":1,\"changed\":true}", null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Conflicting durable plan gate");

    assertThat(gates.findById("gate:plan:" + runId).orElseThrow().getComment())
        .isEqualTo("{\"schemaVersion\":1}");
  }

  @Test
  void replayRejectsMatchingBytesStoredUnderTheWrongGateType() {
    String runId = "atomic-wrong-type-" + UUID.randomUUID();
    String planJson = "{\"planTitle\":\"Exact\",\"tasks\":[]}";
    String continuation = "{\"schemaVersion\":1}";
    String continuationSha = PlanGateOpenService.sha256(continuation);
    insertPlanningRun(runId, planJson);
    jdbc.update(
        """
        INSERT INTO gate_record (
          gate_key,run_id,gate_type,status,comment,created_at,binding_mode,binding_status,
          binding_reason,continuation_schema_version,continuation_sha256
        ) VALUES (?, ?, 'MERGE', 'PENDING', ?, ?, 'LEGACY', 'UNBOUND', 'LEGACY_MODE', 1, ?)
        """,
        "gate:plan:" + runId,
        runId,
        continuation,
        Timestamp.from(Instant.now()),
        continuationSha);
    jdbc.update(
        """
        INSERT INTO plan_gate_resume_outbox (
          run_id,gate_key,payload,payload_sha256,handler_contract_version,status,
          attempt_count,max_attempts,next_retry_at
        ) VALUES (?, ?, ?, ?, 1, 'WAITING', 0, 8, ?)
        """,
        runId,
        "gate:plan:" + runId,
        continuation,
        continuationSha,
        Timestamp.from(Instant.now()));
    jdbc.update(
        "UPDATE pipeline_run SET status='AWAITING_APPROVAL', current_gate='PLAN_APPROVAL' WHERE"
            + " run_id=?",
        runId);

    assertThatThrownBy(() -> opener.open(command(runId, planJson, continuation, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Conflicting durable plan gate");
  }

  private PlanGateOpenService.OpenCommand command(
      String runId, String planJson, String continuation, String reportDigest) {
    return new PlanGateOpenService.OpenCommand(
        runId,
        planJson,
        ExactPlanFingerprint.sha256(planJson),
        continuation,
        1,
        reportDigest,
        RequestedMode.LEGACY);
  }

  private void insertPlanningRun(String runId, String planJson) {
    jdbc.update(
        """
        INSERT INTO pipeline_run
          (run_id,status,started_at,user_request,plan_json,credits_charged,cost_estimate_high)
        VALUES (?, 'PLANNING', ?, 'Original requirement', ?, 3, 1.25)
        """,
        runId,
        Timestamp.from(Instant.now()),
        planJson);
  }
}
