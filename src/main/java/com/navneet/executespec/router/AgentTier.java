package com.navneet.executespec.router;

/**
 * Classifies agents by their cognitive workload for model selection.
 *
 * <p>Each agent is mapped to a tier in {@code application.yml} under {@code
 * model-matrix.agent-tier-map}. The tier determines which model from the provider's column in the
 * matrix is used.
 */
public enum AgentTier {
  PLANNING,
  WORKHORSE,
  LIGHTWEIGHT
}
