package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GcsWorkspaceStoreConfigurationTest {

  @Test
  void blankBucketDisablesStoreWithoutAttemptingAdc() {
    DeploymentProperties props =
        properties(new DeploymentProperties.WorkspaceConfig(7, "", 24, true));

    assertThat(new GcsWorkspaceStore(props, "local").isConfigured()).isFalse();
  }

  @Test
  void explicitWorkspaceGcsDisableWinsEvenWhenBucketIsPresent() {
    DeploymentProperties props =
        properties(new DeploymentProperties.WorkspaceConfig(7, "configured-bucket", 24, false));

    assertThat(new GcsWorkspaceStore(props, "local").isConfigured()).isFalse();
  }

  private static DeploymentProperties properties(DeploymentProperties.WorkspaceConfig workspace) {
    return new DeploymentProperties(
        false, null, null, null, null, 0, null, null, null, null, workspace);
  }
}
