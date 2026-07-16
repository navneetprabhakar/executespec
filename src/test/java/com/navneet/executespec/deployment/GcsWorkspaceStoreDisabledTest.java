package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GcsWorkspaceStoreDisabledTest {

  @Test
  void disabledConfigurationDoesNotCreateAStorageClient() {
    DeploymentProperties properties =
        new DeploymentProperties(
            false,
            "executespecv1",
            "us-east4",
            null,
            null,
            0,
            "",
            null,
            null,
            null,
            new DeploymentProperties.WorkspaceConfig(0, "", 0, false));

    GcsWorkspaceStore store = new GcsWorkspaceStore(properties, "local");

    assertThat(store.isConfigured()).isFalse();
  }
}
