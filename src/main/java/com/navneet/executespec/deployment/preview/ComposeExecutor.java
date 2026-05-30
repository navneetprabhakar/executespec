package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Docker Compose CLI wrapper for preview containers. Uses V2 syntax (`docker compose`, not
 * `docker-compose`). All operations are project-scoped via `--project-name`.
 */
@Component
@Slf4j
public class ComposeExecutor {

  private static final int MAX_OUTPUT_BYTES = 1_048_576; // 1 MB

  /** Common tool directories that may be missing from the JVM's inherited PATH. */
  private static final String PATH_AUGMENT =
      String.join(
          ":", "/opt/homebrew/bin", "/opt/homebrew/sbin", "/usr/local/bin", "/usr/local/sbin");

  @jakarta.annotation.PostConstruct
  void checkDockerAvailability() {
    if (isAvailable()) {
      log.info("[ComposeExecutor] Docker Compose V2 is available");
    } else {
      log.warn(
          "[ComposeExecutor] Docker Compose V2 NOT found on PATH — Docker previews will fail. "
              + "Ensure Docker Desktop is running and 'docker compose' is on PATH.");
    }
  }

  /**
   * Start all services defined in the compose file. Builds images if needed, runs in detached mode.
   *
   * @param composeFile path to docker-compose.yml
   * @param projectName compose project name (e.g., "es-preview-abc12345")
   * @param timeout maximum time to wait for the up command
   * @return combined stdout+stderr output
   */
  public String up(Path composeFile, String projectName, Duration timeout) {
    var cmd = baseCmd(projectName);
    cmd.addAll(
        List.of(
            "-f",
            composeFile.toAbsolutePath().toString(),
            "up",
            "-d",
            "--build",
            "--remove-orphans"));

    var result = exec(cmd, timeout, composeFile.getParent());
    if (result.exitCode() != 0 && !result.timedOut()) {
      throw new PreviewException(
          "docker compose up failed (exit "
              + result.exitCode()
              + "): "
              + lastNLines(result.output(), 20));
    }
    if (result.timedOut()) {
      throw new PreviewException("docker compose up timed out after " + timeout.toSeconds() + "s");
    }
    return result.output();
  }

  /**
   * Tear down all services, removing volumes and orphan containers. Idempotent — swallows errors if
   * the project doesn't exist.
   */
  public void down(String projectName, boolean removeVolumes) {
    var cmd = baseCmd(projectName);
    cmd.add("down");
    if (removeVolumes) {
      cmd.add("-v");
    }
    cmd.add("--remove-orphans");

    try {
      exec(cmd, Duration.ofSeconds(30), null);
    } catch (Exception e) {
      log.warn("docker compose down for {} failed (non-fatal): {}", projectName, e.getMessage());
    }
  }

  /**
   * Retrieve logs from compose services.
   *
   * @param projectName compose project name
   * @param serviceName specific service (e.g., "app", "db"), or null for all services
   * @param tailLines number of tail lines, or <= 0 for all logs
   * @return combined log output
   */
  public String logs(String projectName, String serviceName, int tailLines) {
    try {
      var cmd = baseCmd(projectName);
      cmd.add("logs");
      cmd.add("--no-color");
      if (tailLines > 0) {
        cmd.add("--tail");
        cmd.add(String.valueOf(tailLines));
      }
      if (serviceName != null && !serviceName.isBlank()) {
        cmd.add(serviceName);
      }

      var result = exec(cmd, Duration.ofSeconds(10), null);
      return result.exitCode() == 0 ? result.output() : "";
    } catch (Exception e) {
      log.debug("Failed to get compose logs for {}: {}", projectName, e.getMessage());
      return "";
    }
  }

  /** Check if any services in the project are running. */
  public boolean isRunning(String projectName) {
    try {
      var cmd = baseCmd(projectName);
      cmd.addAll(List.of("ps", "--format", "json", "-q"));
      var result = exec(cmd, Duration.ofSeconds(5), null);
      return result.exitCode() == 0 && !result.output().isBlank();
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Wait for a specific service to become healthy (based on its healthcheck). Polls `docker compose
   * ps` until the service shows "healthy" status.
   *
   * @param projectName compose project name
   * @param serviceName service to wait for (e.g., "db")
   * @param timeout max wait duration
   */
  public void waitForHealthy(String projectName, String serviceName, Duration timeout) {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      try {
        var cmd = baseCmd(projectName);
        cmd.addAll(List.of("ps", serviceName, "--format", "{{.Health}}"));
        var result = exec(cmd, Duration.ofSeconds(5), null);
        String health = result.output().trim().toLowerCase();
        if (health.contains("healthy") && !health.contains("unhealthy")) {
          log.info("Service {} in project {} is healthy", serviceName, projectName);
          return;
        }
      } catch (Exception e) {
        // Service may not be up yet
      }
      try {
        Thread.sleep(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new PreviewException(
            "Interrupted while waiting for " + serviceName + " to become healthy");
      }
    }
    throw new PreviewException(
        "Service "
            + serviceName
            + " in project "
            + projectName
            + " did not become healthy within "
            + timeout.toSeconds()
            + "s");
  }

  /**
   * List all running compose projects whose names start with the given prefix. Used for orphan
   * reconciliation on JVM restart.
   */
  public List<String> listProjects(String namePrefix) {
    try {
      var cmd = new ArrayList<>(List.of("docker", "compose", "ls", "--format", "table", "-q"));
      var result = exec(cmd, Duration.ofSeconds(5), null);
      if (result.exitCode() != 0 || result.output().isBlank()) {
        return List.of();
      }
      return result
          .output()
          .lines()
          .map(String::trim)
          .filter(name -> !name.isBlank() && name.startsWith(namePrefix))
          .toList();
    } catch (Exception e) {
      log.debug("Failed to list compose projects: {}", e.getMessage());
      return List.of();
    }
  }

  /** Check if Docker Compose V2 is available. */
  public boolean isAvailable() {
    try {
      var result = exec(List.of("docker", "compose", "version"), Duration.ofSeconds(5), null);
      return result.exitCode() == 0 && !result.timedOut();
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Execute a command inside a running compose service container. Uses {@code docker compose exec
   * -T <service> <cmd>} — non-interactive, no TTY.
   *
   * @param projectName compose project name (e.g. "es-preview-abc12345")
   * @param serviceName service to exec into (e.g. "app")
   * @param userCmd command + args to run inside the container
   * @param timeout maximum wall-clock time for the exec
   * @return stdout and stderr merged, plus exit code
   */
  public ExecResult execInService(
      String projectName, String serviceName, List<String> userCmd, Duration timeout) {
    var cmd = baseCmd(projectName);
    cmd.add("exec");
    cmd.add("-T"); // disable TTY — required for non-interactive exec
    cmd.add(serviceName);
    cmd.addAll(userCmd);

    var result = exec(cmd, timeout, null);
    return new ExecResult(result.output(), "", result.exitCode());
  }

  private List<String> baseCmd(String projectName) {
    var cmd = new ArrayList<>(List.of("docker", "compose", "--project-name", projectName));
    return cmd;
  }

  private record ProcessResult(int exitCode, String output, boolean timedOut) {}

  private ProcessResult exec(List<String> command, Duration timeout, Path workDir) {
    try {
      var pb = new ProcessBuilder(command);
      pb.redirectErrorStream(true);
      // SECURITY: Only whitelist safe env vars — never leak API keys or secrets to Docker
      // subprocesses
      Map<String, String> env = pb.environment();
      env.clear();
      var allowedVars =
          List.of(
              "PATH",
              "HOME",
              "USER",
              "LANG",
              "LC_ALL",
              "TZ",
              "TMPDIR",
              "DOCKER_HOST",
              "DOCKER_CONFIG",
              "DOCKER_CERT_PATH",
              "JAVA_HOME",
              "NODE_ENV",
              "HTTP_PROXY",
              "HTTPS_PROXY",
              "NO_PROXY",
              "http_proxy",
              "https_proxy",
              "no_proxy");
      Map<String, String> systemEnv = System.getenv();
      for (String key : allowedVars) {
        if (systemEnv.containsKey(key)) {
          env.put(key, systemEnv.get(key));
        }
      }
      // Augment PATH — JVM may inherit minimal PATH missing docker/node
      String currentPath = env.getOrDefault("PATH", "/usr/bin:/bin");
      env.put("PATH", PATH_AUGMENT + ":" + currentPath);
      if (workDir != null) {
        pb.directory(workDir.toFile());
      }

      Process process = pb.start();

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
      throw new PreviewException(
          "Compose process execution failed: " + String.join(" ", command), e);
    }
  }

  private String lastNLines(String text, int n) {
    if (text == null) return "";
    String[] lines = text.split("\n");
    int start = Math.max(0, lines.length - n);
    var sb = new StringBuilder();
    for (int i = start; i < lines.length; i++) {
      sb.append(lines[i]).append("\n");
    }
    return sb.toString();
  }
}
