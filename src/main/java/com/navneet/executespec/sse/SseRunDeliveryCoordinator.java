package com.navneet.executespec.sse;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import lombok.extern.slf4j.Slf4j;

/** Bounded per-run ordering, terminal-state, and subscription/completion coordination. */
@Slf4j
final class SseRunDeliveryCoordinator {

  private static final int LOCK_STRIPES = 256;
  private static final int MAX_REORDER_FRAMES = 64;
  private static final int MAX_TRACKED_RUNS = 10_000;
  private static final Duration STATE_TTL = Duration.ofHours(24);
  private static final Duration REORDER_TIMEOUT = Duration.ofSeconds(1);

  @FunctionalInterface
  interface FrameSink {
    void accept(String runId, String json, long sequence);
  }

  record Admission<T>(T value, boolean terminalAtAdmission) {}

  private final SseReplayStore replayStore;
  private final FrameSink localSink;
  private final FrameSink redisPublisher;
  private final Consumer<String> completeSubscribers;
  private final Consumer<String> retireSubscribers;
  private final ReentrantLock[] locks = createLocks();
  private final Cache<String, OrderedRun> orderedRuns =
      Caffeine.newBuilder().maximumSize(MAX_TRACKED_RUNS).expireAfterAccess(STATE_TTL).build();
  private final Cache<String, Boolean> terminalRuns =
      Caffeine.newBuilder().maximumSize(MAX_TRACKED_RUNS).expireAfterWrite(STATE_TTL).build();

  SseRunDeliveryCoordinator(
      SseReplayStore replayStore,
      FrameSink localSink,
      FrameSink redisPublisher,
      Consumer<String> completeSubscribers,
      Consumer<String> retireSubscribers) {
    this.replayStore = replayStore;
    this.localSink = localSink;
    this.redisPublisher = redisPublisher;
    this.completeSubscribers = completeSubscribers;
    this.retireSubscribers = retireSubscribers;
  }

  void appendDeliverAndPublish(String runId, String json) {
    withRunLock(
        runId,
        () -> {
          long sequence = replayStore.append(runId, json);
          deliverSequencedLocked(runId, json, sequence, false);
          redisPublisher.accept(runId, json, sequence);
        });
  }

  <T> Admission<T> admit(String runId, Function<Boolean, T> installer) {
    ReentrantLock lock = lockFor(runId);
    lock.lock();
    try {
      boolean terminal =
          terminalRuns.getIfPresent(runId) != null || replayStore.containsTerminalEvent(runId);
      if (terminal) terminalRuns.put(runId, Boolean.TRUE);
      else initializeOrder(runId);
      return new Admission<>(installer.apply(terminal), terminal);
    } finally {
      lock.unlock();
    }
  }

  void complete(String runId) {
    withRunLock(
        runId,
        () -> {
          terminalRuns.put(runId, Boolean.TRUE);
          completeSubscribers.accept(runId);
          orderedRuns.invalidate(runId);
        });
  }

  void deliverRemote(String runId, String json, long sequence) {
    boolean terminal = replayStore.isTerminalEvent(json);
    withRunLock(
        runId,
        () -> {
          if (terminal) terminalRuns.put(runId, Boolean.TRUE);
          deliverSequencedLocked(runId, json, sequence, terminal);
        });
  }

  void maintain(long nowNanos) {
    for (Map.Entry<String, OrderedRun> entry : orderedRuns.asMap().entrySet()) {
      String runId = entry.getKey();
      withRunLock(
          runId,
          () -> {
            OrderedRun state = orderedRuns.getIfPresent(runId);
            if (state != null
                && state.firstPendingNanos > 0
                && nowNanos - state.firstPendingNanos >= REORDER_TIMEOUT.toNanos()) {
              log.warn(
                  "SSE sequence gap timed out for run {}; retiring subscribers for replay", runId);
              orderedRuns.invalidate(runId);
              retireSubscribers.accept(runId);
            }
          });
    }
  }

  void withRunLock(String runId, Runnable action) {
    ReentrantLock lock = lockFor(runId);
    lock.lock();
    try {
      action.run();
    } finally {
      lock.unlock();
    }
  }

  void forgetOrder(String runId) {
    orderedRuns.invalidate(runId);
  }

  private void initializeOrder(String runId) {
    if (orderedRuns.getIfPresent(runId) == null) {
      orderedRuns.put(runId, new OrderedRun(replayStore.currentSequence(runId)));
    }
  }

  private void deliverSequencedLocked(
      String runId, String json, long sequence, boolean closeAfterFrame) {
    OrderedRun state = orderedRuns.getIfPresent(runId);
    if (state == null || sequence <= 0) {
      localSink.accept(runId, json, sequence);
      if (closeAfterFrame) completeSubscribers.accept(runId);
      return;
    }
    if (sequence <= state.lastDeliveredSequence) return;
    state.pending.putIfAbsent(sequence, new Frame(json, sequence, closeAfterFrame));
    if (state.firstPendingNanos == 0) state.firstPendingNanos = System.nanoTime();
    if (state.pending.size() > MAX_REORDER_FRAMES) {
      log.warn("SSE sequence gap overflow for run {}; retiring subscribers for replay", runId);
      state.pending.clear();
      orderedRuns.invalidate(runId);
      retireSubscribers.accept(runId);
      return;
    }
    while (true) {
      Frame next = state.pending.remove(state.lastDeliveredSequence + 1);
      if (next == null) break;
      localSink.accept(runId, next.json(), next.sequence());
      state.lastDeliveredSequence = next.sequence();
      if (next.closeAfterFrame()) {
        completeSubscribers.accept(runId);
        state.pending.clear();
        orderedRuns.invalidate(runId);
        return;
      }
    }
    if (state.pending.isEmpty()) state.firstPendingNanos = 0;
  }

  private ReentrantLock lockFor(String runId) {
    return locks[Math.floorMod(runId.hashCode(), LOCK_STRIPES)];
  }

  private static ReentrantLock[] createLocks() {
    ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
    for (int index = 0; index < locks.length; index++) locks[index] = new ReentrantLock();
    return locks;
  }

  private record Frame(String json, long sequence, boolean closeAfterFrame) {}

  private static final class OrderedRun {
    private long lastDeliveredSequence;
    private long firstPendingNanos;
    private final TreeMap<Long, Frame> pending = new TreeMap<>();

    private OrderedRun(long lastDeliveredSequence) {
      this.lastDeliveredSequence = lastDeliveredSequence;
    }
  }
}
