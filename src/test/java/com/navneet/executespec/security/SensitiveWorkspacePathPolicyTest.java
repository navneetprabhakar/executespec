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

  // RR-083 (2026-07-22, P1) — common credential stores the original small list omitted.
  @Test
  void blocksCommonCredentialStoresAndDirectoryAwarePaths() {
    assertThat(SensitiveWorkspacePathPolicy.isSensitive("home/dev/.aws/credentials")).isTrue();
    assertThat(
            SensitiveWorkspacePathPolicy.isSensitive(
                "home/dev/.config/gcloud/application_default_credentials.json"))
        .isTrue();
    assertThat(SensitiveWorkspacePathPolicy.isSensitive("home/dev/.kube/config")).isTrue();
    assertThat(SensitiveWorkspacePathPolicy.isSensitive("infra/terraform.tfstate")).isTrue();
    assertThat(SensitiveWorkspacePathPolicy.isSensitive("infra/terraform.tfstate.backup")).isTrue();
    assertThat(SensitiveWorkspacePathPolicy.isSensitive(".vault-token")).isTrue();
    assertThat(SensitiveWorkspacePathPolicy.isSensitive("home/dev/.pgpass")).isTrue();
    assertThat(SensitiveWorkspacePathPolicy.isSensitive("deploy/secrets.yaml")).isTrue();
    assertThat(SensitiveWorkspacePathPolicy.isSensitive("k8s/secret.yml")).isTrue();

    // A bare 'config' file that is NOT under a credential directory stays allowed.
    assertThat(SensitiveWorkspacePathPolicy.isSensitive("app/config")).isFalse();
    assertThat(SensitiveWorkspacePathPolicy.isSensitive("infra/variables.tf")).isFalse();
  }
}
