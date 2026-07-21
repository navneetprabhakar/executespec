package com.navneet.executespec.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveWorkspacePathPolicyTest {

  @Test
  void blocksCredentialMaterialButAllowsConfigurationTemplatesAndSource() {
    assertThat(SensitiveWorkspacePathPolicy.isSensitive(".env")).isTrue();
    assertThat(SensitiveWorkspacePathPolicy.isSensitive("config/.env.prod")).isTrue();
    assertThat(SensitiveWorkspacePathPolicy.isSensitive("secrets/service-account.json")).isTrue();
    assertThat(SensitiveWorkspacePathPolicy.isSensitive("keys/id_ed25519")).isTrue();
    assertThat(SensitiveWorkspacePathPolicy.isSensitive("certs/client.pem")).isTrue();

    assertThat(SensitiveWorkspacePathPolicy.isSensitive(".env.example")).isFalse();
    assertThat(SensitiveWorkspacePathPolicy.isSensitive("credentials.example.json")).isFalse();
    assertThat(SensitiveWorkspacePathPolicy.isSensitive("src/Credentials.java")).isFalse();
  }
}
