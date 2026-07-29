package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;

import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

class RunOrchestratorExecutionHeartbeatTest {

  @Test
  void registrationFailsClosedWhenDurableOwnerClaimDoesNotMatch() {
    PipelineRunRepository repository = mock(PipelineRunRepository.class);
    Future<?> rejected = mock(Future.class);
    ActiveRunRegistry registry = new ActiveRunRegistry(repository);
    when(repository.claimExecutionOwner(
            org.mockito.ArgumentMatchers.eq("run-1"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Instant.class)))
        .thenReturn(0);

    assertThatThrownBy(() -> registry.put("run-1", rejected))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("durable execution ownership claim updated 0 rows");

    assertThat(registry.remove("run-1")).isNull();
  }

  @Test
  void registrationRollsBackExactFutureWhenDurableClaimThrows() {
    PipelineRunRepository repository = mock(PipelineRunRepository.class);
    Future<?> rejected = mock(Future.class);
    ActiveRunRegistry registry = new ActiveRunRegistry(repository);
    when(repository.claimExecutionOwner(
            org.mockito.ArgumentMatchers.eq("run-1"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Instant.class)))
        .thenThrow(new IllegalStateException("database unavailable"));

    assertThatThrownBy(() -> registry.put("run-1", rejected))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("database unavailable");

    assertThat(registry.remove("run-1")).isNull();
  }

  @Test
  void successfulReplacementKeepsNewFutureAndOldCleanupCannotRemoveIt() {
    PipelineRunRepository repository = mock(PipelineRunRepository.class);
    Future<?> previous = mock(Future.class);
    Future<?> replacement = mock(Future.class);
    ActiveRunRegistry registry = new ActiveRunRegistry(repository);
    when(repository.claimExecutionOwner(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Instant.class)))
        .thenReturn(1);

    registry.put("run-1", previous);
    registry.put("run-1", replacement);

    assertThat(registry.remove("run-1", previous)).isFalse();
    assertThat(registry.remove("run-1")).isSameAs(replacement);
  }

  @Test
  void failedReplacementRestoresPriorExternalHeartbeatRegistration() {
    PipelineRunRepository repository = mock(PipelineRunRepository.class);
    Future<?> previous = mock(Future.class);
    Future<?> rejected = mock(Future.class);
    ActiveRunRegistry registry = new ActiveRunRegistry(repository);
    registry.putExternallyHeartbeated("run-1", previous);
    when(repository.claimExecutionOwner(
            org.mockito.ArgumentMatchers.eq("run-1"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Instant.class)))
        .thenReturn(0);

    assertThatThrownBy(() -> registry.put("run-1", rejected))
        .isInstanceOf(IllegalStateException.class);

    assertThat(registry.adoptRuntimeHeartbeat("run-1", previous)).isTrue();
    assertThat(registry.remove("run-1")).isSameAs(previous);
  }

  @Test
  void lostDurableOwnerCompareRemovesAndInterruptsOnlyTheExactStaleFuture() {
    PipelineRunRepository repository = mock(PipelineRunRepository.class);
    Future<?> stale = mock(Future.class);
    Future<?> newer = mock(Future.class);
    ConcurrentMap<String, Future<?>> registrations = new ConcurrentHashMap<>();
    registrations.put("run-1", stale);
    when(repository.refreshExecutionHeartbeat("run-1", "runtime-a", Instant.EPOCH))
        .thenAnswer(
            ignored -> {
              registrations.replace("run-1", stale, newer);
              return 0;
            });

    ActiveRunRegistry.refreshExecutionHeartbeats(
        registrations, repository, "runtime-a", Instant.EPOCH);

    assertThat(registrations.get("run-1")).isSameAs(newer);
    verify(stale, org.mockito.Mockito.never()).cancel(true);
    verify(newer, org.mockito.Mockito.never()).cancel(true);
  }

  @Test
  void lostDurableOwnerInterruptsTheStillRegisteredFuture() {
    PipelineRunRepository repository = mock(PipelineRunRepository.class);
    Future<?> stale = mock(Future.class);
    ConcurrentMap<String, Future<?>> registrations = new ConcurrentHashMap<>();
    registrations.put("run-1", stale);
    when(repository.refreshExecutionHeartbeat("run-1", "runtime-a", Instant.EPOCH)).thenReturn(0);

    ActiveRunRegistry.refreshExecutionHeartbeats(
        registrations, repository, "runtime-a", Instant.EPOCH);

    assertThat(registrations).doesNotContainKey("run-1");
    verify(stale).cancel(true);
  }

  @Test
  void transientHeartbeatFailureRetainsFutureForRetry() {
    PipelineRunRepository repository = mock(PipelineRunRepository.class);
    Future<?> active = mock(Future.class);
    ConcurrentMap<String, Future<?>> registrations = new ConcurrentHashMap<>();
    registrations.put("run-1", active);
    doThrow(new IllegalStateException("database unavailable"))
        .when(repository)
        .refreshExecutionHeartbeat("run-1", "runtime-a", Instant.EPOCH);

    ActiveRunRegistry.refreshExecutionHeartbeats(
        registrations, repository, "runtime-a", Instant.EPOCH);

    assertThat(registrations.get("run-1")).isSameAs(active);
    verify(active, org.mockito.Mockito.never()).cancel(true);
  }

  @Test
  void protocolHeartbeatedQueuedFutureIsNotTestedAgainstRuntimeOwner() {
    PipelineRunRepository repository = mock(PipelineRunRepository.class);
    Future<?> queued = mock(Future.class);
    ConcurrentMap<String, Future<?>> registrations = new ConcurrentHashMap<>();
    ConcurrentMap<String, Future<?>> externalHeartbeats = new ConcurrentHashMap<>();
    registrations.put("run-1", queued);
    externalHeartbeats.put("run-1", queued);

    ActiveRunRegistry.refreshExecutionHeartbeats(
        registrations, externalHeartbeats, repository, "runtime-a", Instant.EPOCH);

    assertThat(registrations.get("run-1")).isSameAs(queued);
    verify(repository, org.mockito.Mockito.never())
        .refreshExecutionHeartbeat("run-1", "runtime-a", Instant.EPOCH);
    verify(queued, org.mockito.Mockito.never()).cancel(true);
  }
}
