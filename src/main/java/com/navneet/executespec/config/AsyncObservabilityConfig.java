package com.navneet.executespec.config;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * B-09 (audit, 2026-05-09) — global handler for uncaught {@link
 * org.springframework.scheduling.annotation.Async} exceptions.
 *
 * <p>Pre-fix Spring's default {@code SimpleAsyncUncaughtExceptionHandler} logs the exception at
 * ERROR and moves on — no metric, no alert, no on-call signal. Multiple sites in the codebase
 * already swallow @Async exceptions on purpose (the comment in {@code ActivityEventEmitter} is
 * explicit: "Failures are logged but never rethrown — losing one event is preferable to blocking
 * the whole pipeline"). The intent is right; the observability gap is what the audit flagged.
 *
 * <p>This handler:
 *
 * <ul>
 *   <li>Logs the exception at ERROR with the bean + method that threw, so the dump is grep-able.
 *   <li>Increments a Micrometer counter {@code executespec.async.uncaught.exception} tagged with
 *       the throwing class + method, so dashboards + alerts can fire on the rate.
 * </ul>
 *
 * <p>The MeterRegistry reference is held in an {@link AtomicReference} because Spring constructs
 * the {@link AsyncConfigurer} bean before the {@code MeterRegistry} bean is available in some test
 * profiles; we resolve lazily on first call. If no registry was ever published, we just log
 * (degraded mode) — never NPE the caller's @Async work.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class AsyncObservabilityConfig implements AsyncConfigurer {

  private final org.springframework.beans.factory.ObjectProvider<MeterRegistry>
      meterRegistryProvider;
  private final ConcurrentMap<String, Counter> counterByKey = new ConcurrentHashMap<>();

  @Value("${executespec.async.core-pool-size:4}")
  private int corePoolSize;

  @Value("${executespec.async.max-pool-size:16}")
  private int maxPoolSize;

  @Value("${executespec.async.queue-capacity:500}")
  private int queueCapacity;

  /**
   * RR-109 — bounded executor for every UNQUALIFIED {@code @Async} method (mail sends, referral /
   * invite / run-completion notifications, SSE fan-out). Without this override Spring falls back to
   * {@code SimpleAsyncTaskExecutor}, which spawns a brand-new unbounded thread per task, so a
   * notification/invite burst could create unbounded concurrent tasks/connections and exhaust
   * threads, memory or provider limits. The pool is capped and its queue is bounded; {@link
   * ThreadPoolExecutor.CallerRunsPolicy} applies backpressure by running the overflow task on the
   * caller thread rather than dropping it or allocating without limit.
   */
  @Override
  public Executor getAsyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(corePoolSize);
    executor.setMaxPoolSize(maxPoolSize);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix("es-async-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(20);
    executor.initialize();
    return executor;
  }

  @Override
  public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
    return (Throwable ex, Method method, Object... params) -> {
      String className = method.getDeclaringClass().getSimpleName();
      String methodName = method.getName();
      log.error(
          "[async-uncaught] {}#{} threw {}: {}",
          className,
          methodName,
          ex.getClass().getSimpleName(),
          ex.getMessage(),
          ex);
      MeterRegistry reg = meterRegistryProvider.getIfAvailable();
      if (reg == null) return; // degraded — log-only
      String key = className + "#" + methodName;
      Counter counter =
          counterByKey.computeIfAbsent(
              key,
              k ->
                  Counter.builder("executespec.async.uncaught.exception")
                      .description("Uncaught exceptions thrown from @Async methods")
                      .tag("class", className)
                      .tag("method", methodName)
                      .register(reg));
      counter.increment();
    };
  }
}
