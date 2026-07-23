package com.navneet.executespec.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DevOAuthProfileContractTest {

  @Test
  void devProfileDeclaresEnvironmentBackedOAuthRegistrations() throws IOException {
    String yaml = resource("application-dev.yml");

    assertThat(yaml).contains("client-id: ${GOOGLE_OAUTH_CLIENT_ID:none}");
    assertThat(yaml).contains("client-secret: ${GOOGLE_OAUTH_CLIENT_SECRET:none}");
    assertThat(yaml).contains("client-id: ${GITHUB_OAUTH_CLIENT_ID:none}");
    assertThat(yaml).contains("client-secret: ${GITHUB_OAUTH_CLIENT_SECRET:none}");
  }

  @Test
  void deployWorkflowInjectsOAuthSecretsIntoDev() throws IOException {
    String workflow = Files.readString(Path.of(".github/workflows/deploy.yml"));

    assertThat(workflow)
        .contains("if [ \"${{ env.DEPLOY_ENV }}\" = \"dev\" ]; then")
        .contains("DEPLOY_GITHUB_OAUTH_CLIENT_ID: ${{ secrets.OAUTH_GITHUB_CLIENT_ID }}")
        .contains("DEPLOY_GITHUB_OAUTH_CLIENT_SECRET: ${{ secrets.OAUTH_GITHUB_CLIENT_SECRET }}")
        .contains("append_secret GOOGLE_OAUTH_CLIENT_ID \"$DEPLOY_GOOGLE_OAUTH_CLIENT_ID\"")
        .contains("append_secret GOOGLE_OAUTH_CLIENT_SECRET \"$DEPLOY_GOOGLE_OAUTH_CLIENT_SECRET\"")
        .contains("append_secret GITHUB_OAUTH_CLIENT_ID \"$DEPLOY_GITHUB_OAUTH_CLIENT_ID\"")
        .contains(
            "append_secret GITHUB_OAUTH_CLIENT_SECRET \"$DEPLOY_GITHUB_OAUTH_CLIENT_SECRET\"");
  }

  @Test
  void productionAndBenchmarkProfilesDoNotDeclareSocialLogin() throws IOException {
    assertThat(resource("application-prod.yml"))
        .doesNotContain("GOOGLE_OAUTH_CLIENT_ID", "GITHUB_OAUTH_CLIENT_ID");
    assertThat(resource("application-benchmark.yml"))
        .doesNotContain("GOOGLE_OAUTH_CLIENT_ID", "GITHUB_OAUTH_CLIENT_ID");
  }

  private String resource(String name) throws IOException {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
      assertThat(input).as("classpath resource %s", name).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
