package com.navneet.executespec.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContextSanitizerSecretRedactionTest {

  private final ContextSanitizer sanitizer = new ContextSanitizer();

  @Test
  void redactsEnterpriseCredentialShapesFromOutputs() {
    String input =
        "es_pat_12345678901234567890 ghp_123456789012345678901234 "
            + "github_pat_123456789012345678901234 AKIA1234567890123456";

    String output = sanitizer.sanitizeOutput(input);

    assertThat(output).doesNotContain("es_pat_", "ghp_", "github_pat_", "AKIA1234");
    assertThat(output).contains("[REDACTED]");
  }

  @Test
  void exceptionTextIsBoundedAndSecretFree() {
    RuntimeException error =
        new RuntimeException("Authorization: Bearer abcdefghijklmnopqrstuvwxyz123456");

    assertThat(sanitizer.sanitizeException(error))
        .doesNotContain("abcdefghijklmnopqrstuvwxyz123456")
        .contains("[REDACTED]");
  }
}
