package com.navneet.executespec.benchmark;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/** Executor boundary for legacy SWE-bench/ProgramBench terminal-artifact capture. */
@Configuration
public class BenchmarkCaptureConfig {

  /**
   * RR-1206 — every SWE-bench/ProgramBench submission dispatches a long-lived poller here. Without
   * a concurrency bound a large or operator-retried batch spawns one poller per task with no
   * backpressure, amplifying DB traffic, memory retention and shutdown work. Cap concurrent capture
   * tasks; excess submissions queue at the throttle rather than all running at once.
   */
  @Bean(name = "benchmarkCaptureExecutor")
  TaskExecutor benchmarkCaptureExecutor(
      @Value("${benchmark.capture.max-concurrency:8}") int maxConcurrency) {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("benchmark-capture-");
    executor.setVirtualThreads(true);
    executor.setConcurrencyLimit(Math.max(1, maxConcurrency));
    executor.setTaskTerminationTimeout(30_000);
    return executor;
  }
}
