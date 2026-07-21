package com.navneet.executespec.export;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ProjectExportConfig {

  @Bean(name = "projectExportExecutor")
  ThreadPoolTaskExecutor projectExportExecutor(ProjectExportProperties properties) {
    int parallelism = Math.max(1, Math.min(properties.getMaxParallelJobs(), 8));
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(parallelism);
    executor.setMaxPoolSize(parallelism);
    executor.setQueueCapacity(parallelism * 2);
    executor.setThreadNamePrefix("project-export-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.initialize();
    return executor;
  }
}
