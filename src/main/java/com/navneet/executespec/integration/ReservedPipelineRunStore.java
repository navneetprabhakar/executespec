package com.navneet.executespec.integration;

import java.util.UUID;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;

import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Forces assigned reserved run identities through INSERT semantics rather than JPA merge. */
@Repository
@RequiredArgsConstructor
public class ReservedPipelineRunStore {

  private final EntityManager entityManager;
  private final ThreadLocal<String> reservedRunId = new ThreadLocal<>();

  public PipelineRun withReservedRunId(String runId, Supplier<PipelineRun> submission) {
    validateReservation(runId, submission);
    if (reservedRunId.get() != null) {
      throw new IllegalStateException("nested reserved run submission is not allowed");
    }
    reservedRunId.set(runId);
    try {
      return submission.get();
    } finally {
      reservedRunId.remove();
    }
  }

  public boolean hasReservation() {
    return reservedRunId.get() != null;
  }

  public String currentOrRandomRunId() {
    String current = reservedRunId.get();
    return current == null || current.isBlank() ? UUID.randomUUID().toString() : current;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PipelineRun insert(PipelineRun run) {
    entityManager.persist(run);
    entityManager.flush();
    return run;
  }

  static InitialRunPersistence persistInitial(
      ReservedPipelineRunStore reservedStore,
      PipelineRunRepository repository,
      PipelineRun candidate,
      boolean reservedIdentity) {
    try {
      PipelineRun created =
          reservedIdentity ? reservedStore.insert(candidate) : repository.save(candidate);
      return new InitialRunPersistence(created, true);
    } catch (DataIntegrityViolationException duplicateReservedIdentity) {
      if (!reservedIdentity) throw duplicateReservedIdentity;
      PipelineRun canonical =
          repository.findById(candidate.getRunId()).orElseThrow(() -> duplicateReservedIdentity);
      return new InitialRunPersistence(canonical, false);
    }
  }

  static String runIdForSubmission(String reservedRunId) {
    return reservedRunId == null || reservedRunId.isBlank()
        ? UUID.randomUUID().toString()
        : reservedRunId;
  }

  private static void validateReservation(String runId, Supplier<PipelineRun> submission) {
    if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId is required");
    try {
      UUID.fromString(runId);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("runId must be a UUID", invalid);
    }
    if (submission == null) throw new IllegalArgumentException("submission is required");
  }

  record InitialRunPersistence(PipelineRun run, boolean created) {}
}
