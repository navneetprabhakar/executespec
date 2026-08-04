package com.navneet.executespec.integration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService.Authority;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService.Lease;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Persists and recovers correction workspace transactions across JVM restarts.
 *
 * <p>The database row is durable before evidence invalidation or worker dispatch. Recovery restores
 * every repository tree and the exact previous validation row, then proves both restorations before
 * resolving the attempt. A failed proof remains {@code RECOVERY_FAILED}; the partial unique index
 * prevents any later correction for that run.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DurableCorrectionLifecycle {

  static final List<String> UNRESOLVED = List.of("PREPARED", "MUTATING", "RECOVERY_FAILED");

  private final CorrectionAttemptRepository attempts;
  private final CorrectionWorkspaceTransaction workspaces;
  private final ValidationReportRepository reports;
  private final AgentTaskRepository tasks;
  private final ObjectMapper objectMapper;
  private final RunWorkspaceAuthorityService workspaceAuthorities;

  public Prepared prepare(String runId, String taskId, Path workspaceRoot) {
    AgentTask task =
        tasks
            .findById(taskId)
            .orElseThrow(
                () -> new IllegalStateException("Correction task does not exist: " + taskId));
    return prepare(
        runId, taskId, workspaceRoot, AutomaticCorrectionLifecycle.snapshotTask(task), null, null);
  }

  public Prepared prepare(
      String runId,
      String taskId,
      Path workspaceRoot,
      AutomaticCorrectionLifecycle.TaskState taskSnapshot) {
    return prepare(runId, taskId, workspaceRoot, taskSnapshot, null, null);
  }

  public Prepared prepare(
      String runId,
      String taskId,
      Path workspaceRoot,
      AutomaticCorrectionLifecycle.TaskState taskSnapshot,
      String approvalId,
      String proposalDigest) {
    if ((approvalId == null) != (proposalDigest == null)
        || (proposalDigest != null && !proposalDigest.matches("[0-9a-f]{64}"))) {
      throw new IllegalArgumentException("CORRECTION_APPROVAL_BINDING_INVALID");
    }
    recoverRunOrThrow(runId);
    Lease authority = workspaceAuthorities.acquire(runId, Authority.CORRECTION);
    try {
      Path canonicalRoot = realPath(workspaceRoot.toString());
      CorrectionWorkspaceTransaction.Checkpoint checkpoint =
          canonicalCheckpoint(canonicalRoot, workspaces.begin(canonicalRoot));
      ValidationReport previous = reports.findByRunId(runId).orElse(null);
      ValidationSnapshotV1 evidenceSnapshot = snapshot(previous);
      String evidenceJson = write(evidenceSnapshot);
      Instant now = Instant.now();
      CorrectionAttempt attempt =
          CorrectionAttempt.builder()
              .id(UUID.randomUUID())
              .runId(runId)
              .taskId(taskId)
              .phase("PREPARED")
              .workspaceRoot(canonicalRoot.toString())
              .beforeGeneration(checkpoint.generation())
              .repositoriesJson(writeRepositories(checkpoint))
              .evidenceReportId(previous == null ? null : previous.getId())
              .evidenceStatus(previous == null ? "ABSENT" : previous.getStatus())
              .evidenceWorkspaceGeneration(
                  previous == null ? null : previous.getWorkspaceGeneration())
              .evidenceSnapshotJson(evidenceJson)
              .taskSnapshotJson(write(taskSnapshot))
              .snapshotSchemaVersion(1)
              .evidenceSnapshotDigest(sha256(evidenceJson))
              .approvalId(approvalId)
              .proposalDigest(proposalDigest)
              .leaseToken(authority.ownerToken())
              .createdAt(now)
              .updatedAt(now)
              .build();
      attempts.saveAndFlush(attempt);
      return new Prepared(attempt.getId(), checkpoint, authority);
    } catch (RuntimeException failure) {
      authority.close();
      throw failure;
    }
  }

  @Transactional
  public void markMutating(Prepared prepared) {
    try {
      prepared.authority().assertOwned();
      requireCas(
          attempts.transition(
              prepared.attemptId(), prepared.authority().ownerToken(), "PREPARED", "MUTATING"),
          "CORRECTION_MUTATING_CAS_FAILED");
    } catch (RuntimeException failure) {
      // A failed admission transition must not leave an in-process heartbeat protecting an
      // unresolved row forever. Releasing the lease makes the durable PREPARED row recoverable.
      prepared.authority().close();
      throw failure;
    }
  }

  @Transactional
  public void accept(Prepared prepared, String afterGeneration) {
    try {
      prepared.authority().assertOwned();
      CorrectionAttempt row = require(prepared.attemptId());
      String attested =
          workspaces.currentGeneration(readCheckpoint(row), prepared.authority()::assertOwned);
      if (!attested.equals(afterGeneration) || !attested.matches("[0-9a-f]{64}")) {
        throw new IllegalStateException("CORRECTION_ACCEPTED_GENERATION_UNATTESTED");
      }
      requireCas(
          attempts.accept(prepared.attemptId(), prepared.authority().ownerToken(), attested),
          "CORRECTION_ACCEPT_CAS_FAILED");
    } finally {
      // Success resolves the row; failure deliberately leaves it unresolved for recovery.
      prepared.authority().close();
    }
  }

  /**
   * Records a rollback only after both workspace and evidence restoration have already succeeded.
   */
  @Transactional
  public void rolledBack(Prepared prepared) {
    try {
      prepared.authority().assertOwned();
      CorrectionAttempt row = require(prepared.attemptId());
      assertWorkspaceRestored(row, prepared.checkpoint(), prepared.authority()::assertOwned);
      assertEvidenceRestored(row);
      restoreTask(row, false);
      requireCas(
          attempts.rolledBack(prepared.attemptId(), prepared.authority().ownerToken()),
          "CORRECTION_ROLLBACK_CAS_FAILED");
    } finally {
      // Any failed proof remains an unresolved durable fence, but must be available to recovery.
      prepared.authority().close();
    }
  }

  /** Admission fence used before any new correction can spend or mutate. */
  public void recoverRunOrThrow(String runId) {
    attempts
        .findFirstByRunIdAndPhaseInOrderByCreatedAtDesc(runId, UNRESOLVED)
        .ifPresent(
            row -> {
              Lease recovery =
                  workspaceAuthorities
                      .tryAcquire(runId, Authority.RECOVERY)
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "CORRECTION_RECOVERY_DEFERRED: live workspace owner"));
              recover(row, recovery);
              if (!"ROLLED_BACK".equals(require(row.getId()).getPhase())) {
                throw new IllegalStateException(
                    "CORRECTION_RECOVERY_UNPROVEN: prior correction could not be restored");
              }
            });
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recoverOnStartup() {
    recoverAll();
  }

  @Scheduled(
      fixedDelayString = "${execution.correction.recovery-scan-ms:60000}",
      initialDelayString = "${execution.correction.recovery-initial-delay-ms:60000}")
  void scheduledRecovery() {
    recoverAll();
  }

  void recoverAll() {
    for (CorrectionAttempt attempt : attempts.findByPhaseInOrderByCreatedAtAsc(UNRESOLVED)) {
      try {
        workspaceAuthorities
            .tryAcquire(attempt.getRunId(), Authority.RECOVERY)
            .ifPresent(lease -> recover(attempt, lease));
      } catch (RuntimeException unavailable) {
        log.error(
            "Correction recovery authority unavailable attempt={} run={}; continuing scan",
            attempt.getId(),
            attempt.getRunId(),
            unavailable);
      }
    }
  }

  void recover(CorrectionAttempt attempt, Lease recoveryAuthority) {
    try {
      requireCas(
          attempts.claimRecovery(attempt.getId(), recoveryAuthority.ownerToken()),
          "CORRECTION_RECOVERY_CLAIM_FAILED");
      attempt = require(attempt.getId());
      CorrectionWorkspaceTransaction.Checkpoint checkpoint = readCheckpoint(attempt);
      recoveryAuthority.assertOwned();
      if (recoverCompletedApprovalAsAccepted(attempt, checkpoint, recoveryAuthority)) {
        return;
      }
      workspaces.rollback(checkpoint, recoveryAuthority::assertOwned);
      recoveryAuthority.assertOwned();
      restoreEvidence(attempt);
      recoveryAuthority.assertOwned();
      restoreTask(attempt, true);
      recoveryAuthority.assertOwned();
      assertWorkspaceRestored(attempt, checkpoint, recoveryAuthority::assertOwned);
      recoveryAuthority.assertOwned();
      assertEvidenceRestored(attempt);
      recoveryAuthority.assertOwned();
      requireCas(
          attempts.rolledBack(attempt.getId(), recoveryAuthority.ownerToken()),
          "CORRECTION_RECOVERY_ROLLBACK_CAS_FAILED");
      log.warn(
          "Recovered incomplete correction attempt={} run={} task={}",
          attempt.getId(),
          attempt.getRunId(),
          attempt.getTaskId());
    } catch (RuntimeException failure) {
      int failed =
          attempts.recoveryFailed(
              attempt.getId(), recoveryAuthority.ownerToken(), boundedReason(failure));
      log.error(
          "Correction recovery failed closed attempt={} run={}",
          attempt.getId(),
          attempt.getRunId(),
          failure);
      if (failed != 1) {
        log.error("Correction recovery failure could not be persisted attempt={}", attempt.getId());
      }
    } finally {
      recoveryAuthority.close();
    }
  }

  /**
   * Closes the crash window after the immutable approval receipt reached COMPLETED but before the
   * correction-attempt terminal CAS. Only fresh, post-correction deterministic evidence bound to a
   * changed current generation can prove that the durable transaction should commit.
   */
  private boolean recoverCompletedApprovalAsAccepted(
      CorrectionAttempt attempt,
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      Lease recoveryAuthority) {
    if (attempt.getApprovalId() == null) return false;
    AgentTask task =
        tasks
            .findById(attempt.getTaskId())
            .orElseThrow(() -> new IllegalStateException("Correction task disappeared"));
    if (!java.util.Objects.equals(attempt.getApprovalId(), task.getContinuationApprovalId())
        || !java.util.Objects.equals(
            attempt.getProposalDigest(), task.getContinuationApprovalProposalDigest())) {
      throw new IllegalStateException("Correction approval receipt identity changed");
    }
    if (!"COMPLETED".equals(task.getContinuationApprovalStatus())) return false;

    recoveryAuthority.assertOwned();
    String currentGeneration =
        workspaces.currentGeneration(checkpoint, recoveryAuthority::assertOwned);
    ValidationReport currentReport = reports.findByRunId(attempt.getRunId()).orElse(null);
    if (attempt.getBeforeGeneration().equals(currentGeneration)
        || currentReport == null
        || !"COMPLETE".equalsIgnoreCase(currentReport.getStatus())
        || !currentGeneration.equals(currentReport.getWorkspaceGeneration())
        || currentReport.getPipelineResult()
            == com.navneet.executespec.report.entity.PipelineResult.FAILED
        || !Boolean.TRUE.equals(currentReport.getFinalBuildPassed())
        || !Boolean.TRUE.equals(currentReport.getTestExecutionVerified())
        || currentReport.getTestsTotal() == null
        || currentReport.getTestsTotal() <= 0
        || !com.navneet.executespec.report.TestExecutionTrust.successfulPopulation(
            currentReport.getTestsPassed(),
            currentReport.getTestsSkipped(),
            currentReport.getTestsTotal())) {
      throw new IllegalStateException(
          "Completed correction approval lacks fresh accepted-generation evidence");
    }
    recoveryAuthority.assertOwned();
    requireCas(
        attempts.acceptRecovered(
            attempt.getId(), recoveryAuthority.ownerToken(), currentGeneration),
        "CORRECTION_RECOVERY_ACCEPT_CAS_FAILED");
    log.warn(
        "Recovered completed correction commit attempt={} run={} generation={}",
        attempt.getId(),
        attempt.getRunId(),
        currentGeneration);
    return true;
  }

  private void restoreEvidence(CorrectionAttempt attempt) {
    ValidationSnapshotV1 snapshot = readValidationSnapshot(attempt);
    if (snapshot.reportJson() == null) {
      reports.findByRunId(attempt.getRunId()).ifPresent(reports::delete);
      reports.flush();
      return;
    }
    try {
      ValidationReport restored =
          objectMapper.readValue(snapshot.reportJson(), ValidationReport.class);
      restored.setLegacyShareToken(snapshot.legacyShareToken());
      restored.setShareTokenHash(snapshot.shareTokenHash());
      reports.saveAndFlush(restored);
    } catch (JsonProcessingException malformed) {
      throw new IllegalStateException("Correction evidence checkpoint is malformed", malformed);
    }
  }

  private void restoreTask(CorrectionAttempt attempt, boolean terminateApprovalReceipt) {
    try {
      AutomaticCorrectionLifecycle.TaskState snapshot =
          objectMapper.readValue(
              attempt.getTaskSnapshotJson(), AutomaticCorrectionLifecycle.TaskState.class);
      AgentTask current =
          tasks
              .findById(attempt.getTaskId())
              .orElseThrow(() -> new IllegalStateException("Correction task disappeared"));
      current.setStatus(snapshot.status());
      current.setStartedAt(snapshot.startedAt());
      current.setCompletedAt(snapshot.completedAt());
      current.setDurationMs(snapshot.durationMs());
      CorrectionStateSnapshots.restoreTaskEvidence(current, snapshot);
      // Spend counters and append-only action history intentionally stay on the current row. The
      // immutable approval receipt also survives; a crash-recovered attempt terminates it as
      // REJECTED rather than erasing or replaying the authority.
      if (terminateApprovalReceipt && attempt.getApprovalId() != null) {
        if (!java.util.Objects.equals(attempt.getApprovalId(), current.getContinuationApprovalId())
            || !java.util.Objects.equals(
                attempt.getProposalDigest(), current.getContinuationApprovalProposalDigest())) {
          throw new IllegalStateException("Correction approval receipt identity changed");
        }
      }
      if (terminateApprovalReceipt
          && attempt.getApprovalId() != null
          && ("CLAIMED".equals(current.getContinuationApprovalStatus())
              || "QUEUED".equals(current.getContinuationApprovalStatus()))) {
        requireCas(
            tasks.updateContinuationApprovalStatus(
                current.getTaskId(),
                attempt.getApprovalId(),
                attempt.getProposalDigest(),
                "REJECTED"),
            "CORRECTION_RECOVERY_APPROVAL_REJECTION_CAS_FAILED");
        current.setContinuationApprovalStatus("REJECTED");
        current.setContinuationApprovalUpdatedAt(Instant.now());
      } else if (terminateApprovalReceipt
          && attempt.getApprovalId() != null
          && !"REJECTED".equals(current.getContinuationApprovalStatus())) {
        throw new IllegalStateException(
            "Correction approval receipt is not recoverably terminal: "
                + current.getContinuationApprovalStatus());
      }
      tasks.saveAndFlush(current);
    } catch (JsonProcessingException malformed) {
      throw new IllegalStateException("Correction task checkpoint is malformed", malformed);
    }
  }

  private void assertWorkspaceRestored(
      CorrectionAttempt attempt, CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    assertWorkspaceRestored(attempt, checkpoint, () -> {});
  }

  private void assertWorkspaceRestored(
      CorrectionAttempt attempt,
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      Runnable authorityFence) {
    String generation = workspaces.currentGeneration(checkpoint, authorityFence);
    if (!attempt.getBeforeGeneration().equals(generation)) {
      throw new IllegalStateException("Correction workspace rollback generation does not match");
    }
  }

  private void assertEvidenceRestored(CorrectionAttempt attempt) {
    ValidationSnapshotV1 expected = readValidationSnapshot(attempt);
    ValidationReport current = reports.findByRunId(attempt.getRunId()).orElse(null);
    if (expected.reportJson() == null) {
      if (current != null)
        throw new IllegalStateException("Correction evidence rollback not absent");
      return;
    }
    if (current == null
        || !attempt.getEvidenceSnapshotDigest().equals(sha256(write(snapshot(current))))) {
      throw new IllegalStateException("Correction evidence rollback identity does not match");
    }
  }

  /** Read-only publication/deployment admission fence; this method never attempts recovery. */
  public void assertNoUnresolvedAttempt(String runId) {
    attempts
        .findFirstByRunIdAndPhaseInOrderByCreatedAtDesc(runId, UNRESOLVED)
        .ifPresent(
            row -> {
              throw new IllegalStateException(
                  "CORRECTION_ATTEMPT_UNRESOLVED: phase=" + row.getPhase());
            });
  }

  private CorrectionWorkspaceTransaction.Checkpoint readCheckpoint(CorrectionAttempt attempt) {
    try {
      RepositoryRef[] refs =
          objectMapper.readValue(attempt.getRepositoriesJson(), RepositoryRef[].class);
      Path root = realPath(attempt.getWorkspaceRoot());
      return new CorrectionWorkspaceTransaction.Checkpoint(
          root,
          java.util.Arrays.stream(refs)
              .map(
                  ref -> {
                    Path repository = realPath(ref.repository());
                    if (!repository.startsWith(root)) {
                      throw new IllegalStateException(
                          "Correction repository checkpoint escapes workspace root");
                    }
                    return new CorrectionWorkspaceTransaction.RepositoryCheckpoint(
                        repository, ref.head(), ref.tree());
                  })
              .toList());
    } catch (Exception malformed) {
      throw new IllegalStateException("Correction workspace checkpoint is malformed", malformed);
    }
  }

  private String writeRepositories(CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    return write(
        checkpoint.repositories().stream()
            .map(r -> new RepositoryRef(r.repository().toString(), r.head(), r.tree()))
            .toList());
  }

  private static CorrectionWorkspaceTransaction.Checkpoint canonicalCheckpoint(
      Path canonicalRoot, CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    return new CorrectionWorkspaceTransaction.Checkpoint(
        canonicalRoot,
        checkpoint.repositories().stream()
            .map(
                repository -> {
                  Path canonicalRepository = realPath(repository.repository().toString());
                  if (!canonicalRepository.startsWith(canonicalRoot)) {
                    throw new IllegalStateException(
                        "Correction repository checkpoint escapes workspace root");
                  }
                  return new CorrectionWorkspaceTransaction.RepositoryCheckpoint(
                      canonicalRepository, repository.head(), repository.tree());
                })
            .toList());
  }

  private static Path realPath(String path) {
    try {
      return Path.of(path).toRealPath();
    } catch (java.io.IOException unavailable) {
      throw new IllegalStateException("Correction checkpoint path is unavailable", unavailable);
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Could not serialize correction checkpoint", failure);
    }
  }

  private ValidationSnapshotV1 snapshot(ValidationReport report) {
    if (report == null) return new ValidationSnapshotV1(1, null, null, null);
    ValidationReport copy = new ValidationReport();
    org.springframework.beans.BeanUtils.copyProperties(report, copy);
    // updated_at is persistence bookkeeping and changes on the restoring UPDATE. Every semantic
    // evidence/share field remains covered by the digest.
    copy.setUpdatedAt(null);
    return new ValidationSnapshotV1(
        1, write(copy), report.getLegacyShareToken(), report.getShareTokenHash());
  }

  private ValidationSnapshotV1 readValidationSnapshot(CorrectionAttempt attempt) {
    if (!Integer.valueOf(1).equals(attempt.getSnapshotSchemaVersion())) {
      throw new IllegalStateException("Unsupported correction snapshot schema");
    }
    try {
      ValidationSnapshotV1 snapshot =
          objectMapper.readValue(attempt.getEvidenceSnapshotJson(), ValidationSnapshotV1.class);
      if (snapshot.schemaVersion() != 1) {
        throw new IllegalStateException("Unsupported correction validation snapshot schema");
      }
      // evidence_snapshot_json is PostgreSQL jsonb. jsonb preserves every typed value (including
      // the byte-exact reportJson string) but is explicitly free to reorder the outer object's
      // keys. V369 digested the Java record serialization before insertion, so hashing the raw
      // value read back from jsonb rejects an unchanged checkpoint whenever PostgreSQL returns a
      // different key order. Re-serialize the typed V1 envelope in its declared record order: this
      // reproduces the bytes V369 originally digested while still binding every evidence/share
      // value, and remains compatible with already-persisted unresolved attempts.
      if (!attempt.getEvidenceSnapshotDigest().equals(sha256(write(snapshot)))) {
        throw new IllegalStateException("Correction evidence snapshot digest mismatch");
      }
      return snapshot;
    } catch (JsonProcessingException malformed) {
      throw new IllegalStateException("Correction validation snapshot is malformed", malformed);
    }
  }

  private static String sha256(String value) {
    return org.apache.commons.codec.digest.DigestUtils.sha256Hex(value);
  }

  private static void requireCas(int updated, String code) {
    if (updated != 1) throw new IllegalStateException(code);
  }

  private static String boundedReason(RuntimeException failure) {
    String reason = failure.getMessage();
    if (reason == null) return failure.getClass().getSimpleName();
    return reason.substring(0, Math.min(reason.length(), 2_000));
  }

  private CorrectionAttempt require(UUID id) {
    return attempts
        .findById(id)
        .orElseThrow(() -> new IllegalStateException("Correction attempt disappeared: " + id));
  }

  public record Prepared(
      UUID attemptId, CorrectionWorkspaceTransaction.Checkpoint checkpoint, Lease authority) {}

  record RepositoryRef(String repository, String head, String tree) {}

  record ValidationSnapshotV1(
      int schemaVersion, String reportJson, String legacyShareToken, String shareTokenHash) {}
}
