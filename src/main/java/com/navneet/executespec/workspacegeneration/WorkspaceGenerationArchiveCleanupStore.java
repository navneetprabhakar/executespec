package com.navneet.executespec.workspacegeneration;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL authority for deleting only terminal, unreferenced generation archives. */
@Component
public class WorkspaceGenerationArchiveCleanupStore {

  public record Claim(
      UUID id,
      UUID stepId,
      UUID attemptId,
      String runId,
      String kind,
      String key,
      String artifactDigest,
      String storageGeneration,
      long sizeBytes,
      String crc32c,
      String etag) {}

  private static final String UNRESOLVED =
      "('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED','PREPARED','MUTATING',"
          + "'VERIFYING','VERIFIED','CHECKPOINTING','ROLLING_BACK','RECOVERY_FAILED')";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public WorkspaceGenerationArchiveCleanupStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Discovers verified physical uploads whose owning attempt is terminally rolled back. */
  public int enqueueEligible() {
    return transactions.execute(
        ignored ->
            jdbc.update(
                "INSERT INTO workspace_generation_archive_cleanup(id,step_id,attempt_id,"
                    + "checkpoint_kind,object_key,artifact_digest,storage_generation,size_bytes,crc32c,etag,status,created_at)"
                    + " SELECT gen_random_uuid(),s.id,s.attempt_id,CASE WHEN s.object_key LIKE"
                    + " 'local-generation://%' THEN 'LOCAL_ARCHIVE' ELSE 'GCS'"
                    + " END,s.object_key,s.artifact_digest,s.storage_generation,s.size_bytes,s.crc32c,s.etag,'PENDING',NOW()"
                    + " FROM workspace_generation_step s JOIN workspace_generation_attempt a ON"
                    + " a.id=s.attempt_id WHERE s.step_kind IN"
                    + " ('BEFORE_ARCHIVE_UPLOAD','CANDIDATE_ARCHIVE_UPLOAD') AND s.state='VERIFIED'"
                    + " AND a.state='ROLLED_BACK' AND NOT EXISTS (SELECT 1 FROM"
                    + " workspace_generation_archive_cleanup q WHERE q.step_id=s.id) AND NOT EXISTS"
                    + " (SELECT 1 FROM pipeline_run r WHERE r.accepted_checkpoint_key=s.object_key)"
                    + " AND NOT EXISTS (SELECT 1 FROM workspace_generation_attempt live WHERE"
                    + " live.state IN "
                    + UNRESOLVED
                    + " AND (live.before_checkpoint_key=s.object_key "
                    + "OR live.checkpoint_key=s.object_key))"));
  }

  public Optional<Claim> claimNext(Duration staleAfter) {
    return transactions.execute(
        ignored -> {
          List<Claim> rows =
              jdbc.query(
                  "SELECT"
                      + " q.id,q.step_id,q.attempt_id,a.run_id,q.checkpoint_kind,q.object_key,q.artifact_digest,q.storage_generation,q.size_bytes,q.crc32c,q.etag"
                      + " FROM workspace_generation_archive_cleanup q JOIN"
                      + " workspace_generation_attempt a ON a.id=q.attempt_id WHERE"
                      + " q.status='PENDING' OR (q.status='PROCESSING' AND q.claimed_at<?) ORDER BY"
                      + " q.created_at,q.id FOR UPDATE SKIP LOCKED LIMIT 1",
                  (rs, row) ->
                      new Claim(
                          rs.getObject(1, UUID.class),
                          rs.getObject(2, UUID.class),
                          rs.getObject(3, UUID.class),
                          rs.getString(4),
                          rs.getString(5),
                          rs.getString(6),
                          rs.getString(7),
                          rs.getString(8),
                          rs.getLong(9),
                          rs.getString(10),
                          rs.getString(11)),
                  Timestamp.from(Instant.now().minus(staleAfter)));
          if (rows.isEmpty()) return Optional.empty();
          Claim claim = rows.getFirst();
          jdbc.update(
              "UPDATE workspace_generation_archive_cleanup SET status='PROCESSING',"
                  + "claimed_at=NOW(),last_error=NULL WHERE id=?",
              claim.id());
          return Optional.of(claim);
        });
  }

  /** Rechecks all durable references immediately before the irreversible storage call. */
  public boolean stillDeletable(Claim claim) {
    Integer safe =
        jdbc.queryForObject(
            "SELECT CASE WHEN a.state='ROLLED_BACK' "
                + "AND NOT EXISTS (SELECT 1 FROM pipeline_run r WHERE r.accepted_checkpoint_key=?) "
                + "AND NOT EXISTS (SELECT 1 FROM workspace_generation_attempt live WHERE "
                + "live.state IN "
                + UNRESOLVED
                + " AND (live.before_checkpoint_key=? OR live.checkpoint_key=?)) "
                + "THEN 1 ELSE 0 END FROM workspace_generation_attempt a WHERE a.id=?",
            Integer.class,
            claim.key(),
            claim.key(),
            claim.key(),
            claim.attemptId());
    return safe != null && safe == 1;
  }

  public void protectedReference(Claim claim) {
    settle(claim, "PROTECTED", null);
  }

  public void complete(Claim claim) {
    settle(claim, "COMPLETED", null);
  }

  public void fail(Claim claim, String failure) {
    jdbc.update(
        "UPDATE workspace_generation_archive_cleanup SET status='PENDING',last_error=?,"
            + "claimed_at=NULL WHERE id=? AND status='PROCESSING'",
        bounded(failure),
        claim.id());
  }

  private void settle(Claim claim, String status, String error) {
    jdbc.update(
        "UPDATE workspace_generation_archive_cleanup SET status=?,completed_at=NOW(),"
            + "last_error=? WHERE id=? AND status='PROCESSING'",
        status,
        error,
        claim.id());
  }

  private static String bounded(String value) {
    if (value == null || value.isBlank()) return "unspecified";
    return value.length() <= 1000 ? value : value.substring(0, 1000);
  }
}
