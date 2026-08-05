package com.navneet.executespec.sse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.benchmark.BenchmarkTrajectoryStore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * SSE broadcaster with Redis pub/sub for horizontal scalability.
 *
 * <h3>Local delivery</h3>
 *
 * Emitters are in-memory and instance-local. The bounds from Issue #15 apply: max {@link
 * #MAX_EMITTERS_PER_RUN} per run, {@link #MAX_TOTAL_EMITTERS} globally. Each emitter owns one
 * ordered replay/live lane drained by a dedicated virtual-thread writer, so Tomcat socket
 * backpressure never blocks a pipeline or Redis-listener thread. Heartbeat maintenance retires
 * quiet half-open sockets, and retired writer tasks keep consuming admission capacity until they
 * really exit. Reconnect replay recovers from the last acknowledged event id.
 *
 * <p>On every {@link #emit}, events are published to Redis channel {@code sse:{runId}}. Every
 * instance subscribes to {@code sse:*} and delivers incoming messages to its locally-connected
 * emitters. A self-origin marker ({@code "from":instanceId}) is embedded in each Redis message to
 * avoid echoing back to the publishing instance.
 *
 * <p>Events are appended to a Redis List {@code sse:replay:{runId}} (capped at {@link
 * #MAX_REPLAY_EVENTS}, 24h TTL). Clients reconnecting with {@code Last-Event-ID: N} receive all
 * events from sequence N+1 onward. Sequence numbers are independent of the bounded list index, so
 * trimming old entries never reuses an SSE id.
 */
@Component
@Slf4j
public class SseBroadcaster {

  static final int MAX_EMITTERS_PER_RUN = 15;

  static final int MAX_TOTAL_EMITTERS = 500;

  /** Maximum events to retain per run for replay. */
  static final int MAX_REPLAY_EVENTS = 500;

  static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
  static final Duration SEND_STALL_TIMEOUT = Duration.ofSeconds(30);
  static final int MAX_CONCURRENT_CLOSE_ATTEMPTS = 32;
  private static final String CHANNEL_PREFIX = "sse:";

  /**
   * Unique ID for this JVM instance — used to suppress echo from Redis pub/sub.
   *
   * <p>RR-424 — the previous identity was {@code loopbackHostName + "-" + pid}. Containers commonly
   * report the same loopback hostname and run the JVM as PID 1, so two replicas could compute the
   * SAME id and each classify the other's pub/sub events as a self-echo — subscribers on the
   * non-origin replica then received no live run events. A per-boot random suffix makes the id
   * deployment-unique regardless of host/PID collision; it only needs to be stable for the lifetime
   * of this process and distinct from every peer's.
   */
  private static final String INSTANCE_ID =
      java.net.InetAddress.getLoopbackAddress().getHostName()
          + "-"
          + ProcessHandle.current().pid()
          + "-"
          + java.util.UUID.randomUUID();

  private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
  private final Map<SseEmitter, SseEmitterDelivery> localDeliveries = new ConcurrentHashMap<>();
  private final Object subscriptionAdmissionLock = new Object();
  private final AtomicInteger totalEmitterCount = new AtomicInteger(0);
  private final AtomicInteger deliveryTaskCount = new AtomicInteger(0);
  private final Map<String, AtomicInteger> deliveryTaskCountByRun = new ConcurrentHashMap<>();
  private final AtomicInteger outstandingCloseAttempts = new AtomicInteger(0);
  private final AtomicLong backpressureEvictionCount = new AtomicLong();
  private final Semaphore closeAttemptPermits = new Semaphore(MAX_CONCURRENT_CLOSE_ATTEMPTS);
  private final ExecutorService localDeliveryExecutor =
      Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("executespec-sse-delivery-", 0).factory());
  private final ScheduledExecutorService maintenanceExecutor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofVirtual().name("executespec-sse-maintenance", 0).factory());

  private final ObjectMapper mapper;
  private final ApplicationEventPublisher eventPublisher;
  private final MeterRegistry meterRegistry;
  private final StringRedisTemplate redis;
  private final RedisMessageListenerContainer listenerContainer;
  private final BenchmarkTrajectoryStore benchmarkTrajectoryStore;
  private final SseReplayStore replayStore;
  private final SseRunDeliveryCoordinator runDeliveryCoordinator;

  @Autowired
  public SseBroadcaster(
      ObjectMapper mapper,
      ApplicationEventPublisher eventPublisher,
      MeterRegistry meterRegistry,
      StringRedisTemplate redis,
      RedisMessageListenerContainer listenerContainer,
      ObjectProvider<BenchmarkTrajectoryStore> benchmarkTrajectoryStore) {
    this.mapper = mapper;
    this.eventPublisher = eventPublisher;
    this.meterRegistry = meterRegistry;
    this.redis = redis;
    this.listenerContainer = listenerContainer;
    this.benchmarkTrajectoryStore = benchmarkTrajectoryStore.getIfAvailable();
    this.replayStore = new SseReplayStore(mapper, redis);
    this.runDeliveryCoordinator = createRunDeliveryCoordinator();
  }

  /** Provider-free constructor retained for narrow unit tests outside the benchmark profile. */
  SseBroadcaster(
      ObjectMapper mapper,
      ApplicationEventPublisher eventPublisher,
      MeterRegistry meterRegistry,
      StringRedisTemplate redis,
      RedisMessageListenerContainer listenerContainer) {
    this.mapper = mapper;
    this.eventPublisher = eventPublisher;
    this.meterRegistry = meterRegistry;
    this.redis = redis;
    this.listenerContainer = listenerContainer;
    this.benchmarkTrajectoryStore = null;
    this.replayStore = new SseReplayStore(mapper, redis);
    this.runDeliveryCoordinator = createRunDeliveryCoordinator();
  }

  private SseRunDeliveryCoordinator createRunDeliveryCoordinator() {
    return new SseRunDeliveryCoordinator(
        replayStore,
        this::deliverLocal,
        this::publishToRedis,
        this::completeLiveSubscribersLocked,
        this::retireLiveSubscribersLocked);
  }

  @PostConstruct
  void init() {
    // Gauge for live emitter count
    Gauge.builder("executespec.sse.emitters.active", totalEmitterCount, AtomicInteger::get)
        .description("Total live SSE emitter connections across all runs")
        .register(meterRegistry);
    Gauge.builder(
            "executespec.sse.emitters.backpressure.evictions",
            backpressureEvictionCount,
            AtomicLong::get)
        .description("Slow SSE subscribers retired after exhausting their bounded private queue")
        .register(meterRegistry);
    Gauge.builder("executespec.sse.delivery.tasks", deliveryTaskCount, AtomicInteger::get)
        .description("SSE writer tasks, including retired writers that have not terminated")
        .register(meterRegistry);
    Gauge.builder("executespec.sse.close.attempts", outstandingCloseAttempts, AtomicInteger::get)
        .description("Bounded asynchronous SSE response-close attempts currently running")
        .register(meterRegistry);

    maintenanceExecutor.scheduleAtFixedRate(
        this::maintainLocalDeliveries,
        HEARTBEAT_INTERVAL.toMillis(),
        HEARTBEAT_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);

    // Subscribe to all sse:* channels (pattern subscription)
    listenerContainer.addMessageListener(
        new SseRedisListener(), new PatternTopic(CHANNEL_PREFIX + "*"));
    log.info("[SseBroadcaster] Subscribed to Redis pattern sse:* (instanceId={})", INSTANCE_ID);
  }

  /**
   * Complete every open async response before Tomcat begins graceful shutdown.
   *
   * <p>Run-detail pages intentionally keep their SSE connection open while a run is waiting at the
   * plan gate. Without an explicit lifecycle close, one such idle browser tab keeps Tomcat's active
   * request count non-zero for the full graceful-shutdown timeout.
   */
  @PreDestroy
  @EventListener(ContextClosedEvent.class)
  void shutdown() {
    List<SseEmitterDelivery> open = localDeliveries.values().stream().distinct().toList();
    emitters.clear();
    localDeliveries.clear();
    totalEmitterCount.set(0);
    open.forEach(delivery -> delivery.abort(true));
    localDeliveryExecutor.shutdownNow();
    maintenanceExecutor.shutdownNow();
    if (!open.isEmpty()) {
      log.info("[SseBroadcaster] Completed {} emitter(s) for shutdown", open.size());
    }
  }

  int activeEmitterCount() {
    return totalEmitterCount.get();
  }

  /**
   * Create a new SSE emitter for a run.
   *
   * @param runId the pipeline run to subscribe to
   * @param lastEventId optional Last-Event-ID for replay (may be null)
   * @return the SSE emitter (Spring will stream it to the client)
   */
  public SseEmitter subscribe(String runId, String lastEventId) {
    return subscribeWithEmitter(runId, lastEventId, new SseEmitter(0L));
  }

  /** Package-visible seam for deterministic replay/socket-failure tests. */
  SseEmitter subscribeWithEmitter(String runId, String lastEventId, SseEmitter emitter) {
    SubscriptionAdmission admission = admitLocalEmitter(runId, emitter);
    SseEmitterDelivery delivery = admission.delivery();

    emitter.onCompletion(() -> remove(runId, emitter));
    emitter.onTimeout(() -> remove(runId, emitter));
    emitter.onError(e -> remove(runId, emitter));

    // Registration intentionally precedes the replay read: live frames are buffered while the
    // snapshot is loaded, then one writer emits connected -> replay -> de-duplicated live frames.
    // Redis I/O may delay this request, but no socket write can block it.
    String replayCursor =
        admission.terminalAtAdmission() && (lastEventId == null || lastEventId.isBlank())
            ? "0"
            : lastEventId;
    List<SseEmitterDelivery.ReplayFrame> replay =
        replayCursor == null || replayCursor.isBlank()
            ? List.of()
            : loadMissedEvents(runId, replayCursor);
    delivery.finishBootstrap(true, replay);

    return emitter;
  }

  public SseEmitter subscribe(String runId) {
    return subscribe(runId, null);
  }

  /**
   * Emit an event to all subscribers of the given run.
   *
   * <p>Delivers to local emitters, persists to Redis replay log, and publishes to Redis pub/sub for
   * cross-instance delivery. Also publishes as a Spring event for local listeners (Slack, etc.).
   */
  public void emit(String runId, RunEvent event) {
    String json = serialize(event);
    if (json == null) return;

    appendBenchmarkTrajectory(runId, event);

    appendDeliverAndPublish(runId, json);

    // Spring event for local listeners (Slack notifications, etc.)
    try {
      eventPublisher.publishEvent(event);
    } catch (Exception e) {
      log.debug("Failed to publish RunEvent for external listeners: {}", e.getMessage());
    }
  }

  /**
   * Emit an SSE event WITHOUT republishing as a Spring event. Used by RunDetailSseService to avoid
   * infinite loop (it listens for RunEvents).
   */
  public void emitDirect(String runId, RunEvent event) {
    String json = serialize(event);
    if (json == null) return;
    appendBenchmarkTrajectory(runId, event);
    appendDeliverAndPublish(runId, json);
  }

  /**
   * Keep sequence allocation, durable replay append, local admission, and Redis publication in one
   * per-run order. Fixed lock striping bounds lock memory for arbitrary run ids. Spring application
   * listeners intentionally remain outside this section because their execution is unconstrained.
   */
  private void appendDeliverAndPublish(String runId, String json) {
    runDeliveryCoordinator.appendDeliverAndPublish(runId, json);
  }

  private void appendBenchmarkTrajectory(String runId, RunEvent event) {
    if (benchmarkTrajectoryStore == null) return;
    try {
      benchmarkTrajectoryStore.append(runId, event);
    } catch (RuntimeException e) {
      // Optional benchmark evidence must never break the product event path. Benchmark submission
      // still fails closed because no complete export will be available.
      log.error(
          "Benchmark trajectory eligibility/append failed for run {}: {}",
          runId,
          e.getClass().getName());
    }
  }

  /** Explicitly register a benchmark run before its first event; a DB fallback covers restarts. */
  public void registerBenchmarkTrajectory(String runId) {
    if (benchmarkTrajectoryStore != null) benchmarkTrajectoryStore.registerBenchmarkRun(runId);
  }

  /** Complete the SSE stream for a run (run finished or validation complete). */
  public void complete(String runId) {
    runDeliveryCoordinator.complete(runId);
  }

  private void completeLiveSubscribersLocked(String runId) {
    List<SseEmitter> clients = emitters.remove(runId);
    if (clients == null) return;
    totalEmitterCount.addAndGet(-clients.size());
    for (SseEmitter emitter : clients) {
      // Keep the delivery registered until the terminal frame drains so the maintenance watchdog
      // can still retire a socket that stalls during final status/completion delivery.
      SseEmitterDelivery delivery = localDeliveries.get(emitter);
      if (delivery != null) delivery.completeAfterPending();
    }
  }

  void deliverLocal(String runId, String json, long seqNum) {
    List<SseEmitter> clients = emitters.getOrDefault(runId, new CopyOnWriteArrayList<>());
    if (clients.isEmpty()) return;
    for (SseEmitter emitter : clients) {
      SseEmitterDelivery delivery = localDeliveries.get(emitter);
      if (delivery != null) delivery.offer(json, seqNum);
    }
  }

  private void retireLiveSubscribersLocked(String runId) {
    List<SseEmitter> clients = emitters.remove(runId);
    if (clients == null) return;
    totalEmitterCount.addAndGet(-clients.size());
    for (SseEmitter emitter : clients) {
      SseEmitterDelivery delivery = localDeliveries.get(emitter);
      if (delivery != null) delivery.abort(true);
    }
  }

  /** Appends JSON to the Redis replay list with a monotonic per-run sequence. */
  private long appendToReplayLog(String runId, String json) {
    return replayStore.append(runId, json);
  }

  /**
   * RL-2026-05-046 — returns the persisted replay log for a run as parsed event objects, so the
   * run-detail "Logs" tab can render the live log even after the run finished (within the 24h
   * replay TTL). Reuses the same Redis list the reconnect-replay uses — no new persistence. Each
   * entry already carries {@code type} + {@code timestamp} + payload fields (see {@link RunEvent}).
   */
  public ReplayLog getReplayLog(String runId) {
    return replayStore.getReplayLog(runId);
  }

  public record ReplayLog(
      List<java.util.Map<String, Object>> events,
      boolean available,
      String reason,
      Instant retainedUntil,
      String lastEventId,
      boolean truncated) {}

  private void publishToRedis(String runId, String json, long sequence) {
    try {
      // Wrap with instance marker to suppress echo on receive
      String msg =
          "{\"from\":\"" + INSTANCE_ID + "\",\"seq\":" + sequence + ",\"data\":" + json + "}";
      redis.convertAndSend(CHANNEL_PREFIX + runId, msg);
    } catch (Exception e) {
      log.debug("[SSE] Redis publish failed for run {}: {}", runId, e.getMessage());
    }
  }

  private List<SseEmitterDelivery.ReplayFrame> loadMissedEvents(String runId, String lastEventId) {
    return replayStore.loadMissedEvents(runId, lastEventId);
  }

  private String serialize(RunEvent event) {
    try {
      return mapper.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      log.warn("SSE serialize failed: {}", e.getMessage());
      return null;
    }
  }

  private void remove(String runId, SseEmitter emitter) {
    runDeliveryCoordinator.withRunLock(runId, () -> removeLocked(runId, emitter));
  }

  private void removeLocked(String runId, SseEmitter emitter) {
    List<SseEmitter> clients = emitters.get(runId);
    if (clients != null && clients.remove(emitter)) {
      totalEmitterCount.decrementAndGet();
      if (clients.isEmpty()) {
        emitters.remove(runId, clients);
        runDeliveryCoordinator.forgetOrder(runId);
      }
    }
    SseEmitterDelivery delivery = localDeliveries.remove(emitter);
    if (delivery != null) delivery.abort(false);
  }

  /** Package-visible registration seam for deterministic socket-isolation tests. */
  void registerLocalEmitter(String runId, SseEmitter emitter) {
    admitLocalEmitter(runId, emitter).delivery().finishBootstrap(false, List.of());
  }

  private SseEmitterDelivery prepareLocalEmitter(
      String runId, SseEmitter emitter, boolean liveSubscriber) {
    List<SseEmitter> clients = null;
    if (liveSubscriber) {
      clients = emitters.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>());
      clients.add(emitter);
      totalEmitterCount.incrementAndGet();
    }
    reserveDeliverySlot(runId);
    SseEmitterDelivery delivery =
        new SseEmitterDelivery(
            runId,
            emitter,
            localDeliveryExecutor,
            () -> remove(runId, emitter),
            () -> releaseDeliverySlot(runId),
            backpressureEvictionCount::incrementAndGet,
            () -> requestEmitterClose(emitter));
    localDeliveries.put(emitter, delivery);
    try {
      delivery.start();
    } catch (RuntimeException rejected) {
      localDeliveries.remove(emitter, delivery);
      if (clients != null && clients.remove(emitter)) totalEmitterCount.decrementAndGet();
      releaseDeliverySlot(runId);
      throw rejected;
    }
    return delivery;
  }

  private SubscriptionAdmission admitLocalEmitter(String runId, SseEmitter emitter) {
    SseRunDeliveryCoordinator.Admission<SseEmitterDelivery> admission =
        runDeliveryCoordinator.admit(
            runId,
            terminalAtAdmission -> {
              synchronized (subscriptionAdmissionLock) {
                if (deliveryTaskCount.get() + outstandingCloseAttempts.get()
                    >= MAX_TOTAL_EMITTERS) {
                  Counter.builder("executespec.sse.emitters.rejected")
                      .tag("reason", "global_limit")
                      .register(meterRegistry)
                      .increment();
                  log.warn(
                      "SSE global delivery limit ({}) reached — rejecting subscription for run {}",
                      MAX_TOTAL_EMITTERS,
                      runId);
                  throw new ResponseStatusException(
                      HttpStatus.TOO_MANY_REQUESTS,
                      "SSE connection limit reached. Try again later.");
                }
                if (deliveryTaskCountFor(runId) >= MAX_EMITTERS_PER_RUN) {
                  Counter.builder("executespec.sse.emitters.rejected")
                      .tag("reason", "per_run_limit")
                      .register(meterRegistry)
                      .increment();
                  log.warn(
                      "SSE per-run delivery limit ({}) reached for run {} — rejecting",
                      MAX_EMITTERS_PER_RUN,
                      runId);
                  throw new ResponseStatusException(
                      HttpStatus.TOO_MANY_REQUESTS,
                      "Too many SSE connections for this run (max " + MAX_EMITTERS_PER_RUN + ").");
                }
                SseEmitterDelivery delivery =
                    prepareLocalEmitter(runId, emitter, !terminalAtAdmission);
                if (terminalAtAdmission) delivery.completeAfterPending();
                return delivery;
              }
            });
    return new SubscriptionAdmission(admission.value(), admission.terminalAtAdmission());
  }

  private int deliveryTaskCountFor(String runId) {
    AtomicInteger count = deliveryTaskCountByRun.get(runId);
    return count == null ? 0 : count.get();
  }

  private void reserveDeliverySlot(String runId) {
    deliveryTaskCount.incrementAndGet();
    deliveryTaskCountByRun.computeIfAbsent(runId, ignored -> new AtomicInteger()).incrementAndGet();
  }

  private void releaseDeliverySlot(String runId) {
    deliveryTaskCount.decrementAndGet();
    deliveryTaskCountByRun.computeIfPresent(
        runId,
        (ignored, count) -> {
          count.decrementAndGet();
          return count.get() <= 0 ? null : count;
        });
  }

  /** One bounded maintenance sweep owns heartbeat and stalled-send retirement. */
  void maintainLocalDeliveries() {
    long now = System.nanoTime();
    for (SseEmitterDelivery delivery : localDeliveries.values()) {
      try {
        delivery.maintain(now, HEARTBEAT_INTERVAL.toNanos(), SEND_STALL_TIMEOUT.toNanos());
      } catch (RuntimeException maintenanceFailure) {
        log.debug("SSE maintenance failed: {}", maintenanceFailure.getMessage());
      }
    }
    runDeliveryCoordinator.maintain(now);
  }

  /** Package-visible seam for deterministic cross-instance ordering tests. */
  void deliverRemote(String runId, String json, long sequence) {
    runDeliveryCoordinator.deliverRemote(runId, json, sequence);
  }

  /**
   * A close may itself wait in the servlet container. Permit-gating makes that resource explicit
   * and bounded; a stuck writer remains counted separately until its task really exits.
   */
  private void requestEmitterClose(SseEmitter emitter) {
    if (!closeAttemptPermits.tryAcquire()) {
      log.warn(
          "SSE close-attempt limit ({}) reached; relying on writer interruption/container cleanup",
          MAX_CONCURRENT_CLOSE_ATTEMPTS);
      return;
    }
    outstandingCloseAttempts.incrementAndGet();
    try {
      Thread.ofVirtual()
          .name("executespec-sse-close")
          .start(
              () -> {
                try {
                  emitter.complete();
                } catch (Throwable ignored) {
                  // The client or container has already closed the async response.
                } finally {
                  outstandingCloseAttempts.decrementAndGet();
                  closeAttemptPermits.release();
                }
              });
    } catch (RuntimeException startFailure) {
      outstandingCloseAttempts.decrementAndGet();
      closeAttemptPermits.release();
      log.debug("SSE close task could not start: {}", startFailure.getMessage());
    }
  }

  private class SseRedisListener implements MessageListener {
    @Override
    public void onMessage(Message message, byte[] pattern) {
      try {
        String raw = new String(message.getBody());
        String channel = new String(message.getChannel());
        String runId = channel.substring(CHANNEL_PREFIX.length());

        // Parse envelope: {"from":"...", "data":{...}}
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(raw);
        String from = node.path("from").asText();

        // Suppress echo — this instance already delivered locally on emit()
        if (INSTANCE_ID.equals(from)) return;

        long sequence = node.path("seq").asLong(0);
        String json = mapper.writeValueAsString(node.get("data"));

        deliverRemote(runId, json, sequence);
      } catch (Exception e) {
        log.debug("[SSE] Redis listener error: {}", e.getMessage());
      }
    }
  }

  private record SubscriptionAdmission(SseEmitterDelivery delivery, boolean terminalAtAdmission) {}
}
