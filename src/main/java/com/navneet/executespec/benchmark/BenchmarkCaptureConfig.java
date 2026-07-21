package com.navneet.executespec.benchmark;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/** Executor boundary for legacy SWE-bench/ProgramBench terminal-artifact capture. */
@Configuration
public class BenchmarkCaptureConfig {

  @Bean(name = "benchmarkCaptureExecutor")
  TaskExecutor benchmarkCaptureExecutor() {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("benchmark-capture-");
    executor.setVirtualThreads(true);
    executor.setTaskTerminationTimeout(30_000);
    return executor;
  }
}
