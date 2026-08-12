package com.navneet.executespec.integration.scm;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.integration.RunWorkspaceAuthorityService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Durable admission, dispatch, settlement, and recovery of irreversible publication effects. */
@Component
@RequiredArgsConstructor
class PublicationExternalEffectStore {

  private final JdbcTemplate jdbc;

  @Transactional
  PublicationPreparationStore.PublicationAttempt admitPublicationAttempt(
      UUID preparationId,
      PublicationPreparationStore.PreparedRepository repository,
      String remoteIdentityDigest,
      String expectedRemoteSha) {
    String idempotencyBase =
        "publication:"
            + repository.repositoryReceiptId()
            + ":"
            + remoteIdentityDigest
            + ":"
            + (expectedRemoteSha == null ? "none" : expectedRemoteSha.toLowerCase());
    List<PublicationPreparationStore.PublicationAttempt> prior =
        findPublicationAttempts(
            repository.repositoryReceiptId(), remoteIdentityDigest, expectedRemoteSha);
    if (!prior.isEmpty() && !"FAILED".equals(prior.getFirst().state())) return prior.getFirst();
    int attemptNumber = prior.isEmpty() ? 1 : prior.getFirst().attemptNumber() + 1;
    String idempotencyKey = idempotencyBase + ":attempt:" + attemptNumber;
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO workspace_publication_attempt
          (id, preparation_id, repository_receipt_id, repository_identity_digest,
           publication_branch, prepared_commit_id, remote_identity_digest,
           expected_remote_sha, idempotency_key, attempt_number)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        preparationId,
        repository.repositoryReceiptId(),
        repository.repositoryIdentityDigest(),
        repository.publicationBranch(),
        repository.commitId(),
        remoteIdentityDigest,
        expectedRemoteSha,
        idempotencyKey,
        attemptNumber);
    return new PublicationPreparationStore.PublicationAttempt(
        id,
        preparationId,
        repository.repositoryReceiptId(),
        repository.publicationBranch(),
        repository.commitId(),
        "ADMITTED",
        idempotencyKey,
        attemptNumber);
  }

  @Transactional
  void markPublicationDispatched(UUID attemptId) {
    int updated =
        jdbc.update(
            """
            UPDATE workspace_publication_attempt
               SET state='DISPATCHED', dispatched_at=NOW()
             WHERE id=? AND state='ADMITTED'
            """,
            attemptId);
    if (updated != 1) throw new IllegalStateException("PUBLICATION_ATTEMPT_DISPATCH_LOST");
  }

  @Transactional
  void settlePublication(
      UUID attemptId,
      RunWorkspaceAuthorityService.Lease lease,
      boolean published,
      String providerReceiptDigest,
      String failureCode) {
    requirePublicationLease(lease);
    String state = published ? "PUBLISHED" : "INDETERMINATE";
    int updated =
        jdbc.update(
            """
            UPDATE workspace_publication_attempt a
               SET state=?, provider_receipt_digest=?, failure_code=?, settled_at=NOW()
              FROM workspace_publication_preparation p, run_workspace_authority authority
             WHERE a.id=? AND a.state='DISPATCHED' AND p.id=a.preparation_id
               AND authority.run_id=p.run_id AND authority.run_id=?
               AND authority.owner_token=? AND authority.authority_type='PUBLICATION'
               AND authority.lease_expires_at >= NOW()
            """,
            state,
            providerReceiptDigest,
            published ? null : boundedCode(failureCode),
            attemptId,
            lease.runId(),
            lease.ownerToken());
    if (updated != 1) {
      throw new IllegalStateException("PUBLICATION_ATTEMPT_SETTLEMENT_FENCE_REJECTED");
    }
  }

  @Transactional
  PublicationPreparationStore.ExternalRecovery recoverStalePublicationAttempts(Instant cutoff) {
    int admittedFailed =
        jdbc.update(
            """
            UPDATE workspace_publication_attempt
               SET state='FAILED', failure_code='PUBLICATION_DISPATCH_NOT_STARTED',
                   settled_at=NOW()
             WHERE state='ADMITTED' AND admitted_at < ?
            """,
            Timestamp.from(cutoff));
    int dispatchedIndeterminate =
        jdbc.update(
            """
            UPDATE workspace_publication_attempt
               SET state='INDETERMINATE', failure_code='PUBLICATION_OUTCOME_LOST',
                   settled_at=NOW()
             WHERE state='DISPATCHED' AND updated_at < ?
            """,
            Timestamp.from(cutoff));
    return new PublicationPreparationStore.ExternalRecovery(
        admittedFailed, dispatchedIndeterminate);
  }

  @Transactional
  PublicationPreparationStore.RepositoryProvision admitRepositoryProvision(
      UUID preparationId,
      PublicationPreparationStore.PreparedRepository repository,
      String provider) {
    String keyBase = "repository-provision:" + repository.repositoryReceiptId();
    List<PublicationPreparationStore.RepositoryProvision> replay =
        findRepositoryProvision(repository.repositoryReceiptId());
    if (!replay.isEmpty() && !"FAILED".equals(replay.getFirst().state())) return replay.getFirst();
    int attemptNumber = replay.isEmpty() ? 1 : replay.getFirst().attemptNumber() + 1;
    String key = keyBase + ":attempt:" + attemptNumber;
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO workspace_publication_repository_provision
          (id,preparation_id,repository_receipt_id,provider,target_repository_identity,
           target_repository_identity_digest,idempotency_key,attempt_number)
        VALUES (?,?,?,?,?,?,?,?)
        """,
        id,
        preparationId,
        repository.repositoryReceiptId(),
        provider,
        repository.repositoryIdentity(),
        repository.repositoryIdentityDigest(),
        key,
        attemptNumber);
    return new PublicationPreparationStore.RepositoryProvision(
        id,
        preparationId,
        repository.repositoryReceiptId(),
        provider,
        repository.repositoryIdentity(),
        "ADMITTED",
        null,
        attemptNumber);
  }

  @Transactional
  void markRepositoryProvisionDispatched(UUID provisionId) {
    int updated =
        jdbc.update(
            "UPDATE workspace_publication_repository_provision SET state='DISPATCHED',"
                + "dispatched_at=NOW() WHERE id=? AND state='ADMITTED'",
            provisionId);
    if (updated != 1) throw new IllegalStateException("REPOSITORY_PROVISION_DISPATCH_LOST");
  }

  @Transactional
  void settleRepositoryProvision(
      UUID provisionId,
      RunWorkspaceAuthorityService.Lease lease,
      boolean provisioned,
      String remoteUrl,
      String remoteIdentityDigest,
      String failureCode) {
    requirePublicationLease(lease);
    int updated =
        jdbc.update(
            """
            UPDATE workspace_publication_repository_provision provision
               SET state=?,remote_url=?,remote_identity_digest=?,failure_code=?,settled_at=NOW()
              FROM workspace_publication_preparation p, run_workspace_authority authority
             WHERE provision.id=? AND provision.state='DISPATCHED'
               AND p.id=provision.preparation_id
               AND authority.run_id=p.run_id AND authority.run_id=?
               AND authority.owner_token=? AND authority.authority_type='PUBLICATION'
               AND authority.lease_expires_at >= NOW()
            """,
            provisioned ? "PROVISIONED" : "INDETERMINATE",
            provisioned ? remoteUrl : null,
            provisioned ? remoteIdentityDigest : null,
            provisioned ? null : boundedCode(failureCode),
            provisionId,
            lease.runId(),
            lease.ownerToken());
    if (updated != 1) {
      throw new IllegalStateException("REPOSITORY_PROVISION_SETTLEMENT_FENCE_REJECTED");
    }
  }

  @Transactional
  PublicationPreparationStore.ExternalRecovery recoverStaleRepositoryProvisions(Instant cutoff) {
    int admittedFailed =
        jdbc.update(
            """
            UPDATE workspace_publication_repository_provision
               SET state='FAILED', failure_code='REPOSITORY_PROVISION_DISPATCH_NOT_STARTED',
                   settled_at=NOW()
             WHERE state='ADMITTED' AND admitted_at < ?
            """,
            Timestamp.from(cutoff));
    int dispatchedIndeterminate =
        jdbc.update(
            """
            UPDATE workspace_publication_repository_provision
               SET state='INDETERMINATE', failure_code='REPOSITORY_PROVISION_OUTCOME_LOST',
                   settled_at=NOW()
             WHERE state='DISPATCHED' AND updated_at < ?
            """,
            Timestamp.from(cutoff));
    return new PublicationPreparationStore.ExternalRecovery(
        admittedFailed, dispatchedIndeterminate);
  }

  List<PublicationPreparationStore.RepositoryProvision> findRepositoryProvision(
      UUID repositoryReceiptId) {
    return jdbc.query(
        """
        SELECT id,preparation_id,repository_receipt_id,provider,target_repository_identity,
               state,remote_url,attempt_number
          FROM workspace_publication_repository_provision
         WHERE repository_receipt_id=?
         ORDER BY attempt_number DESC
        """,
        (rs, row) ->
            new PublicationPreparationStore.RepositoryProvision(
                rs.getObject("id", UUID.class),
                rs.getObject("preparation_id", UUID.class),
                rs.getObject("repository_receipt_id", UUID.class),
                rs.getString("provider"),
                rs.getString("target_repository_identity"),
                rs.getString("state"),
                rs.getString("remote_url"),
                rs.getInt("attempt_number")),
        repositoryReceiptId);
  }

  private List<PublicationPreparationStore.PublicationAttempt> findPublicationAttempts(
      UUID repositoryReceiptId, String remoteIdentityDigest, String expectedRemoteSha) {
    return jdbc.query(
        """
        SELECT id, preparation_id, repository_receipt_id, publication_branch,
               prepared_commit_id, state, idempotency_key, attempt_number
          FROM workspace_publication_attempt
         WHERE repository_receipt_id=? AND remote_identity_digest=?
           AND expected_remote_sha IS NOT DISTINCT FROM ?
         ORDER BY attempt_number DESC
        """,
        (rs, row) ->
            new PublicationPreparationStore.PublicationAttempt(
                rs.getObject("id", UUID.class),
                rs.getObject("preparation_id", UUID.class),
                rs.getObject("repository_receipt_id", UUID.class),
                rs.getString("publication_branch"),
                rs.getString("prepared_commit_id"),
                rs.getString("state"),
                rs.getString("idempotency_key"),
                rs.getInt("attempt_number")),
        repositoryReceiptId,
        remoteIdentityDigest,
        expectedRemoteSha);
  }

  private static void requirePublicationLease(RunWorkspaceAuthorityService.Lease lease) {
    if (lease == null
        || lease.runId() == null
        || lease.ownerToken() == null
        || lease.authority() != RunWorkspaceAuthorityService.Authority.PUBLICATION) {
      throw new IllegalStateException("PUBLICATION_SETTLEMENT_AUTHORITY_MISSING");
    }
  }

  private static String boundedCode(String code) {
    if (code == null || code.isBlank()) return "PUBLICATION_PREPARATION_FAILED";
    String normalized = code.replaceAll("[^A-Z0-9_]", "_");
    return normalized.substring(0, Math.min(80, normalized.length()));
  }
}
