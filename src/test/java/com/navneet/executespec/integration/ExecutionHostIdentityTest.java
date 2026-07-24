package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExecutionHostIdentityTest {

  @Test
  void explicitIdentityWins() {
    assertThat(ExecutionHostIdentity.resolve("node-override", "pod-1", "machine-1", "host", "1@h"))
        .isEqualTo("node-override");
  }

  @Test
  void hostnameEnvironmentPreservesContainerIdentity() {
    assertThat(ExecutionHostIdentity.resolve(null, "pod-1", "machine-1", "host", "1@h"))
        .isEqualTo("pod-1");
  }

  @Test
  void machineIdentityIsStableWhenSystemdDoesNotExportHostname() {
    assertThat(ExecutionHostIdentity.resolve(null, null, " gce-machine-id\n", "host", "1@h"))
        .isEqualTo("machine:gce-machine-id");
  }

  @Test
  void runtimeFallbackDropsTheProcessId() {
    assertThat(ExecutionHostIdentity.resolve(null, null, null, null, "1092169@dev-vm"))
        .isEqualTo("dev-vm");
  }
}
