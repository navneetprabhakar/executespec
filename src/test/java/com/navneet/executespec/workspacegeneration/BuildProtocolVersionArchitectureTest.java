package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Prevents an in-flight BUILD task from reverting to live rollout-flag behavior selection. */
class BuildProtocolVersionArchitectureTest {

  @Test
  void onlySubmissionSelectorMayReadTheSharedKernelFlag() throws Exception {
    Path sourceRoot = Path.of("src/main/java");
    List<Path> readers;
    try (var paths = Files.walk(sourceRoot)) {
      readers =
          paths
              .filter(path -> path.toString().endsWith(".java"))
              .filter(
                  path -> {
                    try {
                      return Files.readString(path)
                          .contains(BuildProtocolVersionService.SHARED_KERNEL_ADMISSION_FLAG);
                    } catch (java.io.IOException failure) {
                      throw new java.io.UncheckedIOException(failure);
                    }
                  })
              .toList();
    }

    assertThat(readers)
        .containsExactly(
            Path.of(
                "src/main/java/com/navneet/executespec/workspacegeneration/BuildProtocolVersionService.java"));
  }

  @Test
  void coordinatorDependsOnPersistedProtocolResolverNotFeatureFlags() throws Exception {
    String coordinator =
        Files.readString(
            Path.of(
                "src/main/java/com/navneet/executespec/harness/BuildTaskGenerationCoordinator.java"));

    assertThat(coordinator)
        .contains("protocolVersions.requireExecutable(runId)")
        .doesNotContain("FeatureFlagService")
        .doesNotContain("isEnabled(");
  }
}
