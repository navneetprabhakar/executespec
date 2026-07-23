package com.navneet.executespec.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.integration.IntegrationProperties;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OperationalReleasePolicyTest {

  @Test
  void prodFailsClosedForDeploymentWithoutRecoveryAttestationAndForOutboundDelivery() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    AgentProperties agent = new AgentProperties();
    agent.getDeployment().setEnabled(true);
    IntegrationProperties integrations = new IntegrationProperties();
    integrations.setEnabled(true);

    OperationalReleasePolicy policy =
        new OperationalReleasePolicy(environment, agent, integrations, false, false);

    assertThat(policy.deploymentCreationEnabled()).isFalse();
    assertThat(policy.outboundIntegrationDeliveryEnabled()).isFalse();
    assertThat(policy.sharedPersistentWorkspaceConfigured()).isFalse();
  }

  @Test
  void explicitRecoveryAttestationAllowsDeploymentButNotOutboundDeliveryInBenchmark() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("benchmark");
    AgentProperties agent = new AgentProperties();
    agent.getDeployment().setEnabled(true);
    IntegrationProperties integrations = new IntegrationProperties();
    integrations.setEnabled(true);

    OperationalReleasePolicy policy =
        new OperationalReleasePolicy(environment, agent, integrations, true, true);

    assertThat(policy.deploymentCreationEnabled()).isTrue();
    assertThat(policy.outboundIntegrationDeliveryEnabled()).isFalse();
    assertThat(policy.sharedPersistentWorkspaceConfigured()).isTrue();
  }

  @Test
  void localOutboundPathIsExplicitlyBestEffortAndHonorsMasterSwitch() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("local");
    AgentProperties agent = new AgentProperties();
    IntegrationProperties integrations = new IntegrationProperties();
    integrations.setEnabled(true);

    OperationalReleasePolicy enabled =
        new OperationalReleasePolicy(environment, agent, integrations, false, false);
    assertThat(enabled.outboundIntegrationDeliveryEnabled()).isTrue();

    integrations.setEnabled(false);
    OperationalReleasePolicy disabled =
        new OperationalReleasePolicy(environment, agent, integrations, false, false);
    assertThat(disabled.outboundIntegrationDeliveryEnabled()).isFalse();
  }
}
