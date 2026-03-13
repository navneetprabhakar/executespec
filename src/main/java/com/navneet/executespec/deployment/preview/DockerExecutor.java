package com.navneet.executespec.deployment.preview;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class DockerExecutor {

    private static final int MAX_OUTPUT_BYTES = 1_048_576; // 1 MB

    private final PreviewProperties props;

    // ── Inner record ────────────────────────────────────────────────────────

    public record DockerBuildResult(boolean success, String imageId, String logs, long durationMs) {}

    // ── Private helper record ───────────────────────────────────────────────

    private record ProcessResult(int exitCode, String output, boolean timedOut) {}

    // ── Public methods ──────────────────────────────────────────────────────

    /**
     * Check if Docker daemon is available.
     */
    public boolean isDockerAvailable() {
        try {
            var result = exec(List.of("docker", "info", "--format", "{{.ServerVersion}}"),
                    Duration.ofSeconds(5), null);
            return result.exitCode() == 0 && !result.timedOut();
        } catch (Exception e) {
            log.debug("Docker not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Build a Docker image from a context directory.
     */
    public DockerBuildResult build(Path contextDir, String tag) {
        long start = System.currentTimeMillis();
        try {
            var result = exec(
                    List.of("docker", "build", "-t", tag, "."),
                    Duration.ofSeconds(props.getBuildTimeoutSeconds()),
                    contextDir);

            long durationMs = System.currentTimeMillis() - start;
            String imageId = parseImageId(result.output());

            if (result.timedOut()) {
                return new DockerBuildResult(false, null,
                        result.output() + "\n[BUILD TIMED OUT after " + props.getBuildTimeoutSeconds() + "s]",
                        durationMs);
            }

            return new DockerBuildResult(result.exitCode() == 0, imageId, result.output(), durationMs);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            return new DockerBuildResult(false, null, "Build exception: " + e.getMessage(), durationMs);
        }
    }

    /**
     * Run a container in detached mode with resource limits.
     * Returns the container ID.
     */
    public String run(String imageTag, String containerName, int hostPort, int containerPort,
                      String cpuLimit, String memoryLimit) {
        var result = exec(List.of(
                "docker", "run", "-d",
                "--name", containerName,
                "--cpus=" + cpuLimit,
                "--memory=" + memoryLimit,
                "-p", hostPort + ":" + containerPort,
                imageTag
        ), Duration.ofSeconds(30), null);

        if (result.exitCode() != 0) {
            throw new PreviewException("Docker run failed: " + result.output().trim());
        }
        return result.output().trim();
    }

    /**
     * Stop and remove a container. Idempotent — swallows all exceptions.
     */
    public void stop(String containerName) {
        try {
            exec(List.of("docker", "stop", containerName, "--time", "5"), Duration.ofSeconds(15), null);
        } catch (Exception e) {
            log.warn("docker stop {} failed (non-fatal): {}", containerName, e.getMessage());
        }
        try {
            exec(List.of("docker", "rm", "-f", containerName), Duration.ofSeconds(10), null);
        } catch (Exception e) {
            log.warn("docker rm {} failed (non-fatal): {}", containerName, e.getMessage());
        }
    }

    /**
     * Retrieve container logs (tail N lines).
     */
    public String logs(String containerName, int tailLines) {
        try {
            var result = exec(
                    List.of("docker", "logs", "--tail", String.valueOf(tailLines), containerName),
                    Duration.ofSeconds(5), null);
            return result.output();
        } catch (Exception e) {
            log.debug("Failed to get logs for {}: {}", containerName, e.getMessage());
            return "";
        }
    }

    /**
     * Check if a container is currently running.
     */
    public boolean isRunning(String containerName) {
        try {
            var result = exec(
                    List.of("docker", "inspect", "-f", "{{.State.Running}}", containerName),
                    Duration.ofSeconds(5), null);
            return "true".equals(result.output().trim());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * List all containers with names starting with "preview-".
     * Used for reconciliation on JVM restart.
     */
    public List<String> listPreviewContainers() {
        try {
            var result = exec(
                    List.of("docker", "ps", "--filter", "name=preview-", "--format", "{{.Names}}"),
                    Duration.ofSeconds(5), null);
            if (result.exitCode() != 0 || result.output().isBlank()) {
                return List.of();
            }
            return Arrays.stream(result.output().trim().split("\n"))
                    .filter(s -> !s.isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to list preview containers: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Inspect a container's mapped host port for a given container port.
     */
    public int inspectHostPort(String containerName, int containerPort) {
        try {
            var result = exec(List.of(
                    "docker", "inspect", "-f",
                    "{{(index (index .NetworkSettings.Ports \"" + containerPort + "/tcp\") 0).HostPort}}",
                    containerName
            ), Duration.ofSeconds(5), null);

            if (result.exitCode() == 0 && !result.output().isBlank()) {
                return Integer.parseInt(result.output().trim());
            }
        } catch (Exception e) {
            log.debug("Failed to inspect host port for {}: {}", containerName, e.getMessage());
        }
        return -1;
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private ProcessResult exec(List<String> command, Duration timeout, Path workDir) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.environment().putAll(System.getenv());
            if (workDir != null) {
                pb.directory(workDir.toFile());
            }

            Process process = pb.start();

            // Read output in bounded fashion
            String output;
            try (InputStream is = process.getInputStream()) {
                byte[] buf = is.readNBytes(MAX_OUTPUT_BYTES);
                output = new String(buf);
            }

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessResult(-1, output, true);
            }

            return new ProcessResult(process.exitValue(), output, false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PreviewException("Process execution failed: " + String.join(" ", command), e);
        }
    }

    /**
     * Parse Docker image ID from build output.
     * Looks for "Successfully built <id>" or "writing image sha256:<id>".
     */
    private String parseImageId(String output) {
        if (output == null) return null;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Successfully built ")) {
                return trimmed.substring("Successfully built ".length()).trim();
            }
            // BuildKit format
            int idx = trimmed.indexOf("writing image sha256:");
            if (idx >= 0) {
                String after = trimmed.substring(idx + "writing image sha256:".length());
                // Take first 12 chars or up to space
                int end = after.indexOf(' ');
                return end > 0 ? after.substring(0, Math.min(end, 12)) : after.substring(0, Math.min(after.length(), 12));
            }
        }
        return null;
    }
}
