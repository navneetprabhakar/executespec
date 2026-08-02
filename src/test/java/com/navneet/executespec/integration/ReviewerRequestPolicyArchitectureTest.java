package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;

class ReviewerRequestPolicyArchitectureTest {

  @Test
  void everyBuildReviewerEntrypointUsesTheReadOnlyPolicyBuilder() throws Exception {
    for (String source :
        new String[] {
          "src/main/java/com/navneet/executespec/integration/RunLifecycleService.java",
          "src/main/java/com/navneet/executespec/integration/RunOrchestrator.java"
        }) {
      assertThat(Files.readString(Path.of(source)))
          .as("raw reviewer builder bypass in %s", source)
          .doesNotContain("ReviewerRequest.builder()");
    }

    assertThat(
            InitialReviewerRequestPolicy.builder()
                .provider("deepseek")
                .model("deepseek-chat")
                .context(
                    new ExecutionContext(
                        "run-policy", "review", Path.of(".").toAbsolutePath().normalize()))
                .build()
                .readOnly())
        .isTrue();
  }
}
