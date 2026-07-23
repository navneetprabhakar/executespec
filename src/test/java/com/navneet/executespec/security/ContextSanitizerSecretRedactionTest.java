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

  // RR-037 (2026-07-22, P1) — the redaction set must cover the provider formats
  // GeneratedSecretScanner already recognizes plus common JWT / Basic-auth / URL-credential shapes.
  @Test
  void redactsStripeSlackJwtBasicAndUrlCredentials() {
    String stripe = sanitizer.sanitizeOutput("key sk_live_ABCDEFGHIJKLMNOPQRSTUVWX");
    assertThat(stripe).doesNotContain("sk_live_ABCDEFGHIJKLMNOPQRSTUVWX").contains("[REDACTED]");

    String slack = sanitizer.sanitizeOutput("token xoxb-123456789012-abcdefghijklmnop");
    assertThat(slack).doesNotContain("xoxb-123456789012-abcdefghijklmnop").contains("[REDACTED]");

    String jwt =
        sanitizer.sanitizeOutput(
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U");
    assertThat(jwt)
        .doesNotContain("dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U")
        .contains("[REDACTED]");

    String basic = sanitizer.sanitizeOutput("Authorization: Basic dXNlcjpzdXBlcnNlY3JldA==");
    assertThat(basic).doesNotContain("dXNlcjpzdXBlcnNlY3JldA==").contains("[REDACTED]");

    String urlCred =
        sanitizer.sanitizeOutput("clone https://alice:hunter2secret@github.com/x/y.git");
    assertThat(urlCred).doesNotContain("alice:hunter2secret@").contains("[REDACTED]");
  }
}
