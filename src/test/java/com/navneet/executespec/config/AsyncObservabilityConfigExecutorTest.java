package com.navneet.executespec.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * RR-109 — unqualified {@code @Async} work must run on a BOUNDED executor, not Spring's fallback
 * unbounded {@code SimpleAsyncTaskExecutor}.
 */
@ExtendWith(MockitoExtension.class)
class AsyncObservabilityConfigExecutorTest {

  @Test
  @SuppressWarnings("unchecked")
  void getAsyncExecutor_isBoundedThreadPoolWithBackpressure() {
    ObjectProvider<MeterRegistry> provider =
        (ObjectProvider<MeterRegistry>) org.mockito.Mockito.mock(ObjectProvider.class);
    AsyncObservabilityConfig config = new AsyncObservabilityConfig(provider);
    ReflectionTestUtils.setField(config, "corePoolSize", 4);
    ReflectionTestUtils.setField(config, "maxPoolSize", 16);
    ReflectionTestUtils.setField(config, "queueCapacity", 500);

    Executor executor = config.getAsyncExecutor();
    assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
    ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
    assertThat(pool.getMaxPoolSize()).isEqualTo(16);
    // Bounded queue → backpressure via CallerRunsPolicy, never unbounded thread creation.
    assertThat(pool.getThreadPoolExecutor().getRejectedExecutionHandler())
        .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
  }
}
