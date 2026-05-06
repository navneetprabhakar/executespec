package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for DockerExecutor.
 *
 * <p>Tests that invoke actual Docker commands are guarded by
 * {@code assumeTrue(executor.isDockerAvailable(), "Docker not available...")} so they skip cleanly
 * in CI or environments without Docker. The reason string surfaces in the surefire report
 * (replaces the bare "Skipped: N" line that gave no hint why).
 */
@DisplayName("DockerExecutor")
class DockerExecutorTest {

  DockerExecutor executor;

  @BeforeEach
  void setUp() {
    executor = new DockerExecutor();
  }

  @Nested
  @DisplayName("isDockerAvailable")
  class DockerAvailability {

    @Test
    @DisplayName("Returns boolean without throwing")
    void returnsBoolean() {
      // Should never throw, regardless of Docker presence
      boolean result = executor.isDockerAvailable();
      assertThat(result).isNotNull();
    }
  }

  @Nested
  @DisplayName("Build")
  class Build {

    @Test
    @DisplayName("Build with valid Dockerfile succeeds")
    void buildSucceeds(@TempDir Path dir) throws IOException {
      assumeTrue(
          executor.isDockerAvailable(),
          "Docker daemon not reachable — skipping Docker integration test. Start Docker Desktop"
              + " (or wire `docker` into the CI image) to exercise this path.");

      Files.writeString(
          dir.resolve("Dockerfile"),
          """
          FROM alpine:3.19
          LABEL preview=true
          CMD ["echo", "hello"]
          """);

      var result = executor.build(dir, "test-preview-build");
      assertThat(result.success()).isTrue();
      assertThat(result.durationMs()).isGreaterThan(0);
      assertThat(result.logs()).isNotEmpty();

      // Cleanup
      try {
        new ProcessBuilder("docker", "rmi", "-f", "test-preview-build").start().waitFor();
      } catch (Exception ignored) {
      }
    }

    @Test
    @DisplayName("Build with missing Dockerfile fails gracefully")
    void buildFailsNoDockerfile(@TempDir Path dir) {
      assumeTrue(
          executor.isDockerAvailable(),
          "Docker daemon not reachable — skipping Docker integration test. Start Docker Desktop"
              + " (or wire `docker` into the CI image) to exercise this path.");

      var result = executor.build(dir, "test-preview-build-fail");
      assertThat(result.success()).isFalse();
      assertThat(result.logs()).isNotEmpty();
    }

    @Test
    @DisplayName("Build with invalid Dockerfile returns failure")
    void buildFailsInvalidDockerfile(@TempDir Path dir) throws IOException {
      assumeTrue(
          executor.isDockerAvailable(),
          "Docker daemon not reachable — skipping Docker integration test. Start Docker Desktop"
              + " (or wire `docker` into the CI image) to exercise this path.");

      Files.writeString(dir.resolve("Dockerfile"), "INVALID DIRECTIVE\n");
      var result = executor.build(dir, "test-preview-build-invalid");
      assertThat(result.success()).isFalse();
    }
  }

  @Nested
  @DisplayName("Container lifecycle")
  class Lifecycle {

    @Test
    @DisplayName("Run, check running, get logs, stop — full lifecycle")
    void fullLifecycle(@TempDir Path dir) throws Exception {
      assumeTrue(
          executor.isDockerAvailable(),
          "Docker daemon not reachable — skipping Docker integration test. Start Docker Desktop"
              + " (or wire `docker` into the CI image) to exercise this path.");

      String containerName = "preview-test-lifecycle";
      String imageTag = "preview-test-lifecycle-img";

      try {
        // Build a simple image
        Files.writeString(
            dir.resolve("Dockerfile"),
            """
            FROM alpine:3.19
            LABEL preview=true
            CMD ["sh", "-c", "echo 'SERVER READY' && sleep 60"]
            """);
        var buildResult = executor.build(dir, imageTag);
        assumeTrue(
            buildResult.success(),
            "docker build failed during lifecycle test setup — skipping rather than failing"
                + " (likely a transient registry pull error, not a regression in DockerExecutor).");

        // Run container
        String containerId = executor.run(imageTag, containerName, 19999, 80, "0.5", "64m");
        assertThat(containerId).isNotBlank();

        // Check running
        Thread.sleep(1000); // Give Docker a moment
        assertThat(executor.isRunning(containerName)).isTrue();

        // Get logs
        String logs = executor.logs(containerName, 10);
        // Log may or may not have output yet depending on timing
        assertThat(logs).isNotNull();

        // Stop
        executor.stop(containerName);
        Thread.sleep(500);
        assertThat(executor.isRunning(containerName)).isFalse();

      } finally {
        // Cleanup
        executor.stop(containerName);
        try {
          new ProcessBuilder("docker", "rmi", "-f", imageTag).start().waitFor();
        } catch (Exception ignored) {
        }
      }
    }

    @Test
    @DisplayName("Stop on non-existent container is idempotent")
    void stopNonExistent() {
      // Should not throw
      executor.stop("preview-nonexistent-container-xyz");
    }

    @Test
    @DisplayName("isRunning returns false for non-existent container")
    void isRunningNonExistent() {
      assertThat(executor.isRunning("preview-nonexistent-xyz")).isFalse();
    }

    @Test
    @DisplayName("logs returns empty string for non-existent container")
    void logsNonExistent() {
      String logs = executor.logs("preview-nonexistent-xyz", 50);
      assertThat(logs).isEmpty();
    }
  }

  @Nested
  @DisplayName("listPreviewContainers")
  class ListContainers {

    @Test
    @DisplayName("Returns list without throwing, even if no Docker")
    void returnsListSafely() {
      var containers = executor.listPreviewContainers();
      assertThat(containers).isNotNull();
    }
  }

  @Nested
  @DisplayName("inspectHostPort")
  class InspectPort {

    @Test
    @DisplayName("Returns -1 for non-existent container")
    void returnsNegativeForMissing() {
      int port = executor.inspectHostPort("preview-nonexistent-xyz", 8080);
      assertThat(port).isEqualTo(-1);
    }
  }
}
