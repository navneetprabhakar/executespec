package com.navneet.executespec.integration.scm;

import java.time.Duration;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Conservative recovery: stale external/preparation effects become indeterminate, never success.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PublicationRecoveryService {

  private static final Duration STALE_AFTER = Duration.ofMinutes(10);
  private final PublicationPreparationStore store;

  @Scheduled(fixedDelayString = "${execution.publication-recovery.interval-ms:60000}")
  public void recoverScheduled() {
    try {
      recover();
    } catch (RuntimeException failure) {
      log.error("Publication recovery sweep failed", failure);
    }
  }

  public RecoveryResult recover() {
    Instant cutoff = Instant.now().minus(STALE_AFTER);
    PublicationPreparationStore.ExternalRecovery attempts =
        store.recoverStalePublicationAttempts(cutoff);
    PublicationPreparationStore.ExternalRecovery provisions =
        store.recoverStaleRepositoryProvisions(cutoff);
    int preparations = store.recoverStalePreparations(cutoff);
    return new RecoveryResult(
        preparations,
        provisions.admittedFailed(),
        provisions.dispatchedIndeterminate(),
        attempts.admittedFailed(),
        attempts.dispatchedIndeterminate());
  }

  public record RecoveryResult(
      int preparationsIndeterminate,
      int repositoryProvisionsFailedBeforeDispatch,
      int repositoryProvisionsIndeterminate,
      int publicationAttemptsFailedBeforeDispatch,
      int publicationAttemptsIndeterminate) {}
}
