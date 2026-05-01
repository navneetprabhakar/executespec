package com.navneet.executespec.deployment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ArtifactRegistryClient {

  private static final int BUILD_LOG_MAX_CHARS = 8000;

  private final DeploymentProperties props;

  public ArtifactRegistryClient(DeploymentProperties props) {
    this.props = props;
  }

  /**
   * Build Docker image locally.
   *
   * @return full image URI (used as the tag)
   */
  public BuildResult buildImage(Path workspacePath, String versionTag)
      throws IOException, InterruptedException {

    String imageTag = fullImageUri(versionTag);

    ProcessBuilder pb =
        new ProcessBuilder("docker", "build", "--platform", "linux/amd64", "-t", imageTag, ".")
            .directory(workspacePath.toFile())
            .redirectErrorStream(true);

    Process process = pb.start();
    String output = captureOutput(process);

    boolean finished = process.waitFor(props.docker().buildTimeoutSeconds(), TimeUnit.SECONDS);

    if (!finished) {
      process.destroyForcibly();
      throw new IOException(
          "Docker build timed out after " + props.docker().buildTimeoutSeconds() + "s");
    }

    if (process.exitValue() != 0) {
      if (output.contains("Cannot connect to the Docker daemon")) {
        throw new IOException("Docker is not running. Start Docker Desktop and retry.");
      }
      throw new IOException(
          "Docker build failed (exit " + process.exitValue() + "): " + tailOf(output, 500));
    }

    log.info("Docker image built: {}", imageTag);
    return new BuildResult(imageTag, output);
  }

  /** Push image to Artifact Registry. */
  public void pushImage(String imageTag) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder("docker", "push", imageTag).redirectErrorStream(true);

    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes()).trim();
    boolean finished = process.waitFor(10, TimeUnit.MINUTES);

    if (!finished) {
      process.destroyForcibly();
      throw new IOException("Docker push timed out after 10 minutes for " + imageTag);
    }

    if (process.exitValue() != 0) {
      throw new IOException(
          "Docker push failed (exit " + process.exitValue() + "): " + tailOf(output, 500));
    }
    log.info("Image pushed: {}", imageTag);
  }

  /** Full image URI: {host}/{project}/{repo}/{tag} */
  public String fullImageUri(String versionTag) {
    return "%s/%s/%s/%s"
        .formatted(
            props.artifactRegistry().host(),
            props.gcpProject(),
            props.artifactRegistry().repository(),
            versionTag);
  }

  private String captureOutput(Process process) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append("\n");
        if (sb.length() > BUILD_LOG_MAX_CHARS * 2) {
          sb.delete(0, sb.length() - BUILD_LOG_MAX_CHARS);
        }
      }
    }
    return sb.length() > BUILD_LOG_MAX_CHARS
        ? sb.substring(sb.length() - BUILD_LOG_MAX_CHARS)
        : sb.toString();
  }

  private String tailOf(String s, int maxLen) {
    return s.length() <= maxLen ? s : "..." + s.substring(s.length() - maxLen);
  }

  public record BuildResult(String imageTag, String buildLog) {}
}
