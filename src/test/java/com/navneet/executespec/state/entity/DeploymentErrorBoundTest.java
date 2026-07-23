package com.navneet.executespec.state.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * RR-699 — {@code markFailed} must bound the provider/build error to the persisted {@code
 * VARCHAR(2048)} column so the terminal FAILED transition cannot itself throw a length violation
 * and strand the deployment in an in-flight state.
 */
class DeploymentErrorBoundTest {

  @Test
  void markFailed_boundsOversizedError() {
    Deployment d = Deployment.builder().build();
    d.markFailed("x".repeat(10_000));

    assertThat(d.getStatus()).isEqualTo(Deployment.DeployStatus.FAILED);
    assertThat(d.getErrorMessage()).hasSizeLessThanOrEqualTo(Deployment.ERROR_MESSAGE_MAX);
    assertThat(d.getErrorMessage()).endsWith("[truncated]");
  }

  @Test
  void markFailed_keepsShortErrorVerbatim() {
    Deployment d = Deployment.builder().build();
    d.markFailed("boom: exit 1");

    assertThat(d.getErrorMessage()).isEqualTo("boom: exit 1");
  }

  @Test
  void markFailed_toleratesNull() {
    Deployment d = Deployment.builder().build();
    d.markFailed(null);

    assertThat(d.getStatus()).isEqualTo(Deployment.DeployStatus.FAILED);
    assertThat(d.getErrorMessage()).isNull();
  }
}
