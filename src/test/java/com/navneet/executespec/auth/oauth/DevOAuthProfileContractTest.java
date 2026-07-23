package com.navneet.executespec.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DevOAuthProfileContractTest {

  @Test
  void devProfileUsesBootSafeOAuthFallbacks() throws IOException {
    String yaml = resource("application-dev.yml");

    assertThat(yaml).contains("client-id: ${GOOGLE_OAUTH_CLIENT_ID:none}");
    assertThat(yaml).contains("client-secret: ${GOOGLE_OAUTH_CLIENT_SECRET:none}");
    assertThat(yaml).contains("client-id: ${GITHUB_OAUTH_CLIENT_ID:none}");
    assertThat(yaml).contains("client-secret: ${GITHUB_OAUTH_CLIENT_SECRET:none}");
  }

  private String resource(String name) throws IOException {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
      assertThat(input).as("classpath resource %s", name).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
