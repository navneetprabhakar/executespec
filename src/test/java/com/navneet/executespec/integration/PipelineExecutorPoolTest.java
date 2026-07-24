package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class PipelineExecutorPoolTest {

  @Test
  void lifecycleStopInterruptsActiveWorkAndRejectsNewProviderWork() throws Exception {
    PipelineExecutorPool pool = new PipelineExecutorPool();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);

    pool.submit(
        () -> {
          started.countDown();
          try {
            new CountDownLatch(1).await();
          } catch (InterruptedException expected) {
            Thread.currentThread().interrupt();
            interrupted.countDown();
          }
        });
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

    pool.stop();

    assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(pool.isShuttingDown()).isTrue();
    assertThat(pool.isRunning()).isFalse();
    assertThat(pool.getPhase()).isEqualTo(Integer.MAX_VALUE);
    assertThatThrownBy(() -> pool.submit(() -> {})).isInstanceOf(RejectedExecutionException.class);
  }

  @Test
  void callbackStopIsIdempotent() {
    PipelineExecutorPool pool = new PipelineExecutorPool();
    CountDownLatch callback = new CountDownLatch(1);

    pool.stop(callback::countDown);
    pool.shutdownPool();

    assertThat(callback.getCount()).isZero();
    assertThat(pool.isShuttingDown()).isTrue();
  }
}
