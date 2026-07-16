package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.swebench.SwebenchProperties;

import org.junit.jupiter.api.Test;

class BenchmarkGenerationServiceDockerDiagnosticsTest {

  @Test
  void dockerTimeoutIsBoundedAndActionable() {
    String message =
        BenchmarkGenerationService.dockerImageFailure(
            "pull",
            "docker.io/swebench/example:latest",
            new BenchmarkGenerationService.ShellResult(-1, "timeout after 30s"),
            30);

    assertThat(message)
        .contains("docker image pull failed")
        .contains("docker.io/swebench/example:latest")
        .contains("timed out")
        .contains("timeout=30s");
  }

  @Test
  void imageTimeoutDefaultsPreserveHistoricalDeploymentBudgets() {
    SwebenchProperties props = new SwebenchProperties();

    assertThat(props.getImageInspectTimeoutSeconds()).isEqualTo(30);
    assertThat(props.getImagePullTimeoutSeconds()).isEqualTo(1800);
  }

  @Test
  void dockerOutputIsTruncatedToKeepApiErrorsBounded() {
    String message =
        BenchmarkGenerationService.dockerImageFailure(
            "pull",
            "image",
            new BenchmarkGenerationService.ShellResult(1, "x".repeat(2_000)),
            1800);

    assertThat(message).hasSizeLessThan(700);
    assertThat(message).endsWith("…");
  }
}
