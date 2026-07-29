package com.navneet.executespec.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;

import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Owns local pipeline futures and fences them against the durable execution-owner lease. */
@Slf4j
@Component
@RequiredArgsConstructor
class ActiveRunRegistry {

  private static final String EXECUTION_INSTANCE_ID = ExecutionHostIdentity.current();

  private final PipelineRunRepository runRepository;
  private final ConcurrentMap<String, Future<?>> activeRuns = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Future<?>> externallyHeartbeatedActiveRuns =
      new ConcurrentHashMap<>();

  String executionOwner() {
    return EXECUTION_INSTANCE_ID;
  }

  void put(String runId, Future<?> future) {
    Future<?> previous;
    Future<?> previousExternalHeartbeat;
    synchronized (activeRuns) {
      previousExternalHeartbeat = externallyHeartbeatedActiveRuns.remove(runId);
      previous = activeRuns.put(runId, future);
    }
    try {
      int claimed = runRepository.claimExecutionOwner(runId, EXECUTION_INSTANCE_ID, Instant.now());
      if (claimed != 1) {
        throw new IllegalStateException(
            "Cannot register run "
                + runId
                + ": durable execution ownership claim updated "
                + claimed
                + " rows");
      }
    } catch (RuntimeException claimFailure) {
      synchronized (activeRuns) {
        if (activeRuns.remove(runId, future)) {
          if (previous != null) activeRuns.put(runId, previous);
          if (previousExternalHeartbeat != null) {
            externallyHeartbeatedActiveRuns.put(runId, previousExternalHeartbeat);
          }
        }
      }
      throw claimFailure;
    }
  }

  void putExternallyHeartbeated(String runId, Future<?> future) {
    synchronized (activeRuns) {
      externallyHeartbeatedActiveRuns.put(runId, future);
      activeRuns.put(runId, future);
    }
  }

  boolean adoptRuntimeHeartbeat(String runId, Future<?> expectedFuture) {
    synchronized (activeRuns) {
      if (expectedFuture == null || activeRuns.get(runId) != expectedFuture) return false;
      return externallyHeartbeatedActiveRuns.remove(runId, expectedFuture);
    }
  }

  Future<?> remove(String runId) {
    synchronized (activeRuns) {
      Future<?> removed = activeRuns.remove(runId);
      if (removed != null) externallyHeartbeatedActiveRuns.remove(runId);
      return removed;
    }
  }

  boolean remove(String runId, Future<?> expectedFuture) {
    synchronized (activeRuns) {
      boolean removed = expectedFuture != null && activeRuns.remove(runId, expectedFuture);
      if (removed) externallyHeartbeatedActiveRuns.remove(runId, expectedFuture);
      return removed;
    }
  }

  @Scheduled(fixedRate = 30_000, initialDelay = 30_000)
  void refreshExecutionHeartbeats() {
    refreshExecutionHeartbeats(
        activeRuns,
        externallyHeartbeatedActiveRuns,
        runRepository,
        EXECUTION_INSTANCE_ID,
        Instant.now());
  }

  static void refreshExecutionHeartbeats(
      ConcurrentMap<String, Future<?>> registrations,
      PipelineRunRepository repository,
      String executionOwner,
      Instant heartbeat) {
    refreshExecutionHeartbeats(registrations, Map.of(), repository, executionOwner, heartbeat);
  }

  static void refreshExecutionHeartbeats(
      ConcurrentMap<String, Future<?>> registrations,
      Map<String, Future<?>> externallyHeartbeatedRegistrations,
      PipelineRunRepository repository,
      String executionOwner,
      Instant heartbeat) {
    // Snapshot registration and heartbeat authority together. A takeover that replaces one map
    // value while its prior heartbeat is in flight is handled on the next tick, never revisited
    // during the same weakly-consistent ConcurrentHashMap traversal.
    java.util.List<Map.Entry<String, Future<?>>> registrationSnapshot;
    Map<String, Future<?>> externalHeartbeatSnapshot;
    synchronized (registrations) {
      registrationSnapshot = new ArrayList<>(registrations.entrySet());
      externalHeartbeatSnapshot = Map.copyOf(externallyHeartbeatedRegistrations);
    }
    registrationSnapshot.forEach(
        registration -> {
          String runId = registration.getKey();
          Future<?> future = registration.getValue();
          if (externalHeartbeatSnapshot.get(runId) == future) return;
          if (future == null || future.isDone() || future.isCancelled()) {
            registrations.remove(runId, future);
            return;
          }
          try {
            if (repository.refreshExecutionHeartbeat(runId, executionOwner, heartbeat) == 0
                && registrations.remove(runId, future)) {
              future.cancel(true);
              log.warn(
                  "run={} lost durable execution ownership; cancelled stale local Future", runId);
            }
          } catch (RuntimeException transientFailure) {
            log.warn(
                "run={} execution heartbeat refresh failed; retaining local Future for retry: {}",
                runId,
                transientFailure.getMessage());
          }
        });
  }
}
