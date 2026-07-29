package com.navneet.executespec.codeintel;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ProjectArchitectureJobConfig {

  @Bean(name = "projectArchitectureExecutor")
  ThreadPoolTaskExecutor projectArchitectureExecutor(ProjectArchitectureJobProperties properties) {
    int parallelism = Math.max(1, Math.min(properties.getMaxParallelJobs(), 4));
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(parallelism);
    executor.setMaxPoolSize(parallelism);
    executor.setQueueCapacity(parallelism);
    executor.setThreadNamePrefix("project-architecture-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.initialize();
    return executor;
  }

  @Bean(name = "projectArchitectureLeaseExecutor", destroyMethod = "shutdownNow")
  ScheduledExecutorService projectArchitectureLeaseExecutor() {
    var executor = new ScheduledThreadPoolExecutor(1);
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }
}
