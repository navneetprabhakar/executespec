package com.navneet.executespec.deployment.preview.vm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * RR-1040 (TTL-extension validation) + RR-1049 (untrusted remote deploy-script output). The remote
 * deploy script runs on a VM whose output is not trusted for URL/identity, and the extend API used
 * to accept any integer for any status.
 */
class PreviewDeploymentServiceValidationTest {

  private PreviewDeploymentService service() {
    return new PreviewDeploymentService(
        mock(PreviewVmProperties.class),
        mock(PreviewRegistryRepository.class),
        mock(PreviewMetricRepository.class),
        new ObjectMapper());
  }

  // ---- RR-1049 ----

  @Test
  void validateAndPinResultPinsUrlAndIdToServerDerivedValues() {
    PreviewVmResult scriptOutput =
        new PreviewVmResult(
            "http://169.254.169.254/latest/meta-data", // attacker-influenced URL (metadata)
            8080,
            "forged-preview-id",
            "10.0.0.5",
            null,
            null,
            true);

    PreviewDeploymentService.validateAndPinResult(
        scriptOutput, "real-preview-id", "https://real-preview-id.preview.executespec.ai");

    assertThat(scriptOutput.getUrl()).isEqualTo("https://real-preview-id.preview.executespec.ai");
    assertThat(scriptOutput.getPreviewId()).isEqualTo("real-preview-id");
    assertThat(scriptOutput.getVmIp()).isNull();
  }

  @Test
  void validateAndPinResultRejectsOutOfRangePort() {
    PreviewVmResult bad =
        new PreviewVmResult(
            "https://x.preview.executespec.ai", 70000, "id", null, null, null, true);

    assertThatThrownBy(
            () ->
                PreviewDeploymentService.validateAndPinResult(
                    bad, "id", "https://id.preview.executespec.ai"))
        .isInstanceOf(PreviewDeploymentException.class)
        .hasMessageContaining("out-of-range port");
  }

  // ---- RR-1040 ----

  @Test
  void extendRejectsNonPositiveMinutes() {
    assertThatThrownBy(() -> service().extend("p1", 0))
        .isInstanceOf(PreviewDeploymentException.class)
        .hasMessageContaining("between 1 and");
  }

  @Test
  void extendRejectsAboveCeiling() {
    assertThatThrownBy(
            () -> service().extend("p1", PreviewDeploymentService.MAX_EXTEND_MINUTES + 1))
        .isInstanceOf(PreviewDeploymentException.class)
        .hasMessageContaining("between 1 and");
  }
}
