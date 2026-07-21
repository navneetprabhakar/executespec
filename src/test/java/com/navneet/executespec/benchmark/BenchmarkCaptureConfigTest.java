package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

class BenchmarkCaptureConfigTest {

  @Test
  void dispatchesCaptureOffTheSubmissionThread() throws Exception {
    var executor = new BenchmarkCaptureConfig().benchmarkCaptureExecutor();
    var taskThread = new AtomicReference<Thread>();
    var completed = new CountDownLatch(1);
    Thread caller = Thread.currentThread();
    try {
      executor.execute(
          () -> {
            taskThread.set(Thread.currentThread());
            completed.countDown();
          });

      assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(taskThread.get()).isNotSameAs(caller);
      assertThat(taskThread.get().getName()).startsWith("benchmark-capture-");
      assertThat(taskThread.get().isVirtual()).isTrue();
    } finally {
      ((SimpleAsyncTaskExecutor) executor).close();
    }
  }
}
