package com.navneet.executespec.integration.scm;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.integration.RunWorkspaceAuthorityService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Durable state transitions for exact Git artifacts prepared before SCM publication. */
@Component
public class PublicationPreparationStore {

  private final JdbcTemplate jdbc;
  private final PublicationExternalEffectStore externalEffects;

  public PublicationPreparationStore(JdbcTemplate jdbc) {
    this(jdbc, new PublicationExternalEffectStore(jdbc));
  }

  @Autowired
  PublicationPreparationStore(JdbcTemplate jdbc, PublicationExternalEffectStore externalEffects) {
    this.jdbc = jdbc;
    this.externalEffects = externalEffects;
  }

  @Transactional
  public UUID admit(PreparationIntent intent, List<RepositoryIntent> repositories) {
    if (repositories == null || repositories.isEmpty()) {
      throw new IllegalArgumentException("publication preparation requires a repository target");
    }
    Optional<PreparationState> existing =
        findByEvidence(
            intent.runId(), intent.acceptedGeneration(), intent.validationEvidenceDigest());
    if (existing.isPresent()) {
      if ("PREPARED".equals(existing.get().state())) return existing.get().preparationId();
      throw new IllegalStateException(
          "PUBLICATION_PREPARATION_OUTCOME_UNRESOLVED:" + existing.get().state());
    }

    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO workspace_publication_preparation
          (id, run_id, accepted_generation, accepted_attempt_id,
           accepted_checkpoint_kind, accepted_checkpoint_key, accepted_checkpoint_digest,
           accepted_checkpoint_completed_at, validation_report_id, validation_evidence_digest,
           validation_evidence_json, validation_report_updated_at, workspace_authority_owner,
           expected_repository_count)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        intent.runId(),
        intent.acceptedGeneration(),
        intent.acceptedAttemptId(),
        intent.acceptedCheckpointKind(),
        intent.acceptedCheckpointKey(),
        intent.acceptedCheckpointDigest(),
        Timestamp.from(intent.acceptedCheckpointCompletedAt()),
        intent.validationReportId(),
        intent.validationEvidenceDigest(),
        intent.validationEvidenceJson(),
        Timestamp.from(intent.validationReportUpdatedAt()),
        intent.workspaceAuthorityOwner(),
        repositories.size());
    for (RepositoryIntent repository : repositories) {
      jdbc.update(
          """
          INSERT INTO workspace_publication_repository_receipt
            (id, preparation_id, repository_identity, repository_identity_digest,
             relative_worktree_path, publication_branch, provision_required, commit_message,
             commit_message_digest, committed_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          repository.id(),
          id,
          repository.repositoryIdentity(),
          repository.repositoryIdentityDigest(),
          repository.relativeWorktreePath(),
          repository.publicationBranch(),
          repository.provisionRequired(),
          repository.commitMessage(),
          repository.commitMessageDigest(),
          Timestamp.from(repository.committedAt()));
    }
    int preparing =
        jdbc.update(
            """
            UPDATE workspace_publication_preparation
               SET state='PREPARING', preparing_at=NOW()
             WHERE id=? AND state='ADMITTED'
            """,
            id);
    if (preparing != 1) throw new IllegalStateException("PUBLICATION_PREPARATION_ADMISSION_LOST");
    return id;
  }

  public List<RepositoryIntent> admittedRepositories(UUID preparationId) {
    return jdbc.query(
        """
        SELECT id, repository_identity, repository_identity_digest, relative_worktree_path,
               publication_branch, provision_required, commit_message, commit_message_digest,
               committed_at
          FROM workspace_publication_repository_receipt
         WHERE preparation_id=? AND state='ADMITTED'
         ORDER BY repository_identity_digest
        """,
        (rs, row) ->
            new RepositoryIntent(
                rs.getObject("id", UUID.class),
                rs.getString("repository_identity"),
                rs.getString("repository_identity_digest"),
                rs.getString("relative_worktree_path"),
                rs.getString("publication_branch"),
                rs.getBoolean("provision_required"),
                rs.getString("commit_message"),
                rs.getString("commit_message_digest"),
                rs.getTimestamp("committed_at").toInstant()),
        preparationId);
  }

  @Transactional
  public void recordPreparedRepository(
      UUID repositoryReceiptId,
      String parentCommitId,
      String treeId,
      String commitId,
      boolean hasChanges) {
    int updated =
        jdbc.update(
            """
            UPDATE workspace_publication_repository_receipt
               SET state='PREPARED', parent_commit_id=?, tree_id=?, commit_id=?,
                   has_changes=?, prepared_at=NOW()
             WHERE id=? AND state='ADMITTED'
            """,
            parentCommitId,
            treeId,
            commitId,
            hasChanges,
            repositoryReceiptId);
    if (updated != 1) throw new IllegalStateException("PUBLICATION_REPOSITORY_SETTLEMENT_LOST");
  }

  @Transactional
  public void markPrepared(UUID preparationId) {
    int updated =
        jdbc.update(
            """
            UPDATE workspace_publication_preparation
               SET state='PREPARED', prepared_at=NOW()
             WHERE id=? AND state='PREPARING'
            """,
            preparationId);
    if (updated != 1) throw new IllegalStateException("PUBLICATION_PREPARATION_SETTLEMENT_LOST");
  }

  @Transactional
  public void fail(UUID preparationId, String code, boolean indeterminate) {
    String state = indeterminate ? "INDETERMINATE" : "FAILED";
    jdbc.update(
        """
        UPDATE workspace_publication_repository_receipt
           SET state=?, failure_code=?, failed_at=NOW()
         WHERE preparation_id=? AND state='ADMITTED'
        """,
        state,
        boundedCode(code),
        preparationId);
    jdbc.update(
        """
        UPDATE workspace_publication_preparation
           SET state=?, failure_code=?, failed_at=NOW()
         WHERE id=? AND state IN ('ADMITTED','PREPARING')
        """,
        state,
        boundedCode(code),
        preparationId);
  }

  public Optional<PreparedPublication> findPrepared(
      String runId, String acceptedGeneration, String validationEvidenceDigest) {
    List<PreparedPublication> rows =
        jdbc.query(
            """
            SELECT id, run_id, accepted_generation, validation_report_id,
                   validation_evidence_digest, prepared_at
              FROM workspace_publication_preparation
             WHERE run_id=? AND accepted_generation=? AND validation_evidence_digest=?
               AND state='PREPARED'
             ORDER BY prepared_at DESC, id DESC
             LIMIT 1
            """,
            (rs, row) ->
                new PreparedPublication(
                    rs.getObject("id", UUID.class),
                    rs.getString("run_id"),
                    rs.getString("accepted_generation"),
                    rs.getString("validation_report_id"),
                    rs.getString("validation_evidence_digest"),
                    rs.getTimestamp("prepared_at").toInstant()),
            runId,
            acceptedGeneration,
            validationEvidenceDigest);
    return rows.stream().findFirst();
  }

  private Optional<PreparationState> findByEvidence(
      String runId, String acceptedGeneration, String validationEvidenceDigest) {
    return jdbc
        .query(
            """
            SELECT id,state FROM workspace_publication_preparation
             WHERE run_id=? AND accepted_generation=? AND validation_evidence_digest=?
             LIMIT 1
            """,
            (rs, row) ->
                new PreparationState(rs.getObject("id", UUID.class), rs.getString("state")),
            runId,
            acceptedGeneration,
            validationEvidenceDigest)
        .stream()
        .findFirst();
  }

  public List<PreparedRepository> preparedRepositories(UUID preparationId) {
    return jdbc.query(
        """
        SELECT id, repository_identity, repository_identity_digest, relative_worktree_path,
               publication_branch, parent_commit_id, tree_id, commit_id, has_changes,
               provision_required, commit_message, committed_at
          FROM workspace_publication_repository_receipt
         WHERE preparation_id=? AND state='PREPARED'
         ORDER BY repository_identity_digest
        """,
        (rs, row) ->
            new PreparedRepository(
                rs.getObject("id", UUID.class),
                rs.getString("repository_identity"),
                rs.getString("repository_identity_digest"),
                rs.getString("relative_worktree_path"),
                rs.getString("publication_branch"),
                rs.getString("parent_commit_id"),
                rs.getString("tree_id"),
                rs.getString("commit_id"),
                rs.getBoolean("has_changes"),
                rs.getBoolean("provision_required"),
                rs.getString("commit_message"),
                rs.getTimestamp("committed_at").toInstant()),
        preparationId);
  }

  public Optional<PreparedRepository> requireRepository(
      UUID preparationId, String repositoryIdentityDigest, String branch) {
    return preparedRepositories(preparationId).stream()
        .filter(
            repository ->
                repository.repositoryIdentityDigest().equals(repositoryIdentityDigest)
                    && repository.publicationBranch().equals(branch))
        .findFirst();
  }

  @Transactional
  public PublicationAttempt admitPublicationAttempt(
      UUID preparationId,
      PreparedRepository repository,
      String remoteIdentityDigest,
      String expectedRemoteSha) {
    return externalEffects.admitPublicationAttempt(
        preparationId, repository, remoteIdentityDigest, expectedRemoteSha);
  }

  @Transactional
  public void markPublicationDispatched(UUID attemptId) {
    externalEffects.markPublicationDispatched(attemptId);
  }

  @Transactional
  public void settlePublication(
      UUID attemptId,
      RunWorkspaceAuthorityService.Lease lease,
      boolean published,
      String providerReceiptDigest,
      String failureCode) {
    externalEffects.settlePublication(
        attemptId, lease, published, providerReceiptDigest, failureCode);
  }

  @Transactional
  public ExternalRecovery recoverStalePublicationAttempts(Instant cutoff) {
    return externalEffects.recoverStalePublicationAttempts(cutoff);
  }

  @Transactional
  public ExternalRecovery recoverStaleRepositoryProvisions(Instant cutoff) {
    return externalEffects.recoverStaleRepositoryProvisions(cutoff);
  }

  @Transactional
  public int recoverStalePreparations(Instant cutoff) {
    jdbc.update(
        """
        UPDATE workspace_publication_repository_receipt rr
           SET state='INDETERMINATE', failure_code='PUBLICATION_PREPARATION_OUTCOME_LOST',
               failed_at=NOW()
          FROM workspace_publication_preparation p
         WHERE rr.preparation_id=p.id AND rr.state='ADMITTED'
           AND p.state IN ('ADMITTED','PREPARING') AND p.updated_at < ?
        """,
        Timestamp.from(cutoff));
    return jdbc.update(
        """
        UPDATE workspace_publication_preparation
           SET state='INDETERMINATE', failure_code='PUBLICATION_PREPARATION_OUTCOME_LOST',
               failed_at=NOW()
         WHERE state IN ('ADMITTED','PREPARING') AND updated_at < ?
        """,
        Timestamp.from(cutoff));
  }

  @Transactional
  public RepositoryProvision admitRepositoryProvision(
      UUID preparationId, PreparedRepository repository, String provider) {
    return externalEffects.admitRepositoryProvision(preparationId, repository, provider);
  }

  @Transactional
  public void markRepositoryProvisionDispatched(UUID provisionId) {
    externalEffects.markRepositoryProvisionDispatched(provisionId);
  }

  @Transactional
  public void settleRepositoryProvision(
      UUID provisionId,
      RunWorkspaceAuthorityService.Lease lease,
      boolean provisioned,
      String remoteUrl,
      String remoteIdentityDigest,
      String failureCode) {
    externalEffects.settleRepositoryProvision(
        provisionId, lease, provisioned, remoteUrl, remoteIdentityDigest, failureCode);
  }

  public List<RepositoryProvision> findRepositoryProvision(UUID repositoryReceiptId) {
    return externalEffects.findRepositoryProvision(repositoryReceiptId);
  }

  private static String boundedCode(String code) {
    if (code == null || code.isBlank()) return "PUBLICATION_PREPARATION_FAILED";
    String normalized = code.replaceAll("[^A-Z0-9_]", "_");
    return normalized.substring(0, Math.min(80, normalized.length()));
  }

  public record PreparationIntent(
      String runId,
      String acceptedGeneration,
      UUID acceptedAttemptId,
      String acceptedCheckpointKind,
      String acceptedCheckpointKey,
      String acceptedCheckpointDigest,
      Instant acceptedCheckpointCompletedAt,
      String validationReportId,
      String validationEvidenceDigest,
      String validationEvidenceJson,
      Instant validationReportUpdatedAt,
      UUID workspaceAuthorityOwner) {}

  public record RepositoryIntent(
      UUID id,
      String repositoryIdentity,
      String repositoryIdentityDigest,
      String relativeWorktreePath,
      String publicationBranch,
      boolean provisionRequired,
      String commitMessage,
      String commitMessageDigest,
      Instant committedAt) {}

  public record PreparedPublication(
      UUID preparationId,
      String runId,
      String acceptedGeneration,
      String validationReportId,
      String validationEvidenceDigest,
      Instant preparedAt) {}

  private record PreparationState(UUID preparationId, String state) {}

  public record PreparedRepository(
      UUID repositoryReceiptId,
      String repositoryIdentity,
      String repositoryIdentityDigest,
      String relativeWorktreePath,
      String publicationBranch,
      String parentCommitId,
      String treeId,
      String commitId,
      boolean hasChanges,
      boolean provisionRequired,
      String commitMessage,
      Instant committedAt) {}

  public record PublicationAttempt(
      UUID attemptId,
      UUID preparationId,
      UUID repositoryReceiptId,
      String publicationBranch,
      String preparedCommitId,
      String state,
      String idempotencyKey,
      int attemptNumber) {}

  public record RepositoryProvision(
      UUID provisionId,
      UUID preparationId,
      UUID repositoryReceiptId,
      String provider,
      String targetRepositoryIdentity,
      String state,
      String remoteUrl,
      int attemptNumber) {}

  public record ExternalRecovery(int admittedFailed, int dispatchedIndeterminate) {}
}
