package com.navneet.executespec.specchat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.micrometer.context.ContextSnapshotFactory;

/**
 * Bounded off-request executor for the compatibility {@code POST /api/spec/chat} turn.
 *
 * <p>The turn runs a multi-round model/tool loop with outbound provider and public-web calls. It
 * used to run inline on the Tomcat request thread, so every in-flight chat pinned one HTTP worker
 * for the whole provider duration; a handful of slow turns could starve auth, billing and
 * run-control endpoints. This mirrors the pattern the agent-session stream and project chat's
 * {@code applyExecutor} already use: hand the loop to a virtual thread with propagated
 * trace/request context and return the emitter immediately.
 *
 * <p>Unbounded virtual threads would move the exhaustion from the web tier to the provider bill, so
 * admission is explicitly capped — globally and per user — and a rejected turn is a plain {@code
 * false} the caller surfaces as HTTP 429 rather than a queue that grows without limit.
 */
@Component
public class SpecChatTurnDispatcher {

  private final Semaphore globalPermits;
  private final int perUserLimit;
  private final Map<String, AtomicInteger> inFlightByUser = new ConcurrentHashMap<>();

  public SpecChatTurnDispatcher(
      @Value("${spec.chat.max-concurrent-turns:8}") int maxConcurrentTurns,
      @Value("${spec.chat.max-concurrent-turns-per-user:2}") int maxConcurrentTurnsPerUser) {
    this.globalPermits = new Semaphore(Math.max(1, maxConcurrentTurns));
    this.perUserLimit = Math.max(1, maxConcurrentTurnsPerUser);
  }

  /**
   * Admit and start one turn.
   *
   * @return {@code false} when the global or per-user ceiling is already reached; the task is not
   *     started and no thread is created.
   */
  public boolean submit(String userId, String turnLabel, Runnable turn) {
    String key = userId == null ? "anonymous" : userId;
    AtomicInteger inFlight = inFlightByUser.computeIfAbsent(key, k -> new AtomicInteger());
    if (!claimUserSlot(inFlight)) {
      return false;
    }
    if (!globalPermits.tryAcquire()) {
      inFlight.decrementAndGet();
      return false;
    }
    var snapshot = ContextSnapshotFactory.builder().build().captureAll();
    try {
      Thread.ofVirtual()
          .name("spec-chat-" + turnLabel)
          .start(
              snapshot.wrap(
                  () -> {
                    try {
                      turn.run();
                    } finally {
                      globalPermits.release();
                      inFlight.decrementAndGet();
                    }
                  }));
      return true;
    } catch (RuntimeException startFailure) {
      globalPermits.release();
      inFlight.decrementAndGet();
      throw startFailure;
    }
  }

  private boolean claimUserSlot(AtomicInteger inFlight) {
    while (true) {
      int current = inFlight.get();
      if (current >= perUserLimit) return false;
      if (inFlight.compareAndSet(current, current + 1)) return true;
    }
  }
}
