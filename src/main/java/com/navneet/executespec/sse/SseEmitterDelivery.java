package com.navneet.executespec.sse;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

/** One bounded, ordered, non-blocking delivery lane for one SSE subscriber. */
@Slf4j
final class SseEmitterDelivery {

  static final int MAX_PENDING_EVENTS = 64;

  record ReplayFrame(String json, long sequence) {}

  private final String runId;
  private final SseEmitter emitter;
  private final ExecutorService executor;
  private final Runnable onClosed;
  private final Runnable onTerminated;
  private final Runnable onBackpressure;
  private final Runnable requestEmitterClose;
  private final LongSupplier nanoTime;
  private final ArrayBlockingQueue<Frame> pending = new ArrayBlockingQueue<>(MAX_PENDING_EVENTS);
  private final ReentrantLock stateLock = new ReentrantLock();
  private final CountDownLatch bootstrapReady = new CountDownLatch(1);
  private final AtomicBoolean removalNotified = new AtomicBoolean();
  private final AtomicBoolean closeRequested = new AtomicBoolean();
  private final AtomicBoolean pressureRetirementNotified = new AtomicBoolean();
  private final AtomicBoolean terminated = new AtomicBoolean();

  private volatile boolean accepting = true;
  private volatile boolean aborted;
  private volatile boolean bootstrapConfigured;
  private volatile boolean sendConnectedComment;
  private volatile List<ReplayFrame> replayFrames = List.of();
  private volatile long sendingSinceNanos;
  private volatile long lastActivityNanos;
  private volatile Future<?> writer;

  SseEmitterDelivery(
      String runId,
      SseEmitter emitter,
      ExecutorService executor,
      Runnable onClosed,
      Runnable onTerminated,
      Runnable onBackpressure,
      Runnable requestEmitterClose) {
    this(
        runId,
        emitter,
        executor,
        onClosed,
        onTerminated,
        onBackpressure,
        requestEmitterClose,
        System::nanoTime);
  }

  SseEmitterDelivery(
      String runId,
      SseEmitter emitter,
      ExecutorService executor,
      Runnable onClosed,
      Runnable onTerminated,
      Runnable onBackpressure,
      Runnable requestEmitterClose,
      LongSupplier nanoTime) {
    this.runId = runId;
    this.emitter = emitter;
    this.executor = executor;
    this.onClosed = onClosed;
    this.onTerminated = onTerminated;
    this.onBackpressure = onBackpressure;
    this.requestEmitterClose = requestEmitterClose;
    this.nanoTime = nanoTime;
    this.lastActivityNanos = nanoTime.getAsLong();
  }

  void start() {
    Future<?> submitted = executor.submit(this::writeLoop);
    writer = submitted;
    if (!accepting) submitted.cancel(true);
  }

  /**
   * Release the writer only after the replay snapshot is known. Live events accepted between
   * registration and this call remain queued behind the replay and are de-duplicated by sequence.
   */
  void finishBootstrap(boolean connectedComment, List<ReplayFrame> replay) {
    stateLock.lock();
    try {
      if (bootstrapConfigured) return;
      bootstrapConfigured = true;
      sendConnectedComment = connectedComment;
      replayFrames = replay == null ? List.of() : List.copyOf(replay);
      lastActivityNanos = nanoTime.getAsLong();
      bootstrapReady.countDown();
    } finally {
      stateLock.unlock();
    }
  }

  /** Enqueue without waiting. A client that exhausts its private buffer is retired fail-closed. */
  boolean offer(String json, long sequence) {
    return offerFrame(Frame.event(json, sequence));
  }

  private boolean offerComment(String comment) {
    return offerFrame(Frame.comment(comment));
  }

  /** The lock makes "accepted" mean strictly before the completion sentinel. */
  private boolean offerFrame(Frame frame) {
    boolean overflow = false;
    stateLock.lock();
    try {
      if (!accepting) return false;
      if (pending.offer(frame)) {
        lastActivityNanos = nanoTime.getAsLong();
        return true;
      }
      accepting = false;
      aborted = true;
      pending.clear();
      overflow = true;
    } finally {
      stateLock.unlock();
    }
    if (overflow) {
      log.warn(
          "SSE client buffer full for run {} (max {}) — retiring slow subscriber",
          runId,
          MAX_PENDING_EVENTS);
      retireForBackpressure();
    }
    return false;
  }

  /** Seal the lane by placing completion after every frame accepted under the same state lock. */
  void completeAfterPending() {
    boolean overflow = false;
    stateLock.lock();
    try {
      if (!accepting) return;
      accepting = false;
      if (!pending.offer(Frame.terminal())) {
        pending.clear();
        overflow = true;
      }
    } finally {
      stateLock.unlock();
    }
    if (overflow) abort(true);
  }

  /**
   * Heartbeat healthy idle sockets and retire a writer whose current socket send exceeded its
   * deadline. This is invoked by one broadcaster maintenance task, never a run thread.
   */
  void maintain(long nowNanos, long heartbeatIntervalNanos, long sendStallNanos) {
    long sending = sendingSinceNanos;
    if (sending > 0 && nowNanos - sending >= sendStallNanos) {
      log.warn("SSE socket send stalled for run {} — retiring subscriber", runId);
      retireForBackpressure();
      return;
    }
    if (accepting && nowNanos - lastActivityNanos >= heartbeatIntervalNanos) {
      offerComment("ping");
    }
  }

  /** Stop the writer without waiting for its socket. The close attempt is externally bounded. */
  void abort(boolean completeEmitter) {
    stateLock.lock();
    try {
      accepting = false;
      aborted = true;
      pending.clear();
      bootstrapReady.countDown();
    } finally {
      stateLock.unlock();
    }
    Future<?> activeWriter = writer;
    if (activeWriter != null) activeWriter.cancel(true);
    if (completeEmitter) requestCloseOnce();
    notifyClosed();
  }

  private void retireForBackpressure() {
    if (!pressureRetirementNotified.compareAndSet(false, true)) return;
    onBackpressure.run();
    abort(true);
  }

  private void writeLoop() {
    try {
      bootstrapReady.await();
      if (Thread.currentThread().isInterrupted()) return;

      if (sendConnectedComment) send(Frame.comment("connected"));
      if (aborted) return;
      long replayHighWater = 0;
      for (ReplayFrame replay : replayFrames) {
        send(Frame.event(replay.json(), replay.sequence()));
        if (aborted) return;
        replayHighWater = Math.max(replayHighWater, replay.sequence());
      }
      replayFrames = List.of();

      while (true) {
        Frame frame = pending.take();
        if (frame.complete()) {
          requestCloseOnce();
          notifyClosed();
          return;
        }
        if (frame.sequence() > 0 && frame.sequence() <= replayHighWater) continue;
        send(frame);
        if (aborted) return;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Throwable disconnected) {
      log.debug("SSE client disconnected for run {} ({})", runId, disconnected.getMessage());
      abort(true);
    } finally {
      if (terminated.compareAndSet(false, true)) onTerminated.run();
    }
  }

  private void send(Frame frame) throws Exception {
    sendingSinceNanos = nanoTime.getAsLong();
    try {
      SseEmitter.SseEventBuilder event =
          frame.comment()
              ? SseEmitter.event().comment(frame.json())
              : SseEmitter.event().data(frame.json());
      if (frame.sequence() > 0) event.id(String.valueOf(frame.sequence()));
      emitter.send(event);
      lastActivityNanos = nanoTime.getAsLong();
    } finally {
      sendingSinceNanos = 0;
    }
  }

  private void notifyClosed() {
    if (removalNotified.compareAndSet(false, true)) onClosed.run();
  }

  private void requestCloseOnce() {
    if (closeRequested.compareAndSet(false, true)) requestEmitterClose.run();
  }

  private record Frame(String json, long sequence, boolean comment, boolean complete) {
    private static Frame event(String json, long sequence) {
      return new Frame(json, sequence, false, false);
    }

    private static Frame comment(String comment) {
      return new Frame(comment, 0, true, false);
    }

    private static Frame terminal() {
      return new Frame("", 0, false, true);
    }
  }
}
