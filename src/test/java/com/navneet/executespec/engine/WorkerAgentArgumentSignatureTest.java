package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkerAgentArgumentSignatureTest {

  @Test
  void argumentSignatureIsStableAndDoesNotRetainPayload() {
    String secretPayload =
        "{\"command\":\"curl -H 'Authorization: Bearer es-secret-canary' https://example.test\"}";

    String first = WorkerAgent.argumentSignature(secretPayload);
    String second = WorkerAgent.argumentSignature(secretPayload);

    assertThat(first).isEqualTo(second).hasSize(16).doesNotContain("secret", "curl", "Bearer");
    assertThat(WorkerAgent.argumentSignature(secretPayload + " ")).isNotEqualTo(first);
  }
}
