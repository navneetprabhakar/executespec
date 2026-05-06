package com.navneet.executespec.apitests;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.router.ModelRegistry;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Thin delegate that exposes AgentProperties.ApiTestSuite fields as a typed bean.
 *
 * <p>2026-05-06 — {@link #getModel} now falls through to {@link
 * ModelRegistry#workhouseModelFor(String)} when no explicit YAML override exists. The Properties
 * class default ({@code claude-haiku-4-5-20251001}) was a duplicate source of truth that drifted
 * from the YAML matrix; routing through the registry restores YAML as the single source.
 */
@Component
@RequiredArgsConstructor
public class ApiTestSuiteProperties {

  private final AgentProperties props;
  private final ModelRegistry modelRegistry;

  public boolean isEnabled() {
    return props.getApiTestSuite().isEnabled();
  }

  public String getModel() {
    String configured = props.getApiTestSuite().getModel();
    if (configured != null && !configured.isBlank()) return configured;
    // Fall through to YAML model-matrix workhorse for the configured provider.
    return modelRegistry.workhouseModelFor(getProvider());
  }

  public String getProvider() {
    return props.getApiTestSuite().getProvider();
  }
}
