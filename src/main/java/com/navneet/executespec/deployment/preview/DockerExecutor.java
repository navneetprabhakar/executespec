package com.navneet.executespec.deployment.preview;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import com.navneet.executespec.testgen.BoundedProcess;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class DockerExecutor {

  private static final int BUILD_TIMEOUT_SECONDS = 300;

  public record DockerBuildResult(boolean success, String imageId, String logs, long durationMs) {}

  private record ProcessResult(int exitCode, String output, boolean timedOut) {}

  /** Check if Docker daemon is available. */
  public boolean isDockerAvailable() {
    try {
      var result =
          exec(
              List.of("docker", "info", "--format", "{{.ServerVersion}}"),
              Duration.ofSeconds(5),
              null);
      return result.exitCode() == 0 && !result.timedOut();
    } catch (Exception e) {
      log.debug("Docker not available: {}", e.getMessage());
      return false;
    }
  }

  /** Build a Docker image from a context directory. */
  public DockerBuildResult build(Path contextDir, String tag) {
    long start = System.currentTimeMillis();
    try {
      var result =
          exec(
              List.of("docker", "build", "--platform", "linux/amd64", "-t", tag, "."),
              Duration.ofSeconds(BUILD_TIMEOUT_SECONDS),
              contextDir);

      long durationMs = System.currentTimeMillis() - start;
      String imageId = parseImageId(result.output());

      if (result.timedOut()) {
        return new DockerBuildResult(
            false,
            null,
            result.output() + "\n[BUILD TIMED OUT after " + BUILD_TIMEOUT_SECONDS + "s]",
            durationMs);
      }

      return new DockerBuildResult(result.exitCode() == 0, imageId, result.output(), durationMs);
    } catch (Exception e) {
      long durationMs = System.currentTimeMillis() - start;
      return new DockerBuildResult(false, null, "Build exception: " + e.getMessage(), durationMs);
    }
  }

  /**
   * Run a container in detached mode with resource limits. Returns the container ID.
   *
   * <p>RR-157: the direct Docker path used to pass only CPU/memory/port, so previewed customer code
   * ran with the full default capability set and no PID ceiling — materially weaker than the
   * Compose path next to it. The flags below bring the two paths to the same floor:
   *
   * <ul>
   *   <li>{@code --cap-drop ALL} and {@code --security-opt no-new-privileges} — no ambient
   *       capabilities, no setuid escalation inside the container.
   *   <li>{@code --pids-limit} — a fork bomb exhausts its own container, not the host.
   *   <li>{@code --add-host} entries pinning the cloud metadata <em>names</em> AND the link-local
   *       address string to loopback, matching {@code ComposeFileGenerator} exactly. Docker's
   *       default seccomp profile is also applied because this call never passes {@code
   *       seccomp=unconfined}.
   * </ul>
   *
   * <p>Non-root is enforced at the image layer, not here: {@code DevDockerfileGenerator} injects a
   * {@code USER} directive (app / nginx) into the generated WEB Dockerfiles, so forcing {@code
   * --user} at run time (which would collide with the image's own uid/gid ownership of the app dir
   * and break writes) is neither needed nor safe.
   *
   * <p><b>Deliberately NOT added, because each would break real previews or needs host infra —
   * reported, not silently dropped (RR-157 remainder):</b>
   *
   * <ul>
   *   <li><b>Raw-IP egress to the metadata server (169.254.169.254) is NOT blocked.</b> An {@code
   *       /etc/hosts} entry only rewrites a <em>name</em> lookup; a socket opened to the numeric IP
   *       never consults it, so the {@code --add-host} for the IP string (like Compose's) is parity
   *       theatre, not a control. Actually denying that IP requires a host firewall rule, a
   *       dedicated egress-denied preview network, or a separate preview VM/worker boundary — none
   *       of which a {@code docker run} flag can supply, and a blanket {@code --internal} network
   *       would break previews that legitimately call external APIs at runtime.
   *   <li><b>Read-only root filesystem ({@code --read-only}).</b> The generated node dev-server
   *       previews (vite/next) write cache and build output back into their {@code /app} WORKDIR on
   *       the image rootfs, so a read-only root breaks the common preview outright. A durable fix
   *       stages an allowlisted context onto named writable volumes.
   *   <li><b>A custom (tightened) seccomp profile.</b> The Docker default profile is already
   *       active; a stricter custom profile needs a shipped, tested artifact and risks breaking
   *       legitimate syscalls in arbitrary customer runtimes.
   * </ul>
   */
  public String run(
      String imageTag,
      String containerName,
      int hostPort,
      int containerPort,
      String cpuLimit,
      String memoryLimit) {
    var result =
        exec(
            List.of(
                "docker",
                "run",
                "-d",
                "--name",
                containerName,
                "--cpus=" + cpuLimit,
                "--memory=" + memoryLimit,
                "--pids-limit",
                "512",
                "--cap-drop",
                "ALL",
                "--security-opt",
                "no-new-privileges",
                "--add-host",
                "metadata.google.internal:127.0.0.1",
                "--add-host",
                "metadata:127.0.0.1",
                "--add-host",
                "169.254.169.254:127.0.0.1",
                "-p",
                hostPort + ":" + containerPort,
                imageTag),
            Duration.ofSeconds(30),
            null);

    if (result.exitCode() != 0) {
      throw new PreviewException("Docker run failed: " + result.output().trim());
    }
    return result.output().trim();
  }

  /** Stop and remove a container. Idempotent — swallows all exceptions. */
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
   * Retrieve container logs. If tailLines > 0, returns the last N lines (docker logs --tail N). If
   * tailLines <= 0, returns ALL logs from container start.
   */
  public String logs(String containerName, int tailLines) {
    try {
      List<String> cmd =
          tailLines > 0
              ? List.of("docker", "logs", "--tail", String.valueOf(tailLines), containerName)
              : List.of("docker", "logs", containerName);
      var result = exec(cmd, Duration.ofSeconds(5), null);
      return (result.exitCode() == 0) ? result.output() : "";
    } catch (Exception e) {
      log.debug("Failed to get logs for {}: {}", containerName, e.getMessage());
      return "";
    }
  }

  /** Check if a container is currently running. */
  public boolean isRunning(String containerName) {
    try {
      var result =
          exec(
              List.of("docker", "inspect", "-f", "{{.State.Running}}", containerName),
              Duration.ofSeconds(5),
              null);
      return "true".equals(result.output().trim());
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * List all containers with names starting with "preview-". Used for reconciliation on JVM
   * restart.
   */
  public List<String> listPreviewContainers() {
    try {
      var result =
          exec(
              List.of("docker", "ps", "--filter", "name=preview-", "--format", "{{.Names}}"),
              Duration.ofSeconds(5),
              null);
      if (result.exitCode() != 0 || result.output().isBlank()) {
        return List.of();
      }
      return Arrays.stream(result.output().trim().split("\n")).filter(s -> !s.isBlank()).toList();
    } catch (Exception e) {
      log.warn("Failed to list preview containers: {}", e.getMessage());
      return List.of();
    }
  }

  /** Inspect a container's mapped host port for a given container port. */
  public int inspectHostPort(String containerName, int containerPort) {
    try {
      var result =
          exec(
              List.of(
                  "docker",
                  "inspect",
                  "-f",
                  "{{(index (index .NetworkSettings.Ports \""
                      + containerPort
                      + "/tcp\") 0).HostPort}}",
                  containerName),
              Duration.ofSeconds(5),
              null);

      if (result.exitCode() == 0 && !result.output().isBlank()) {
        return Integer.parseInt(result.output().trim());
      }
    } catch (Exception e) {
      log.debug("Failed to inspect host port for {}: {}", containerName, e.getMessage());
    }
    return -1;
  }

  private ProcessResult exec(List<String> command, Duration timeout, Path workDir) {
    try {
      var pb = new ProcessBuilder(command);
      pb.redirectErrorStream(true);
      pb.environment().putAll(System.getenv());
      if (workDir != null) {
        pb.directory(workDir.toFile());
      }

      // RR-155: the previous drain used an unbounded ByteArrayOutputStream and only truncated after
      // the child exited, so a container emitting gigabytes of logs could exhaust the heap before
      // the 1 MB limit applied. BoundedProcess drains on a separate thread (timeout stays
      // reachable)
      // AND caps the retained output while reading, destroying the whole tree on expiry.
      BoundedProcess.Outcome outcome = BoundedProcess.run(pb, timeout);
      return new ProcessResult(outcome.exitCode(), outcome.output(), !outcome.finished());
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new PreviewException("Process execution failed: " + String.join(" ", command), e);
    }
  }

  /**
   * Parse Docker image ID from build output. Looks for "Successfully built <id>" or "writing image
   * sha256:<id>".
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
        return end > 0
            ? after.substring(0, Math.min(end, 12))
            : after.substring(0, Math.min(after.length(), 12));
      }
    }
    return null;
  }
}
