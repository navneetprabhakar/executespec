package com.navneet.executespec.codeintel;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import lombok.Data;

/** Bounded concurrency and recovery controls for V353 architecture jobs. */
@Component
@ConfigurationProperties(prefix = "project.architecture-jobs")
@Validated
@Data
public class ProjectArchitectureJobProperties {
  @Min(1) @Max(5) private int maxAttempts = 2;

  @Min(120) @Max(3600) private int leaseSeconds = 900;

  @Min(1) @Max(4) private int maxParallelJobs = 2;

  @Min(250) @Max(60_000) private long pollIntervalMillis = 2000;

  @Min(600) @Max(14_400) private int deadlineSeconds = 7200;

  @Min(1) @Max(365) private int retentionDays = 30;
}
