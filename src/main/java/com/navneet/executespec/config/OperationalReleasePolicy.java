package com.navneet.executespec.config;

import java.util.Arrays;

import com.navneet.executespec.integration.IntegrationProperties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-closed release policy for background work that does not yet have a durable replay contract.
 *
 * <p>Development can exercise the legacy best-effort paths. Production and benchmark profiles may
 * create deployments only after the operator explicitly attests that deployment recovery is safe;
 * outbound lifecycle integrations remain disabled there until they gain adapter-level idempotent
 * delivery.
 */
@Component
public class OperationalReleasePolicy {

  private final Environment environment;
  private final AgentProperties agentProperties;
  private final IntegrationProperties integrationProperties;
  private final boolean deploymentRecoverySafe;
  private final boolean sharedPersistentWorkspace;

  public OperationalReleasePolicy(
      Environment environment,
      AgentProperties agentProperties,
      IntegrationProperties integrationProperties,
      @Value("${agent.deployment.recovery-safe-enabled:false}") boolean deploymentRecoverySafe,
      @Value("${durability.workspace.shared-persistent:false}") boolean sharedPersistentWorkspace) {
    this.environment = environment;
    this.agentProperties = agentProperties;
    this.integrationProperties = integrationProperties;
    this.deploymentRecoverySafe = deploymentRecoverySafe;
    this.sharedPersistentWorkspace = sharedPersistentWorkspace;
  }

  public boolean isReleaseProfile() {
    return Arrays.stream(environment.getActiveProfiles())
        .anyMatch(profile -> "prod".equals(profile) || "benchmark".equals(profile));
  }

  /** Creation is off in release profiles unless recovery safety is explicitly attested. */
  public boolean deploymentCreationEnabled() {
    return agentProperties.getDeployment().isEnabled()
        && (!isReleaseProfile() || deploymentRecoverySafe);
  }

  /**
   * Current adapters append comments/pages and have no replay idempotency key. Keep them off in
   * release profiles even if an individual adapter was accidentally enabled.
   */
  public boolean outboundIntegrationDeliveryEnabled() {
    return integrationProperties.isEnabled() && !isReleaseProfile();
  }

  public boolean sharedPersistentWorkspaceConfigured() {
    return sharedPersistentWorkspace;
  }
}
