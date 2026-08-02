package com.navneet.executespec.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SseBroadcasterNonBlockingDeliveryTest {

  private SseBroadcaster broadcaster;
  private ListOperations<String, String> lists;
  private ValueOperations<String, String> values;
  private AtomicLong sequence;

  @BeforeEach
  void setUp() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    values = mock(ValueOperations.class);
    lists = mock(ListOperations.class);
    sequence = new AtomicLong();
    when(redis.opsForValue()).thenReturn(values);
    when(redis.opsForList()).thenReturn(lists);
    when(values.increment(any())).thenAnswer(ignored -> sequence.incrementAndGet());
    when(values.get(any())).thenAnswer(ignored -> String.valueOf(sequence.get()));
    broadcaster =
        new SseBroadcaster(
            new ObjectMapper().findAndRegisterModules(),
            mock(ApplicationEventPublisher.class),
            new SimpleMeterRegistry(),
            redis,
            mock(RedisMessageListenerContainer.class));
  }

  @AfterEach
  void tearDown() {
    broadcaster.shutdown();
  }

  @Test
  void blockingSubscriberCannotBlockEmitOrDelayAnotherSubscriber() throws Exception {
    CountDownLatch slowEntered = new CountDownLatch(1);
    CountDownLatch releaseSlow = new CountDownLatch(1);
    CountDownLatch fastSent = new CountDownLatch(1);
    SseEmitter slow = mock(SseEmitter.class);
    SseEmitter fast = mock(SseEmitter.class);
    doAnswer(
            ignored -> {
              slowEntered.countDown();
              releaseSlow.await();
              return null;
            })
        .when(slow)
        .send(any(SseEmitter.SseEventBuilder.class));
    doAnswer(
            ignored -> {
              fastSent.countDown();
              return null;
            })
        .when(fast)
        .send(any(SseEmitter.SseEventBuilder.class));
    broadcaster.registerLocalEmitter("run-1", slow);
    broadcaster.registerLocalEmitter("run-1", fast);

    assertTimeoutPreemptively(
        Duration.ofMillis(250),
        () -> broadcaster.emit("run-1", RunEvent.log("run-1", "task-1", "progress")));

    assertThat(slowEntered.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(fastSent.await(1, TimeUnit.SECONDS)).isTrue();
    releaseSlow.countDown();
  }

  @Test
  void eachSubscriberReceivesFramesSeriallyInOfferOrder() throws Exception {
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondSent = new CountDownLatch(1);
    AtomicLong calls = new AtomicLong();
    SseEmitter emitter = mock(SseEmitter.class);
    doAnswer(
            ignored -> {
              long call = calls.incrementAndGet();
              if (call == 1) {
                firstEntered.countDown();
                releaseFirst.await();
              } else if (call == 2) {
                secondSent.countDown();
              }
              return null;
            })
        .when(emitter)
        .send(any(SseEmitter.SseEventBuilder.class));
    broadcaster.registerLocalEmitter("run-2", emitter);

    broadcaster.deliverLocal("run-2", "first", 1);
    assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
    broadcaster.deliverLocal("run-2", "second", 2);

    assertThat(secondSent.await(100, TimeUnit.MILLISECONDS)).isFalse();
    releaseFirst.countDown();
    assertThat(secondSent.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void completionIsQueuedAfterAlreadyAcceptedTerminalFrames() throws Exception {
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch completed = new CountDownLatch(1);
    SseEmitter emitter = mock(SseEmitter.class);
    doAnswer(
            ignored -> {
              order.add("send");
              return null;
            })
        .when(emitter)
        .send(any(SseEmitter.SseEventBuilder.class));
    doAnswer(
            ignored -> {
              order.add("complete");
              completed.countDown();
              return null;
            })
        .when(emitter)
        .complete();
    broadcaster.registerLocalEmitter("run-3", emitter);

    broadcaster.deliverLocal("run-3", "terminal", 9);
    broadcaster.complete("run-3");

    assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(order).containsExactly("send", "complete");
    assertThat(broadcaster.activeEmitterCount()).isZero();
  }

  @Test
  void fullPrivateBufferEvictsOnlyTheSlowSubscriberWithoutBlockingProducer() throws Exception {
    CountDownLatch slowEntered = new CountDownLatch(1);
    CountDownLatch holdSlow = new CountDownLatch(1);
    CountDownLatch completedOffThread = new CountDownLatch(1);
    SseEmitter slow = mock(SseEmitter.class);
    doAnswer(
            ignored -> {
              slowEntered.countDown();
              holdSlow.await();
              return null;
            })
        .when(slow)
        .send(any(SseEmitter.SseEventBuilder.class));
    doAnswer(
            ignored -> {
              completedOffThread.countDown();
              return null;
            })
        .when(slow)
        .complete();
    broadcaster.registerLocalEmitter("run-4", slow);
    broadcaster.deliverLocal("run-4", "in-flight", 1);
    assertThat(slowEntered.await(1, TimeUnit.SECONDS)).isTrue();

    assertTimeoutPreemptively(
        Duration.ofMillis(250),
        () -> {
          for (int i = 0; i <= SseEmitterDelivery.MAX_PENDING_EVENTS; i++) {
            broadcaster.deliverLocal("run-4", "queued-" + i, i + 2L);
          }
        });

    assertThat(broadcaster.activeEmitterCount()).isZero();
    assertThat(completedOffThread.await(1, TimeUnit.SECONDS)).isTrue();
    holdSlow.countDown();
  }

  @Test
  void replaySnapshotAlwaysPrecedesLiveFramesAcceptedWhileSnapshotLoads() throws Exception {
    CountDownLatch replayReadEntered = new CountDownLatch(1);
    CountDownLatch releaseReplayRead = new CountDownLatch(1);
    when(lists.range("sse:replay:run-replay", 0, -1))
        .thenAnswer(
            ignored -> {
              replayReadEntered.countDown();
              releaseReplayRead.await();
              return List.of(
                  new ObjectMapper().writeValueAsString(Map.of("seq", 10, "payload", "replay-10")));
            });

    List<String> sentData = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch threeFrames = new CountDownLatch(3); // connected comment + replay + live
    SseEmitter emitter = mock(SseEmitter.class);
    doAnswer(
            invocation -> {
              String data = eventData(invocation.getArgument(0));
              if (data != null) sentData.add(data);
              threeFrames.countDown();
              return null;
            })
        .when(emitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    ExecutorService subscriber = Executors.newSingleThreadExecutor();
    try {
      Future<SseEmitter> subscribing =
          subscriber.submit(() -> broadcaster.subscribeWithEmitter("run-replay", "9", emitter));
      assertThat(replayReadEntered.await(1, TimeUnit.SECONDS)).isTrue();

      broadcaster.deliverLocal("run-replay", "live-11", 11);
      releaseReplayRead.countDown();
      assertThat(subscribing.get(1, TimeUnit.SECONDS)).isSameAs(emitter);
      assertThat(threeFrames.await(1, TimeUnit.SECONDS)).isTrue();

      assertThat(sentData).containsExactly("replay-10", "live-11");
    } finally {
      releaseReplayRead.countDown();
      subscriber.shutdownNow();
    }
  }

  @Test
  void concurrentEmitAndEmitDirectPreserveReplayAndLocalSequenceOrder() throws Exception {
    CountDownLatch firstReplayAppendEntered = new CountDownLatch(1);
    CountDownLatch releaseFirstReplayAppend = new CountDownLatch(1);
    CountDownLatch secondEmitterStarted = new CountDownLatch(1);
    AtomicInteger replayAppends = new AtomicInteger();
    when(lists.rightPush(any(), any()))
        .thenAnswer(
            ignored -> {
              if (replayAppends.incrementAndGet() == 1) {
                firstReplayAppendEntered.countDown();
                releaseFirstReplayAppend.await();
              }
              return 1L;
            });

    List<String> payloads = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch bothSent = new CountDownLatch(2);
    SseEmitter emitter = mock(SseEmitter.class);
    doAnswer(
            invocation -> {
              payloads.add(eventPayload(invocation.getArgument(0)));
              bothSent.countDown();
              return null;
            })
        .when(emitter)
        .send(any(SseEmitter.SseEventBuilder.class));
    broadcaster.registerLocalEmitter("run-serialized", emitter);

    ExecutorService racers = Executors.newFixedThreadPool(2);
    try {
      Future<?> first =
          racers.submit(
              () ->
                  broadcaster.emitDirect(
                      "run-serialized", RunEvent.log("run-serialized", "task", "first")));
      assertThat(firstReplayAppendEntered.await(1, TimeUnit.SECONDS)).isTrue();
      Future<?> second =
          racers.submit(
              () -> {
                secondEmitterStarted.countDown();
                broadcaster.emit(
                    "run-serialized", RunEvent.log("run-serialized", "task", "second"));
              });
      assertThat(secondEmitterStarted.await(1, TimeUnit.SECONDS)).isTrue();

      assertThat(second.isDone()).isFalse();
      assertThat(payloads).isEmpty();
      releaseFirstReplayAppend.countDown();
      first.get(1, TimeUnit.SECONDS);
      second.get(1, TimeUnit.SECONDS);
      assertThat(bothSent.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(payloads).containsExactly("first", "second");
    } finally {
      releaseFirstReplayAppend.countDown();
      racers.shutdownNow();
    }
  }

  @Test
  void completionCannotSealDeliveryBetweenReplayAppendAndLocalEnqueue() throws Exception {
    CountDownLatch replayAppendEntered = new CountDownLatch(1);
    CountDownLatch releaseReplayAppend = new CountDownLatch(1);
    when(lists.rightPush(any(), any()))
        .thenAnswer(
            ignored -> {
              replayAppendEntered.countDown();
              releaseReplayAppend.await();
              return 1L;
            });

    List<String> order = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch completed = new CountDownLatch(1);
    SseEmitter emitter = mock(SseEmitter.class);
    doAnswer(
            invocation -> {
              order.add(eventPayload(invocation.getArgument(0)));
              return null;
            })
        .when(emitter)
        .send(any(SseEmitter.SseEventBuilder.class));
    doAnswer(
            ignored -> {
              order.add("complete");
              completed.countDown();
              return null;
            })
        .when(emitter)
        .complete();
    broadcaster.registerLocalEmitter("run-emit-complete", emitter);

    ExecutorService racers = Executors.newFixedThreadPool(2);
    try {
      Future<?> emitting =
          racers.submit(
              () ->
                  broadcaster.emitDirect(
                      "run-emit-complete", RunEvent.log("run-emit-complete", "task", "terminal")));
      assertThat(replayAppendEntered.await(1, TimeUnit.SECONDS)).isTrue();
      Future<?> completing = racers.submit(() -> broadcaster.complete("run-emit-complete"));

      assertThat(completed.await(100, TimeUnit.MILLISECONDS)).isFalse();
      assertThat(completing.isDone()).isFalse();
      releaseReplayAppend.countDown();
      emitting.get(1, TimeUnit.SECONDS);
      completing.get(1, TimeUnit.SECONDS);
      assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(order).containsExactly("terminal", "complete");
    } finally {
      releaseReplayAppend.countDown();
      racers.shutdownNow();
    }
  }

  @Test
  void subscriptionInstallationAndCompletionShareTheRunBoundary() throws Exception {
    CountDownLatch sequenceReadEntered = new CountDownLatch(1);
    CountDownLatch releaseSequenceRead = new CountDownLatch(1);
    when(values.get("sse:sequence:run-subscribe-complete"))
        .thenAnswer(
            ignored -> {
              sequenceReadEntered.countDown();
              releaseSequenceRead.await();
              return "0";
            });

    CountDownLatch completed = new CountDownLatch(1);
    SseEmitter emitter = mock(SseEmitter.class);
    doAnswer(ignored -> null).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
    doAnswer(
            ignored -> {
              completed.countDown();
              return null;
            })
        .when(emitter)
        .complete();

    ExecutorService racers = Executors.newFixedThreadPool(2);
    try {
      Future<SseEmitter> subscribing =
          racers.submit(
              () -> broadcaster.subscribeWithEmitter("run-subscribe-complete", null, emitter));
      assertThat(sequenceReadEntered.await(1, TimeUnit.SECONDS)).isTrue();
      Future<?> completing = racers.submit(() -> broadcaster.complete("run-subscribe-complete"));

      assertThat(completing.isDone()).isFalse();
      releaseSequenceRead.countDown();
      assertThat(subscribing.get(1, TimeUnit.SECONDS)).isSameAs(emitter);
      completing.get(1, TimeUnit.SECONDS);
      assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(broadcaster.activeEmitterCount()).isZero();
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(1))
          .untilAsserted(() -> assertThat(deliveryTaskCount()).isZero());
    } finally {
      releaseSequenceRead.countDown();
      racers.shutdownNow();
    }
  }

  @Test
  void subscriptionAfterCompletionReplaysTerminalEventThenCloses() throws Exception {
    String runId = "run-already-complete";
    List<String> replayEntries = Collections.synchronizedList(new ArrayList<>());
    when(lists.rightPush(any(), any()))
        .thenAnswer(
            invocation -> {
              replayEntries.add(invocation.getArgument(1));
              return 1L;
            });
    when(lists.range("sse:replay:" + runId, 0, -1))
        .thenAnswer(ignored -> List.copyOf(replayEntries));
    broadcaster.emitDirect(runId, RunEvent.complete(runId, "FAILED"));
    broadcaster.complete(runId);

    List<String> order = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch completed = new CountDownLatch(1);
    SseEmitter emitter = mock(SseEmitter.class);
    doAnswer(
            invocation -> {
              String data = eventData(invocation.getArgument(0));
              if (data != null)
                order.add(new ObjectMapper().readTree(data).path("payload").asText());
              return null;
            })
        .when(emitter)
        .send(any(SseEmitter.SseEventBuilder.class));
    doAnswer(
            ignored -> {
              order.add("complete");
              completed.countDown();
              return null;
            })
        .when(emitter)
        .complete();

    broadcaster.subscribeWithEmitter(runId, null, emitter);

    assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(order).containsExactly("FAILED", "complete");
    assertThat(broadcaster.activeEmitterCount()).isZero();
  }

  @Test
  void reversedConcurrentRemoteTerminalFramesAreReorderedBeforePeerClose() throws Exception {
    String runId = "run-remote-order";
    sequence.set(10);
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch completed = new CountDownLatch(1);
    SseEmitter emitter = mock(SseEmitter.class);
    doAnswer(
            invocation -> {
              order.add(eventPayload(invocation.getArgument(0)));
              return null;
            })
        .when(emitter)
        .send(any(SseEmitter.SseEventBuilder.class));
    doAnswer(
            ignored -> {
              order.add("complete");
              completed.countDown();
              return null;
            })
        .when(emitter)
        .complete();
    broadcaster.registerLocalEmitter(runId, emitter);

    String eleven = eventJson(RunEvent.log(runId, "task", "eleven"));
    String terminal = eventJson(RunEvent.complete(runId, "FAILED"));
    CountDownLatch olderCallbackStarted = new CountDownLatch(1);
    CountDownLatch releaseOlderCallback = new CountDownLatch(1);
    ExecutorService callbacks = Executors.newFixedThreadPool(2);
    try {
      Future<?> older =
          callbacks.submit(
              () -> {
                olderCallbackStarted.countDown();
                releaseOlderCallback.await();
                broadcaster.deliverRemote(runId, eleven, 11);
                return null;
              });
      assertThat(olderCallbackStarted.await(1, TimeUnit.SECONDS)).isTrue();
      Future<?> newer = callbacks.submit(() -> broadcaster.deliverRemote(runId, terminal, 12));
      newer.get(1, TimeUnit.SECONDS);

      assertThat(order).isEmpty();
      releaseOlderCallback.countDown();
      older.get(1, TimeUnit.SECONDS);
      assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(order).containsExactly("eleven", "FAILED", "complete");
    } finally {
      releaseOlderCallback.countDown();
      callbacks.shutdownNow();
    }
  }

  @Test
  void completionBarrierNeverPlacesAnAcceptedFrameBehindCompletion() throws Exception {
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(1);
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    SseEmitter emitter = mock(SseEmitter.class);
    doAnswer(
            invocation -> {
              String data = eventData(invocation.getArgument(0));
              order.add(data);
              if ("first".equals(data)) {
                firstEntered.countDown();
                releaseFirst.await();
              }
              return null;
            })
        .when(emitter)
        .send(any(SseEmitter.SseEventBuilder.class));
    doAnswer(
            ignored -> {
              order.add("complete");
              completed.countDown();
              return null;
            })
        .when(emitter)
        .complete();
    broadcaster.registerLocalEmitter("run-seal-race", emitter);
    broadcaster.deliverLocal("run-seal-race", "first", 1);
    assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();

    SseEmitterDelivery delivery = deliveryFor(emitter);
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService racers = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> offered =
          racers.submit(
              () -> {
                start.await();
                return delivery.offer("racing", 2);
              });
      Future<?> sealed =
          racers.submit(
              () -> {
                start.await();
                delivery.completeAfterPending();
                return null;
              });
      boolean accepted = offered.get(1, TimeUnit.SECONDS);
      sealed.get(1, TimeUnit.SECONDS);
      releaseFirst.countDown();
      assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();

      if (accepted) assertThat(order).containsExactly("first", "racing", "complete");
      else assertThat(order).containsExactly("first", "complete");
    } finally {
      releaseFirst.countDown();
      racers.shutdownNow();
    }
  }

  @Test
  void initialOrReplaySocketFailureRemovesSubscriberImmediately() throws Exception {
    SseEmitter emitter = mock(SseEmitter.class);
    doAnswer(
            ignored -> {
              throw new java.io.IOException("closed");
            })
        .when(emitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    broadcaster.subscribeWithEmitter("run-dead", null, emitter);

    verify(emitter, timeout(1000)).send(any(SseEmitter.SseEventBuilder.class));
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(broadcaster.activeEmitterCount()).isZero());
  }

  @Test
  void stalledWriterIsRetiredButRemainsCountedUntilTheBlockedTaskActuallyExits() throws Exception {
    CountDownLatch sendEntered = new CountDownLatch(1);
    CountDownLatch releaseSend = new CountDownLatch(1);
    SseEmitter emitter = mock(SseEmitter.class);
    doAnswer(
            ignored -> {
              sendEntered.countDown();
              boolean released = false;
              while (!released) {
                try {
                  released = releaseSend.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException intentionallyIgnored) {
                  // Model a servlet/socket call that does not honour writer interruption.
                }
              }
              return null;
            })
        .when(emitter)
        .send(any(SseEmitter.SseEventBuilder.class));
    broadcaster.registerLocalEmitter("run-stalled", emitter);
    broadcaster.deliverLocal("run-stalled", "blocked", 1);
    assertThat(sendEntered.await(1, TimeUnit.SECONDS)).isTrue();

    deliveryFor(emitter).maintain(Long.MAX_VALUE, 1, 1);

    assertThat(broadcaster.activeEmitterCount()).isZero();
    assertThat(deliveryTaskCount()).isEqualTo(1);
    releaseSend.countDown();
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(deliveryTaskCount()).isZero());
  }

  @Test
  void blockingEmitterCompletionUsesBoundedAccountedCloseLane() throws Exception {
    CountDownLatch completeEntered = new CountDownLatch(1);
    CountDownLatch releaseComplete = new CountDownLatch(1);
    SseEmitter emitter = mock(SseEmitter.class);
    doAnswer(ignored -> null).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
    doAnswer(
            ignored -> {
              completeEntered.countDown();
              boolean released = false;
              while (!released) {
                try {
                  released = releaseComplete.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException intentionallyIgnored) {
                  // Model a servlet completion call that does not honour interruption.
                }
              }
              return null;
            })
        .when(emitter)
        .complete();

    try {
      broadcaster.registerLocalEmitter("run-blocked-complete", emitter);
      broadcaster.deliverLocal("run-blocked-complete", "terminal", 1);
      broadcaster.complete("run-blocked-complete");

      assertThat(completeEntered.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(broadcaster.activeEmitterCount()).isZero();
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(1))
          .untilAsserted(() -> assertThat(deliveryTaskCount()).isZero());
      assertThat(outstandingCloseAttempts()).isEqualTo(1);
    } finally {
      releaseComplete.countDown();
    }
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(outstandingCloseAttempts()).isZero());
  }

  @SuppressWarnings("unchecked")
  private SseEmitterDelivery deliveryFor(SseEmitter emitter) {
    Map<SseEmitter, SseEmitterDelivery> deliveries =
        (Map<SseEmitter, SseEmitterDelivery>)
            org.springframework.test.util.ReflectionTestUtils.getField(
                broadcaster, "localDeliveries");
    return deliveries.get(emitter);
  }

  private int deliveryTaskCount() {
    AtomicInteger count =
        (AtomicInteger)
            org.springframework.test.util.ReflectionTestUtils.getField(
                broadcaster, "deliveryTaskCount");
    return count.get();
  }

  private int outstandingCloseAttempts() {
    AtomicInteger count =
        (AtomicInteger)
            org.springframework.test.util.ReflectionTestUtils.getField(
                broadcaster, "outstandingCloseAttempts");
    return count.get();
  }

  private static String eventData(SseEmitter.SseEventBuilder builder) throws Exception {
    Method build = builder.getClass().getDeclaredMethod("build");
    build.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.Set<DataWithMediaType> parts =
        (java.util.Set<DataWithMediaType>) build.invoke(builder);
    return parts.stream()
        .map(DataWithMediaType::getData)
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .filter(
            value ->
                !value.startsWith("id:") && !value.startsWith(":") && !value.startsWith("data:"))
        .filter(value -> !"\n".equals(value))
        .findFirst()
        .orElse(null);
  }

  private static String eventPayload(SseEmitter.SseEventBuilder builder) throws Exception {
    String json = eventData(builder);
    return new ObjectMapper().readTree(json).path("payload").asText();
  }

  private static String eventJson(RunEvent event) throws Exception {
    return new ObjectMapper().findAndRegisterModules().writeValueAsString(event);
  }
}
