package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ProviderDispatchGuardCoverageTest {

  @Test
  void everyRemainingDirectProviderSpendingPathHasAuthorizationBoundary() throws Exception {
    // RR-392 — the canonical router must delegate to the SHARED authorization component, not
    // re-implement the kill switch and reservation guard from its own @Value fields.
    assertThat(source("agent/LlmRouter.java"))
        .contains("ProviderDispatchAuthorization")
        .contains("providerDispatchAuthorization.authorize(");
    assertThat(source("project/rag/EmbeddingService.java"))
        .contains("providerDispatchAuthorization.authorize(");
  }

  @Test
  void nativeToolsCompatibilityRoutesRemainProviderInertUntilDurableAuthorityExists()
      throws Exception {
    String source = source("test/NativeToolsTestController.java");

    assertThat(source)
        .contains("LIVE_PROVIDER_SMOKE_DISABLED")
        .contains("PLATFORM_INTERNAL")
        .doesNotContain(
            "OpenAiNativeToolClient",
            "AnthropicChatModel",
            "GoogleGenAiChatModel",
            "ProviderDispatchAuthorization",
            "import com.navneet.executespec.agent.LlmRouter",
            "private final LlmRouter",
            ".call(");
  }

  @Test
  void retiredBatchProviderPackageHasNoProductionJavaBeans() throws Exception {
    Path batchPackage = Path.of("src/main/java/com/navneet/executespec/batch");
    try (var files = Files.list(batchPackage)) {
      assertThat(files.filter(path -> path.toString().endsWith(".java")).toList())
          .as("retired batch implementation must not be a Spring/runtime surface")
          .isEmpty();
    }
  }

  @Test
  void retiredCompatibilityRouterDoesNotReturn() {
    assertThat(Path.of("src/main/java/com/navneet/executespec/agent/FallbackLlmRouter.java"))
        .as("ExecuteSpec 2.0 must have one canonical provider router")
        .doesNotExist();
  }

  private static String source(String file) throws Exception {
    return Files.readString(Path.of("src/main/java/com/navneet/executespec").resolve(file));
  }
}
