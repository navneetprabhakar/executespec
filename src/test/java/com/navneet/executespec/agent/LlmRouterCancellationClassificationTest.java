package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class LlmRouterCancellationClassificationTest {

  @Test
  void wrappedInterruptIsNotRetriedAsTransientNetworkFailure() {
    RuntimeException cancelled =
        new ResourceAccessException("I/O error", new java.io.InterruptedIOException("cancelled"));

    assertThat(LlmRouter.isTransientError(cancelled)).isFalse();
  }

  @Test
  void ordinaryResourceFailureRemainsRetryable() {
    assertThat(LlmRouter.isTransientError(new ResourceAccessException("connection reset")))
        .isTrue();
  }
}
