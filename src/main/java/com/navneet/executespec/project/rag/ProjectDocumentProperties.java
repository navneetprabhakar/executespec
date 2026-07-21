package com.navneet.executespec.project.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/** Hard admission and extraction ceilings for untrusted project documents. */
@Component
@ConfigurationProperties(prefix = "project.documents")
@Getter
@Setter
public class ProjectDocumentProperties {

  private long maxFileBytes = 10L * 1024 * 1024;
  private long maxProjectBytes = 100L * 1024 * 1024;
  private int maxDocumentsPerProject = 100;
  private int maxPagesPerFile = 500;
  private int maxProjectPages = 5_000;
  private int maxExtractedCharsPerFile = 2_000_000;
  private long maxProjectExtractedChars = 20_000_000L;
  private int maxTokensPerFile = 500_000;
  private long maxProjectTokens = 5_000_000L;
  private int maxArchiveEntries = 5_000;
  private long maxArchiveInflatedBytes = 50L * 1024 * 1024;
  private int maxCompressionRatio = 100;
  private int maxProcessingAttempts = 3;

  /**
   * Hard wall-clock budget for one Tika extraction. Output size limits do not bound parse time, so
   * this is the only ceiling that keeps a pathological document from pinning the calling thread.
   */
  private long extractionTimeoutMillis = 20_000L;

  /** Ceiling on simultaneously running parses, so untrusted uploads cannot fan out unbounded. */
  private int maxConcurrentExtractions = 4;

  private boolean malwareScanRequired = true;
  private long staleProcessingMillis = 15L * 60 * 1000;
}
