package com.navneet.executespec.export;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import lombok.Data;

/** Hard ceilings and durability controls for project-export jobs. */
@Component
@ConfigurationProperties(prefix = "project.exports")
@Validated
@Data
public class ProjectExportProperties {

  @Min(1) @Max(100) private int maxJobsPerProjectPerDay = 5;

  @Min(1) @Max(720) private int retentionHours = 24;

  @Min(1) @Max(10_000) private int maxRepos = 1000;

  @Min(1) @Max(100_000) private int maxContracts = 10_000;

  @Min(1) @Max(1_000_000) private int maxActivityEvents = 100_000;

  @Min(1_048_576) @Max(1_073_741_824) private long maxUncompressedBytes = 268_435_456L;

  @Min(1) @Max(1000) private int pageSize = 500;

  @Min(1) @Max(10) private int maxAttempts = 3;

  @Min(30) @Max(3600) private int leaseSeconds = 600;

  @Min(1) @Max(8) private int maxParallelJobs = 2;

  @Min(1) @Max(100) private int listLimit = 25;

  @Min(250) @Max(60_000) private long pollIntervalMillis = 2000;

  private boolean gcsEnabled;
  private String storageRoot;
}
