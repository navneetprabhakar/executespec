package com.navneet.executespec.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NativeToolsTestControllerDispatchAuthorizationTest {

  @Test
  void directNativeSmokeFailsClosedWithoutAProviderTransport() {
    NativeToolsTestController controller = new NativeToolsTestController();

    var result = controller.testNativeTools("openai", null, "smoke-run");

    assertThat(result.get("ok")).isEqualTo(false);
    assertThat(result.get("disabled")).isEqualTo(true);
    assertThat(result.get("provider")).isEqualTo("openai");
    assertThat(result.get("runId")).isEqualTo("smoke-run");
    assertThat(result.get("error").toString())
        .contains("canonical PLATFORM_INTERNAL operation authority");
  }

  @Test
  void nativeMatrixSmokeIsProviderFreeAndExecutesNoRows() {
    var result = new NativeToolsTestController().testAll("matrix-run");

    assertThat(result)
        .containsEntry("ok", false)
        .containsEntry("disabled", true)
        .containsEntry("runId", "matrix-run")
        .containsEntry("totalTested", 0)
        .containsEntry("passed", 0)
        .containsEntry("failed", 0);
  }
}
