package com.navneet.executespec.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeneratedSecretScannerTest {

  private final GeneratedSecretScanner scanner = new GeneratedSecretScanner();

  @Test
  void detectsCredentialWithoutReturningItsValue() {
    String secret = "github_pat_" + "A1".repeat(25);

    var finding = scanner.scan("TOKEN=" + secret);

    assertThat(finding).isPresent();
    assertThat(finding.orElseThrow().category()).isEqualTo("GITHUB_TOKEN");
    assertThat(finding.toString()).doesNotContain(secret);
  }

  @Test
  void detectsPrivateKeyMaterial() {
    assertThat(scanner.scan("-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----"))
        .isPresent();
  }

  @Test
  void permitsOrdinaryCodeAndExplicitPlaceholder() {
    assertThat(scanner.scan("const password = process.env.DB_PASSWORD;")).isEmpty();
    assertThat(scanner.scan("OPENAI_API_KEY=sk-placeholder_" + "x".repeat(40))).isEmpty();
  }
}
