package com.navneet.executespec.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Component
@ConfigurationProperties(prefix = "agent.context")
@Data
public class ContextAgentProperties {

  private boolean enabled = true;

  /**
   * 2026-05-06 — null = use YAML model-matrix LIGHTWEIGHT for the configured provider. The prior
   * hardcoded default was a duplicate source of truth that drifted from the YAML matrix. Consumers
   * should fall back to {@code ModelRegistry.lightweightModelFor(provider)} when this is null.
   */
  private String model = null;

  private String provider = "anthropic";

  /** Run after every N completed runs. 1 = every run. */
  private int runInterval = 1;
}
