package com.navneet.executespec.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the POLICY layer of {@link RunIdempotencyService}: how a lost claim is classified, and that
 * a caller which does not own a claim can never publish a terminal state.
 *
 * <p><b>Scope honesty:</b> the store is mocked here, so these tests prove none of the distributed
 * guarantees. The atomic single-winner, conflict, fencing and dead-claim-recovery behaviour is
 * proven against real PostgreSQL in {@code RunSubmissionIdempotencyConcurrencyIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class RunIdempotencyServiceTest {

  private static final String HASH = "f".repeat(64);

  @Mock RunSubmissionClaimStore store;

  private RunIdempotencyService service() {
    return new RunIdempotencyService(store, 120_000L, 0L, 86_400_000L);
  }

  @Test
  void aBlankActorOrKeyDisablesDedupeWithoutTouchingTheStore() {
    RunIdempotencyService svc = service();
    assertThat(svc.claim(null, "k", HASH).status()).isEqualTo(RunIdempotencyStatus.DISABLED);
    assertThat(svc.claim("u", "  ", HASH).status()).isEqualTo(RunIdempotencyStatus.DISABLED);
    verifyNoInteractions(store);
  }

  @Test
  void aWonClaimCarriesItsOwnerAndFencingToken() {
    when(store.tryClaim(eq("u"), eq("k"), eq(HASH), anyString(), anyLong(), anyLong()))
        .thenReturn(Optional.of(7L));
    var claim = service().claim("u", "k", HASH);
    assertThat(claim.status()).isEqualTo(RunIdempotencyStatus.CLAIMED);
    assertThat(claim.owned()).isTrue();
    assertThat(claim.fencingToken()).isEqualTo(7L);
    assertThat(claim.owner()).isNotBlank();
  }

  @Test
  void aCompletedWinnerResolvesToTheWinnersRun() {
    when(store.tryClaim(anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong()))
        .thenReturn(Optional.empty());
    when(store.find("u", "k")).thenReturn(Optional.of(record(HASH, "COMPLETED", "run-1", null)));
    var claim = service().claim("u", "k", HASH);
    assertThat(claim.status()).isEqualTo(RunIdempotencyStatus.DUPLICATE);
    assertThat(claim.runId()).isEqualTo("run-1");
  }

  @Test
  void aDifferentRequestBodyIsAConflictAndNeverReturnsTheFirstRun() {
    when(store.tryClaim(anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong()))
        .thenReturn(Optional.empty());
    when(store.find("u", "k"))
        .thenReturn(Optional.of(record("a".repeat(64), "COMPLETED", "run-1", null)));
    var claim = service().claim("u", "k", HASH);
    assertThat(claim.status()).isEqualTo(RunIdempotencyStatus.CONFLICT);
    assertThat(claim.runId()).isNull();
  }

  @Test
  void aPermanentlyFailedKeyReportsItsReason() {
    when(store.tryClaim(anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong()))
        .thenReturn(Optional.empty());
    when(store.find("u", "k"))
        .thenReturn(Optional.of(record(HASH, "FAILED_FINAL", null, "402 exhausted")));
    var claim = service().claim("u", "k", HASH);
    assertThat(claim.status()).isEqualTo(RunIdempotencyStatus.FAILED_FINAL);
    assertThat(claim.detail()).isEqualTo("402 exhausted");
  }

  @Test
  void aLiveForeignClaimTimesOutAsInProgressRatherThanCreatingASecondRun() {
    when(store.tryClaim(anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong()))
        .thenReturn(Optional.empty());
    when(store.find("u", "k")).thenReturn(Optional.of(record(HASH, "CLAIMED", null, null)));
    // waitMillis is 0 in these tests, so the bounded wait expires on the first pass.
    assertThat(service().claim("u", "k", HASH).status())
        .isEqualTo(RunIdempotencyStatus.IN_PROGRESS);
  }

  @Test
  void aClaimRequiresAFingerprint() {
    assertThatThrownBy(() -> service().claim("u", "k", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fingerprint");
  }

  @Test
  void anUnownedClaimCanNeverPublishATerminalState() {
    RunIdempotencyService svc = service();
    var notOurs =
        RunIdempotencyClaim.resolved(RunIdempotencyStatus.DUPLICATE, "u", "k", "run-1", null);
    svc.complete(notOurs, "run-2");
    svc.releaseForRetry(notOurs, "boom");
    svc.failFinal(notOurs, "boom");
    verify(store, never()).complete(anyString(), anyString(), anyString(), anyLong(), anyString());
    verify(store, never())
        .fail(anyString(), anyString(), anyString(), anyLong(), anyString(), any());
  }

  @Test
  void completingWithoutARunIdIsRejected() {
    service().complete(RunIdempotencyClaim.claimed("u", "k", "owner", 1L), " ");
    verify(store, never()).complete(anyString(), anyString(), anyString(), anyLong(), anyString());
  }

  @Test
  void fingerprintIsStableAndSensitiveToEveryField() {
    assertThat(RunRequestFingerprint.of(List.of("spec", "proj")))
        .isEqualTo(RunRequestFingerprint.of(List.of("spec", "proj")))
        .hasSize(64);
    assertThat(RunRequestFingerprint.of(List.of("spec", "proj")))
        .isNotEqualTo(RunRequestFingerprint.of(List.of("spec", "other")));
    // Field boundaries are length-prefixed, so a shifted split cannot collide.
    assertThat(RunRequestFingerprint.of(List.of("ab", "c")))
        .isNotEqualTo(RunRequestFingerprint.of(List.of("a", "bc")));
  }

  private static RunSubmissionClaimStore.Record record(
      String hash, String state, String runId, String reason) {
    return new RunSubmissionClaimStore.Record("u", "k", hash, state, runId, null, 1L, null, reason);
  }
}
