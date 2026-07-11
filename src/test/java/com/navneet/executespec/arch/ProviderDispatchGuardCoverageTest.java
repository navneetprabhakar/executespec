package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class ProviderDispatchGuardCoverageTest {

  @Test
  void everyKnownDirectProviderSpendingPathHasAuthorizationBoundary() throws Exception {
    for (String file :
        List.of(
            "test/NativeToolsTestController.java",
            "batch/AnthropicBatchClient.java",
            "batch/OpenAiBatchClient.java",
            "batch/GeminiBatchClient.java")) {
      String source =
          Files.readString(Path.of("src/main/java/com/navneet/executespec").resolve(file));
      assertThat(source)
          .as("direct provider path %s", file)
          .contains("ProviderDispatchAuthorization")
          .contains("dispatchAuthorization.authorize(");
    }

    assertThat(source("agent/LlmRouter.java")).contains("ProviderTestBudgetGuard.authorize(");
  }

  private static String source(String file) throws Exception {
    return Files.readString(Path.of("src/main/java/com/navneet/executespec").resolve(file));
  }
}
