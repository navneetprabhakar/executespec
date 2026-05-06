package com.navneet.executespec.optimiser;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Component
@ConfigurationProperties(prefix = "agent.optimiser")
@Data
public class PromptOptimiserProperties {

  private boolean enabled = true;

  /** Run every N completed runs */
  private int runInterval = 10;

  /** Number of recent runs to analyse for patterns */
  private int analysisWindowRuns = 20;

  /** Failure rate (0.0–1.0) that triggers early optimisation */
  private double failureRateThreshold = 0.20;

  /** Minimum occurrences of a pattern before it is acted on */
  private int patternMinOccurrences = 2;

  /**
   * 2026-05-06 — null = use YAML model-matrix PLANNING for the configured provider. The prior
   * hardcoded default was a duplicate source of truth that drifted from the YAML matrix.
   * Consumers should fall back to {@code ModelRegistry.planningModelFor(provider)} when this is
   * null.
   */
  private String model = null;

  private String provider = "anthropic";
}
