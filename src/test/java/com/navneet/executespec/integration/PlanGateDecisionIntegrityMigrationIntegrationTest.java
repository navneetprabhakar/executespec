package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL clean-install, V347 upgrade, and impossible decision-state proof for V348. */
@Tag("integration")
@Testcontainers
class PlanGateDecisionIntegrityMigrationIntegrationTest {
  private static final String PLAN_JSON = "{\"tasks\":[]}";
  private static final String RUN_OWNER = "user-1";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("plan_gate_decision_integrity")
          .withUsername("test")
          .withPassword("test");

  @BeforeAll
  static void installVectorInSharedSchema() {
    // V95's unqualified CREATE EXTENSION otherwise installs vector into the first ephemeral test
    // schema. A second clean-install schema sees the extension globally but cannot resolve its
    // type through its own search_path. Production installs it in a shared schema; mirror that.
    DriverManagerDataSource publicDataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    new JdbcTemplate(publicDataSource)
        .execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
  }

  @Test
  void cleanInstallAndV347UpgradeBothReachTheDecisionIntegrityContract() {
    String upgradeSchema = schema("gate_decision_upgrade");
    DriverManagerDataSource upgradeDataSource = dataSource(upgradeSchema);
    JdbcTemplate upgradeJdbc = new JdbcTemplate(upgradeDataSource);
    String cleanSchema = schema("gate_decision_clean");
    DriverManagerDataSource cleanDataSource = dataSource(cleanSchema);
    JdbcTemplate cleanJdbc = new JdbcTemplate(cleanDataSource);
    try {
      flyway(upgradeDataSource, upgradeSchema, "347").migrate();
      insertRun(upgradeJdbc, "legacy-decision", "AWAITING_APPROVAL");
      insertOpenGate(upgradeJdbc, "legacy-decision");
      String orphanReport = "[{\"contract\":\"historical-orphan\"}]";
      upgradeJdbc.update(
          "INSERT INTO plan_contract_gate_report"
              + " (run_id,report_json,report_digest,requires_typed_ack,contract_count)"
              + " VALUES (?,?,?,false,1)",
          "historical-orphan",
          orphanReport,
          sha256(orphanReport));

      assertThat(flyway(upgradeDataSource, upgradeSchema, "348").migrate().migrationsExecuted)
          .isEqualTo(1);
      assertThat(
              upgradeJdbc.queryForObject(
                  "SELECT decision_kind FROM gate_record WHERE run_id='legacy-decision'",
                  String.class))
          .isNull();
      assertThat(
              upgradeJdbc.queryForObject(
                  "SELECT count(*) FROM information_schema.columns"
                      + " WHERE table_schema=? AND table_name='gate_record'"
                      + " AND column_name IN ('decision_kind','resolved_actor_type',"
                      + " 'decision_request_fingerprint','resolved_authorization_source',"
                      + " 'resolved_authorization_org_id')",
                  Long.class,
                  upgradeSchema))
          .isEqualTo(5L);
      assertThat(
              upgradeJdbc.queryForObject(
                  "SELECT convalidated FROM pg_constraint WHERE conname="
                      + "'fk_plan_contract_gate_report_run'",
                  Boolean.class))
          .isFalse();
      assertThat(
              upgradeJdbc.queryForObject(
                  "SELECT count(*) FROM plan_contract_gate_report"
                      + " WHERE run_id='historical-orphan'",
                  Integer.class))
          .isOne();
      // Several historical migrations perform unqualified constraint DDL after catalog lookup.
      // Rehearse the two supported paths sequentially so an already-populated sibling schema does
      // not create a false failure that cannot occur in a one-schema deployment.
      upgradeJdbc.execute("DROP SCHEMA IF EXISTS " + upgradeSchema + " CASCADE");

      Flyway clean = flyway(cleanDataSource, cleanSchema, "348");
      clean.migrate();
      assertThat(clean.info().current().getVersion().getVersion()).isEqualTo("348");
      assertThat(
              cleanJdbc.queryForObject(
                  "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace"
                      + " WHERE n.nspname=? AND"
                      + " c.conname='ck_versioned_plan_gate_decision_receipt'",
                  Long.class,
                  cleanSchema))
          .isEqualTo(1L);
    } finally {
      upgradeJdbc.execute("DROP SCHEMA IF EXISTS " + upgradeSchema + " CASCADE");
      cleanJdbc.execute("DROP SCHEMA IF EXISTS " + cleanSchema + " CASCADE");
    }
  }

  @Test
  void v347PendingGatePreservesCompleteHistoricalAcknowledgementBytesDuringUpgrade() {
    String schema = schema("gate_ack_upgrade");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      flyway(dataSource, schema, "347").migrate();
      insertRun(jdbc, "historical-ack", "AWAITING_APPROVAL");
      String reportJson = "[{\"contract\":\"historical-breaking-change\"}]";
      String reportDigest = sha256(reportJson);
      String acknowledgementText = " \tbreaking  ";
      jdbc.update(
          "INSERT INTO plan_contract_gate_report"
              + " (run_id,report_json,report_digest,requires_typed_ack,contract_count,"
              + " acknowledged_at,acknowledged_by_user_id,acknowledgement_text)"
              + " VALUES (?,?,?,true,1,'2026-07-27T23:59:58.123456Z'::timestamptz,?,?)",
          "historical-ack",
          reportJson,
          reportDigest,
          RUN_OWNER,
          acknowledgementText);
      insertOpenGate(jdbc, "historical-ack", reportDigest);
      var before =
          jdbc.queryForMap(
              "SELECT report_json,report_digest,requires_typed_ack,contract_count,"
                  + " acknowledged_at,acknowledged_by_user_id,acknowledgement_text"
                  + " FROM plan_contract_gate_report WHERE run_id='historical-ack'");

      assertThat(flyway(dataSource, schema, "348").migrate().migrationsExecuted).isEqualTo(1);

      var after =
          jdbc.queryForMap(
              "SELECT report_json,report_digest,requires_typed_ack,contract_count,"
                  + " acknowledged_at,acknowledged_by_user_id,acknowledgement_text"
                  + " FROM plan_contract_gate_report WHERE run_id='historical-ack'");
      assertThat(after).containsExactlyInAnyOrderEntriesOf(before);
      assertThat(after.get("acknowledgement_text")).isEqualTo(acknowledgementText);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM gate_record WHERE run_id='historical-ack'", String.class))
          .isEqualTo("PENDING");
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void databaseRejectsBadReportBytesAndMakesTypedAcknowledgementImmutable() {
    String schema = schema("gate_report_integrity");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      flyway(dataSource, schema, "348").migrate();
      insertRun(jdbc, "report-run", "AWAITING_APPROVAL");

      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "INSERT INTO plan_contract_gate_report"
                          + " (run_id,report_json,report_digest,requires_typed_ack,contract_count)"
                          + " VALUES (?,?,?,true,1)",
                      "report-run",
                      "[]",
                      "0".repeat(64)))
          .hasMessageContaining("plan contract gate report digest does not match exact bytes");

      String reportJson = "[{\"contract\":\"orders\"}]";
      jdbc.update(
          "INSERT INTO plan_contract_gate_report"
              + " (run_id,report_json,report_digest,requires_typed_ack,contract_count)"
              + " VALUES (?,?,?,true,1)",
          "report-run",
          reportJson,
          sha256(reportJson));
      insertOpenGate(jdbc, "report-run", sha256(reportJson));
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE plan_contract_gate_report SET requires_typed_ack=false"
                          + " WHERE run_id='report-run'"))
          .hasMessageContaining("referenced plan contract gate report is immutable");
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE plan_contract_gate_report SET acknowledged_at=NOW(),"
                          + " acknowledged_by_user_id='user-1',acknowledgement_text=NULL"
                          + " WHERE run_id='report-run'"))
          .hasMessageContaining("plan contract gate acknowledgement text is invalid");
      jdbc.update(
          "UPDATE plan_contract_gate_report SET acknowledged_at=NOW(),"
              + " acknowledged_by_user_id='user-1',acknowledgement_text='BREAKING'"
              + " WHERE run_id='report-run'");

      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE plan_contract_gate_report SET acknowledgement_text='changed'"
                          + " WHERE run_id='report-run'"))
          .hasMessageContaining("plan contract gate acknowledgement is immutable");
      assertThatThrownBy(
              () -> jdbc.update("DELETE FROM plan_contract_gate_report WHERE run_id='report-run'"))
          .hasMessageContaining("referenced plan contract gate report cannot be deleted directly");
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void deferredConstraintRejectsSplitApprovalButAcceptsOneCoherentCommit() throws Exception {
    String schema = schema("gate_decision_coherence");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      flyway(dataSource, schema, "348").migrate();
      insertRun(jdbc, "split-approval", "AWAITING_APPROVAL");
      insertOpenGate(jdbc, "split-approval");

      assertThatThrownBy(
              () ->
                  commit(
                      dataSource,
                      approveGate("split-approval"),
                      "UPDATE plan_gate_resume_outbox SET status='READY'"
                          + " WHERE run_id='split-approval'"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("READY continuation requires APPROVAL_RESUMING run");

      // A deferred-constraint failure happens at COMMIT. Prove the whole attempted decision was
      // rolled back, rather than leaving a resolved gate or READY continuation behind.
      assertThat(
              jdbc.queryForMap(
                  "SELECT g.status AS gate_status,g.approval_evidence_json AS evidence,"
                      + " o.status AS outbox_status,p.status AS run_status"
                      + " FROM gate_record g JOIN plan_gate_resume_outbox o USING (run_id)"
                      + " JOIN pipeline_run p USING (run_id)"
                      + " WHERE g.run_id='split-approval'"))
          .containsEntry("gate_status", "PENDING")
          .containsEntry("outbox_status", "WAITING")
          .containsEntry("run_status", "AWAITING_APPROVAL")
          .containsEntry("evidence", null);

      commit(
          dataSource,
          approveGate("split-approval"),
          "UPDATE plan_gate_resume_outbox SET status='READY' WHERE run_id='split-approval'",
          "UPDATE pipeline_run SET status='APPROVAL_RESUMING' WHERE run_id='split-approval'");

      assertThat(
              jdbc.queryForMap(
                  "SELECT g.status AS gate_status,g.decision_kind,o.status AS outbox_status,"
                      + " p.status AS run_status FROM gate_record g"
                      + " JOIN plan_gate_resume_outbox o ON o.run_id=g.run_id"
                      + " JOIN pipeline_run p ON p.run_id=g.run_id"
                      + " WHERE g.run_id='split-approval'"))
          .containsEntry("gate_status", "APPROVED")
          .containsEntry("decision_kind", "APPROVE")
          .containsEntry("outbox_status", "READY")
          .containsEntry("run_status", "APPROVAL_RESUMING");

      String lateReport = "[{\"contract\":\"late\"}]";
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "INSERT INTO plan_contract_gate_report"
                          + " (run_id,report_json,report_digest,requires_typed_ack,contract_count)"
                          + " VALUES (?,?,?,false,1)",
                      "split-approval",
                      lateReport,
                      sha256(lateReport)))
          .hasMessageContaining(
              "approved plan decision must bind the persisted contract gate report");

      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE pipeline_run SET status='AWAITING_APPROVAL'"
                          + " WHERE run_id='split-approval'"))
          .hasMessageContaining("READY continuation requires APPROVAL_RESUMING run");

      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE plan_gate_resume_outbox SET status='DEAD',completed_at=NOW(),"
                          + " last_error='corrupt payload' WHERE run_id='split-approval'"))
          .hasMessageContaining("dead approved continuation requires FAILED or CANCELLED run");

      commit(
          dataSource,
          "UPDATE plan_gate_resume_outbox SET status='DEAD',completed_at=NOW(),"
              + " last_error='corrupt payload' WHERE run_id='split-approval'",
          "UPDATE pipeline_run SET status='FAILED' WHERE run_id='split-approval'");
      assertThat(
              jdbc.queryForMap(
                  "SELECT o.status AS outbox_status,p.status AS run_status"
                      + " FROM plan_gate_resume_outbox o JOIN pipeline_run p USING (run_id)"
                      + " WHERE o.run_id='split-approval'"))
          .containsEntry("outbox_status", "DEAD")
          .containsEntry("run_status", "FAILED");
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void committedDecisionRejectsReceiptActorAndRequestRewritesWithMatchingDigests()
      throws Exception {
    String schema = schema("gate_receipt_immutability");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      flyway(dataSource, schema, "348").migrate();
      String runId = "immutable-receipt";
      insertRun(jdbc, runId, "AWAITING_APPROVAL");
      insertOpenGate(jdbc, runId);
      commit(
          dataSource,
          approveGate(runId),
          "UPDATE plan_gate_resume_outbox SET status='READY' WHERE run_id='" + runId + "'",
          "UPDATE pipeline_run SET status='APPROVAL_RESUMING' WHERE run_id='" + runId + "'");

      String receiptWithDifferentBytes = " " + approvalEvidence(runId);
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE gate_record SET approval_evidence_json=?,"
                          + " approval_evidence_digest=? WHERE run_id=?",
                      receiptWithDifferentBytes,
                      sha256(receiptWithDifferentBytes),
                      runId))
          .hasMessageContaining("plan gate admission receipt is immutable");

      String differentActorEvidence =
          approvalEvidence(runId)
              .replace("\"actorUserId\":\"user-1\"", "\"actorUserId\":\"user-2\"");
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE gate_record SET resolved_by_user_id='user-2',"
                          + " approval_evidence_json=?,approval_evidence_digest=? WHERE run_id=?",
                      differentActorEvidence,
                      sha256(differentActorEvidence),
                      runId))
          .hasMessageContaining("plan gate admission receipt is immutable");

      String differentRequestFingerprint = "e".repeat(64);
      String originalRequestFragment = "\"decisionRequestFingerprint\":\"" + "f".repeat(64) + "\"";
      String differentRequestFragment =
          "\"decisionRequestFingerprint\":\"" + differentRequestFingerprint + "\"";
      String differentRequestEvidence =
          approvalEvidence(runId).replace(originalRequestFragment, differentRequestFragment);
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE gate_record SET decision_request_fingerprint=?,"
                          + " approval_evidence_json=?,approval_evidence_digest=? WHERE run_id=?",
                      differentRequestFingerprint,
                      differentRequestEvidence,
                      sha256(differentRequestEvidence),
                      runId))
          .hasMessageContaining("plan gate admission receipt is immutable");
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void rejectedAndCancelledDecisionsCannotCommitAgainstTheWrongTerminalTuple() throws Exception {
    String schema = schema("gate_terminal_decisions");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      flyway(dataSource, schema, "348").migrate();
      insertRun(jdbc, "reject-run", "AWAITING_APPROVAL");
      insertOpenGate(jdbc, "reject-run");

      assertThatThrownBy(
              () ->
                  commit(
                      dataSource,
                      rejectGate("reject-run", "REJECT", "REJECTED", "RUN_API"),
                      "UPDATE plan_gate_resume_outbox SET status='CANCELLED',completed_at=NOW()"
                          + " WHERE run_id='reject-run'",
                      "UPDATE pipeline_run SET status='CANCELLED' WHERE run_id='reject-run'"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("ck_versioned_plan_gate_resolution_mapping");

      assertThatThrownBy(() -> commit(dataSource, rejectGate("reject-run", "REJECT", "REJECTED")))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("REJECT decision requires CANCELLED run and outbox");

      commit(
          dataSource,
          rejectGate("reject-run", "CANCEL", "CANCELLED"),
          "UPDATE plan_gate_resume_outbox SET status='CANCELLED',completed_at=NOW()"
              + " WHERE run_id='reject-run'",
          "UPDATE pipeline_run SET status='CANCELLED' WHERE run_id='reject-run'");
      assertThat(
              jdbc.queryForObject(
                  "SELECT decision_kind FROM gate_record WHERE run_id='reject-run'", String.class))
          .isEqualTo("CANCEL");
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void databaseRejectsDigestValidEvidenceWhoseReviewedPlanDisagreesWithTheRun() {
    String schema = schema("gate_evidence_rows");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      flyway(dataSource, schema, "348").migrate();
      insertRun(jdbc, "tampered-evidence", "AWAITING_APPROVAL");
      insertOpenGate(jdbc, "tampered-evidence");
      String tampered =
          approvalEvidence("tampered-evidence").replace(sha256(PLAN_JSON), "9".repeat(64));

      assertThatThrownBy(
              () ->
                  commit(
                      dataSource,
                      approveGate("tampered-evidence", tampered),
                      "UPDATE plan_gate_resume_outbox SET status='READY'"
                          + " WHERE run_id='tampered-evidence'",
                      "UPDATE pipeline_run SET status='APPROVAL_RESUMING'"
                          + " WHERE run_id='tampered-evidence'"))
          .hasMessageContaining("reviewed-plan fingerprint is inconsistent");
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void databaseRejectsDigestValidEvidenceWithMissingOrAdditionalTopLevelKeys() {
    String schema = schema("gate_evidence_shape");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      flyway(dataSource, schema, "348").migrate();

      insertRun(jdbc, "missing-evidence-key", "AWAITING_APPROVAL");
      insertOpenGate(jdbc, "missing-evidence-key");
      String missingRequiredKey =
          approvalEvidence("missing-evidence-key").replace("\"globalRole\":null,", "");
      assertThatThrownBy(
              () -> commit(dataSource, approveGate("missing-evidence-key", missingRequiredKey)))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("ck_plan_gate_decision_evidence_shape");

      insertRun(jdbc, "additional-evidence-key", "AWAITING_APPROVAL");
      insertOpenGate(jdbc, "additional-evidence-key");
      String additionalUnknownKey =
          approvalEvidence("additional-evidence-key")
              .replace(
                  "\"contractAcknowledgementOutcome\":\"NOT_REQUIRED\"}",
                  "\"contractAcknowledgementOutcome\":\"NOT_REQUIRED\",\"unexpected\":null}");
      assertThatThrownBy(
              () ->
                  commit(dataSource, approveGate("additional-evidence-key", additionalUnknownKey)))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("ck_plan_gate_decision_evidence_shape");
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void committedDecisionRejectsLaterRunPlanOwnerOrOrgIdentityDrift() throws Exception {
    String schema = schema("gate_decision_run_identity");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      flyway(dataSource, schema, "348").migrate();
      insertRun(jdbc, "immutable-run-identity", "AWAITING_APPROVAL");
      String reportJson = "[{\"contract\":\"retained-contract\"}]";
      String reportDigest = sha256(reportJson);
      jdbc.update(
          "INSERT INTO plan_contract_gate_report"
              + " (run_id,report_json,report_digest,requires_typed_ack,contract_count)"
              + " VALUES (?,?,?,false,1)",
          "immutable-run-identity",
          reportJson,
          reportDigest);
      insertOpenGate(jdbc, "immutable-run-identity", reportDigest);
      String evidence =
          approvalEvidence("immutable-run-identity")
              .replace(
                  "\"contractGateReportDigest\":null,\"contractRequiresTypedAck\":null,",
                  "\"contractGateReportDigest\":\""
                      + reportDigest
                      + "\",\"contractRequiresTypedAck\":false,");
      commit(
          dataSource,
          approveGate("immutable-run-identity", evidence),
          "UPDATE plan_gate_resume_outbox SET status='READY'"
              + " WHERE run_id='immutable-run-identity'",
          "UPDATE pipeline_run SET status='APPROVAL_RESUMING'"
              + " WHERE run_id='immutable-run-identity'");

      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE pipeline_run SET plan_json='{\"tasks\":[\"changed\"]}'"
                          + " WHERE run_id='immutable-run-identity'"))
          .hasMessageContaining("reviewed-plan fingerprint is inconsistent");
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE pipeline_run SET created_by_user_id='different-owner'"
                          + " WHERE run_id='immutable-run-identity'"))
          .hasMessageContaining("billing identity is inconsistent");
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE pipeline_run SET org_id='different-org'"
                          + " WHERE run_id='immutable-run-identity'"))
          .hasMessageContaining("billing identity is inconsistent");
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE pipeline_run SET ai_provider='OPENAI'"
                          + " WHERE run_id='immutable-run-identity'"))
          .hasMessageContaining("provider is inconsistent");
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE pipeline_run SET credits_charged=2"
                          + " WHERE run_id='immutable-run-identity'"))
          .hasMessageContaining("cost or credit identity is inconsistent");
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE pipeline_run SET cost_estimate_high=1.25"
                          + " WHERE run_id='immutable-run-identity'"))
          .hasMessageContaining("cost or credit identity is inconsistent");
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "DELETE FROM plan_gate_resume_outbox"
                          + " WHERE run_id='immutable-run-identity'"))
          .hasMessageContaining("versioned plan continuation cannot be deleted directly");
      assertThatThrownBy(
              () -> jdbc.update("DELETE FROM gate_record WHERE run_id='immutable-run-identity'"))
          .hasMessageContaining("versioned plan decision gate cannot be deleted directly");

      assertThat(jdbc.update("DELETE FROM pipeline_run WHERE run_id='immutable-run-identity'"))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM gate_record WHERE run_id='immutable-run-identity'",
                  Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM plan_gate_resume_outbox"
                      + " WHERE run_id='immutable-run-identity'",
                  Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM plan_contract_gate_report"
                      + " WHERE run_id='immutable-run-identity'",
                  Integer.class))
          .isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void approvalEvidenceRejectsNegativeMoneyNonPositiveCreditsAndInventedAcknowledgement()
      throws Exception {
    String schema = schema("gate_decision_domains");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      flyway(dataSource, schema, "348").migrate();

      insertRun(jdbc, "negative-money", "AWAITING_APPROVAL");
      insertOpenGate(jdbc, "negative-money");
      String negativeMoney =
          approvalEvidence("negative-money")
              .replace("\"estimateHighUsd\":\"0.000000\"", "\"estimateHighUsd\":\"-0.000001\"");
      assertThatThrownBy(() -> commit(dataSource, approveGate("negative-money", negativeMoney)))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("ck_plan_gate_decision_fixed_scale_money");

      insertRun(jdbc, "zero-credits", "AWAITING_APPROVAL");
      insertOpenGate(jdbc, "zero-credits");
      String zeroCredits =
          approvalEvidence("zero-credits")
              .replace("\"expectedCredits\":1", "\"expectedCredits\":0");
      assertThatThrownBy(() -> commit(dataSource, approveGate("zero-credits", zeroCredits)))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("ck_plan_gate_decision_policy_outcomes");

      insertRun(jdbc, "invented-ack", "AWAITING_APPROVAL");
      insertOpenGate(jdbc, "invented-ack");
      String inventedAcknowledgement =
          approvalEvidence("invented-ack")
              .replace(
                  "\"contractAcknowledgementOutcome\":\"NOT_REQUIRED\"",
                  "\"contractAcknowledgementOutcome\":\"ACKNOWLEDGED\"");
      assertThatThrownBy(
              () ->
                  commit(
                      dataSource,
                      approveGate("invented-ack", inventedAcknowledgement),
                      "UPDATE plan_gate_resume_outbox SET status='READY'"
                          + " WHERE run_id='invented-ack'",
                      "UPDATE pipeline_run SET status='APPROVAL_RESUMING'"
                          + " WHERE run_id='invented-ack'"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("claims a missing contract report");
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void enforcedBindingRejectsReadyRequirementArtifactWithBlockersBeforeApproval() {
    String schema = schema("gate_enforced_ready");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      flyway(dataSource, schema, "348").migrate();
      insertRun(jdbc, "ready-with-blocker", "PLANNING");
      UUID contractId = insertRequirementContract(jdbc, "ready-with-blocker", "READY", 1);

      assertThatThrownBy(() -> insertEnforcedGate(jdbc, "ready-with-blocker", contractId))
          .hasMessageContaining("exact READY zero-blocker RequirementIR");
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void enforcedBindingRejectsNonReadyRequirementArtifactWithZeroBlockersBeforeApproval() {
    String schema = schema("gate_enforced_blocked");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      flyway(dataSource, schema, "348").migrate();
      insertRun(jdbc, "blocked-without-blocker", "PLANNING");
      UUID contractId = insertRequirementContract(jdbc, "blocked-without-blocker", "BLOCKED", 0);

      assertThatThrownBy(() -> insertEnforcedGate(jdbc, "blocked-without-blocker", contractId))
          .hasMessageContaining("exact READY zero-blocker RequirementIR");
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static String approveGate(String runId) {
    return approveGate(runId, approvalEvidence(runId));
  }

  private static String approvalEvidence(String runId) {
    String continuation = "{\"schemaVersion\":1,\"runId\":\"" + runId + "\"}";
    return "{\"schemaVersion\":1,\"policyContract\":\"PLAN_GATE_DECISION_V1\","
        + "\"decision\":\"APPROVE\",\"decidedAt\":\"2026-07-28T00:00:00Z\","
        + "\"resolutionSource\":\"PLAN_APPROVAL_API\",\"resolutionReason\":null,"
        + "\"actorType\":\"HUMAN\",\"actorUserId\":\"user-1\","
        + "\"globalRole\":null,\"orgRole\":null,"
        + "\"effectiveRole\":\"DEVELOPER\","
        + "\"authorizationBasis\":\"RUN_OWNER\",\"authorizationOrgId\":null,"
        + "\"runId\":\""
        + runId
        + "\",\"gateKey\":\"gate:plan:"
        + runId
        + "\",\"reviewedPlanFingerprint\":\""
        + sha256(PLAN_JSON)
        + "\",\"suppliedPlanFingerprint\":\""
        + sha256(PLAN_JSON)
        + "\",\"decisionRequestFingerprint\":\""
        + "f".repeat(64)
        + "\",\"continuationSchemaVersion\":1,\"continuationSha256\":\""
        + sha256(continuation)
        + "\",\"bindingMode\":\"LEGACY\",\"bindingStatus\":\"UNBOUND\","
        + "\"requirementContractId\":null,\"bindingSchemaVersion\":null,"
        + "\"bindingCompilerFingerprint\":null,\"bindingSourceSha256\":null,"
        + "\"bindingExactPlanFingerprint\":null,\"bindingArtifactDigest\":null,"
        + "\"bindingBoundDigest\":null,\"bindingReason\":\"LEGACY_MODE\","
        + "\"requirementCompleteness\":null,\"requirementCount\":null,"
        + "\"acceptanceCriterionCount\":null,\"taskBindingCount\":null,"
        + "\"issueCount\":null,\"blockingIssueCount\":null,"
        + "\"executionMode\":\"REALTIME\",\"estimateHighUsd\":\"0.000000\","
        + "\"hardCapUsd\":null,\"expectedCredits\":1,"
        + "\"creditsConfirmed\":null,\"creditsMatched\":null,"
        + "\"billingOwnerUserId\":\""
        + RUN_OWNER
        + "\","
        + "\"billingOrgId\":\"org-1\",\"provider\":\"anthropic\","
        + "\"keySourceMode\":\"SYSTEM\",\"credentialOrigin\":\"SYSTEM\","
        + "\"ownKeyExempt\":false,\"managedAiMode\":\"MANAGED\","
        + "\"credentialAuthorizationSource\":"
        + "\"SYSTEM_PLATFORM_KEY\",\"credentialAuthorizationOrgId\":\"org-1\","
        + "\"planFingerprintMatched\":true,\"managedAiOutcome\":\"ALLOWED\","
        + "\"managedAiGrantCredits\":null,\"managedAiCreditsRemaining\":null,"
        + "\"managedAiEstimatedCredits\":null,\"managedAiHardRawUsd\":null,"
        + "\"managedAiRemainingRawUsd\":null,"
        + "\"governanceOrgOutcome\":\"ALLOWED\",\"governanceOrgThresholdUsd\":null,"
        + "\"governanceOrgEstimateUsd\":null,\"governanceOrgReason\":null,"
        + "\"governanceProjectOutcome\":\"ALLOWED\",\"governanceProjectCapUsd\":null,"
        + "\"governanceProjectMonthToDateUsd\":null,"
        + "\"governanceProjectProjectedUsd\":null,\"governanceProjectReason\":null,"
        + "\"contractGateReportDigest\":null,\"contractRequiresTypedAck\":null,"
        + "\"contractAcknowledgedAt\":null,\"contractAcknowledgedByUserId\":null,"
        + "\"contractAcknowledgementText\":null,"
        + "\"contractAcknowledgementOutcome\":\"NOT_REQUIRED\"}";
  }

  private static String approveGate(String runId, String evidence) {
    return "UPDATE gate_record SET status='APPROVED',"
        + "resolved_at='2026-07-28T00:00:00Z'::timestamptz,"
        + " admission_contract_version=1,approval_evidence_json='"
        + evidence
        + "',approval_evidence_digest='"
        + sha256(evidence)
        + "',decision_kind='APPROVE',resolved_actor_type='HUMAN',"
        + " resolved_authorization_source='RUN_OWNER',resolved_authorization_org_id=NULL,"
        + " decision_request_fingerprint='"
        + "f".repeat(64)
        + "',resolved_by_user_id='user-1',resolved_by_role='DEVELOPER',"
        + " resolution_source='PLAN_APPROVAL_API',billing_owner_user_id='"
        + RUN_OWNER
        + "',billing_org_id='org-1',"
        + " admitted_at=NOW()"
        + " WHERE run_id='"
        + runId
        + "'";
  }

  private static String rejectGate(String runId, String decision, String gateStatus) {
    return rejectGate(
        runId,
        decision,
        gateStatus,
        "CANCEL".equals(decision) ? "RUN_CANCEL_API" : "PLAN_APPROVAL_API");
  }

  private static String rejectGate(
      String runId, String decision, String gateStatus, String resolutionSource) {
    String continuation = "{\"schemaVersion\":1,\"runId\":\"" + runId + "\"}";
    String evidence =
        "{\"schemaVersion\":1,\"policyContract\":\"PLAN_GATE_DECISION_V1\","
            + "\"decision\":\""
            + decision
            + "\",\"decidedAt\":\"2026-07-28T00:00:00Z\",\"resolutionSource\":\""
            + resolutionSource
            + "\",\"resolutionReason\":\"user decision\",\"actorType\":\"HUMAN\","
            + "\"actorUserId\":\"user-1\",\"globalRole\":null,\"orgRole\":null,"
            + "\"effectiveRole\":\"DEVELOPER\","
            + "\"authorizationBasis\":\"RUN_OWNER\","
            + "\"authorizationOrgId\":null,\"runId\":\""
            + runId
            + "\",\"gateKey\":\"gate:plan:"
            + runId
            + "\",\"reviewedPlanFingerprint\":\""
            + sha256(PLAN_JSON)
            + "\",\"suppliedPlanFingerprint\":null,\"planFingerprintMatched\":null,"
            + "\"decisionRequestFingerprint\":\""
            + "e".repeat(64)
            + "\",\"continuationSchemaVersion\":1,\"continuationSha256\":\""
            + sha256(continuation)
            + "\",\"bindingMode\":\"LEGACY\",\"bindingStatus\":\"UNBOUND\","
            + "\"requirementContractId\":null,\"bindingSchemaVersion\":null,"
            + "\"bindingCompilerFingerprint\":null,\"bindingSourceSha256\":null,"
            + "\"bindingExactPlanFingerprint\":null,\"bindingArtifactDigest\":null,"
            + "\"bindingBoundDigest\":null,\"bindingReason\":\"LEGACY_MODE\","
            + "\"requirementCompleteness\":null,\"requirementCount\":null,"
            + "\"acceptanceCriterionCount\":null,\"taskBindingCount\":null,"
            + "\"issueCount\":null,\"blockingIssueCount\":null,"
            + "\"executionMode\":\"REALTIME\",\"estimateHighUsd\":\"0.000000\","
            + "\"hardCapUsd\":null,\"expectedCredits\":1,"
            + "\"creditsConfirmed\":null,\"creditsMatched\":null,"
            + "\"billingOwnerUserId\":\""
            + RUN_OWNER
            + "\","
            + "\"billingOrgId\":\"org-1\",\"provider\":\"anthropic\","
            + "\"keySourceMode\":null,\"credentialOrigin\":null,"
            + "\"credentialAuthorizationSource\":null,"
            + "\"credentialAuthorizationOrgId\":null,\"ownKeyExempt\":null,"
            + "\"managedAiMode\":null,\"managedAiGrantCredits\":null,"
            + "\"managedAiCreditsRemaining\":null,\"managedAiEstimatedCredits\":null,"
            + "\"managedAiHardRawUsd\":null,\"managedAiRemainingRawUsd\":null,"
            + "\"governanceOrgThresholdUsd\":null,\"governanceOrgEstimateUsd\":null,"
            + "\"governanceOrgReason\":null,\"governanceProjectCapUsd\":null,"
            + "\"governanceProjectMonthToDateUsd\":null,"
            + "\"governanceProjectProjectedUsd\":null,\"governanceProjectReason\":null,"
            + "\"contractGateReportDigest\":null,"
            + "\"contractRequiresTypedAck\":null,\"contractAcknowledgedAt\":null,"
            + "\"contractAcknowledgedByUserId\":null,"
            + "\"contractAcknowledgementText\":null,"
            + "\"managedAiOutcome\":\"NOT_EVALUATED\","
            + "\"governanceOrgOutcome\":\"NOT_EVALUATED\","
            + "\"governanceProjectOutcome\":\"NOT_EVALUATED\","
            + "\"contractAcknowledgementOutcome\":\"NOT_EVALUATED\"}";
    return "UPDATE gate_record SET status='"
        + gateStatus
        + "',resolved_at='2026-07-28T00:00:00Z'::timestamptz,"
        + "admission_contract_version=1,approval_evidence_json='"
        + evidence
        + "',approval_evidence_digest='"
        + sha256(evidence)
        + "',decision_kind='"
        + decision
        + "',resolved_actor_type='HUMAN',resolved_authorization_source='RUN_OWNER',"
        + " resolved_authorization_org_id=NULL,decision_request_fingerprint='"
        + "e".repeat(64)
        + "',resolved_by_user_id='user-1',resolved_by_role='DEVELOPER',"
        + " resolution_source='"
        + resolutionSource
        + "',resolution_reason='user decision',"
        + " billing_owner_user_id='"
        + RUN_OWNER
        + "',billing_org_id='org-1',admitted_at=NOW()"
        + " WHERE run_id='"
        + runId
        + "'";
  }

  private static void insertRun(JdbcTemplate jdbc, String runId, String status) {
    jdbc.update(
        "INSERT INTO pipeline_run"
            + " (run_id,status,started_at,plan_json,org_id,created_by_user_id)"
            + " VALUES (?,?,NOW(),?,'org-1',?)",
        runId,
        status,
        PLAN_JSON,
        RUN_OWNER);
  }

  private static UUID insertRequirementContract(
      JdbcTemplate jdbc, String runId, String completeness, int blockerCount) {
    UUID contractId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO run_requirement_contract (
          id,run_id,schema_version,compiler_fingerprint,source_sha256,
          exact_plan_fingerprint,canonical_artifact_json,artifact_digest,bound_digest,
          completeness,requirement_count,acceptance_criterion_count,task_binding_count,
          issue_count,blocking_issue_count)
        VALUES (?,?,1,?,?,?,'{}',?,?,?,1,1,1,?,?)
        """,
        contractId,
        runId,
        "a".repeat(64),
        "b".repeat(64),
        "c".repeat(64),
        "d".repeat(64),
        "e".repeat(64),
        completeness,
        blockerCount,
        blockerCount);
    return contractId;
  }

  private static void insertEnforcedGate(
      JdbcTemplate jdbc, String runId, UUID requirementContractId) {
    jdbc.update(
        """
        INSERT INTO gate_record (
          gate_key,run_id,gate_type,status,created_at,binding_mode,binding_status,
          requirement_contract_id,binding_schema_version,binding_compiler_fingerprint,
          binding_source_sha256,binding_exact_plan_fingerprint,binding_artifact_digest,
          binding_bound_digest,binding_bound_at)
        VALUES (?,?,'PLAN','PENDING',NOW(),'ENFORCED','BOUND',?,1,?,?,?,?,?,NOW())
        """,
        "gate:plan:" + runId,
        runId,
        requirementContractId,
        "a".repeat(64),
        "b".repeat(64),
        "c".repeat(64),
        "d".repeat(64),
        "e".repeat(64));
  }

  private static void insertOpenGate(JdbcTemplate jdbc, String runId) {
    insertOpenGate(jdbc, runId, null);
  }

  private static void insertOpenGate(
      JdbcTemplate jdbc, String runId, String contractGateReportDigest) {
    String payload = "{\"schemaVersion\":1,\"runId\":\"" + runId + "\"}";
    String payloadDigest = sha256(payload);
    jdbc.update(
        """
        INSERT INTO gate_record (
          gate_key,run_id,gate_type,status,comment,created_at,binding_mode,binding_status,
          binding_reason,contract_gate_report_digest,continuation_schema_version,
          continuation_sha256)
        VALUES (?,?,'PLAN','PENDING',?,NOW(),'LEGACY','UNBOUND','LEGACY_MODE',?,1,?)
        """,
        "gate:plan:" + runId,
        runId,
        payload,
        contractGateReportDigest,
        payloadDigest);
    jdbc.update(
        """
        INSERT INTO plan_gate_resume_outbox (
          run_id,gate_key,payload,payload_sha256,handler_contract_version,status,
          attempt_count,max_attempts,next_retry_at)
        VALUES (?,?,?,?,1,'WAITING',0,8,NOW())
        """,
        runId,
        "gate:plan:" + runId,
        payload,
        payloadDigest);
  }

  private static void commit(DataSource dataSource, String... sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      try {
        for (String command : sql) statement.executeUpdate(command);
        connection.commit();
      } catch (SQLException failure) {
        connection.rollback();
        throw failure;
      }
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String schema(String prefix) {
    return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
  }

  private static DriverManagerDataSource dataSource(String schema) {
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Flyway flyway(DataSource dataSource, String schema, String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .createSchemas(true)
        .target(MigrationVersion.fromVersion(target))
        .load();
  }
}
