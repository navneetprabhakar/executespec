package com.navneet.executespec.deployment.preview.engine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds + tags + pushes a preview image to the local/shared registry.
 *
 * <p>Flow (shells out to docker for prod-parity with preview-engine):
 *
 * <pre>
 *   tag = <registryUrl>/preview/<runId>:<workspaceSha>
 *   docker build -f Dockerfile -t <tag> <workspaceDir>
 *   docker push <tag>
 * </pre>
 *
 * <p>Tag is content-addressed on the workspace file tree so identical rebuilds are cheap (registry
 * manifestInspect skips when possible).
 */
@Component
public class PreviewImagePusher {

  private static final Logger log = LoggerFactory.getLogger(PreviewImagePusher.class);

  public record PushResult(String imageRef, boolean skippedBuild) {}

  /**
   * Build the image from the generated Dockerfile + workspace; push to the registry; return the
   * fully-qualified image ref.
   *
   * @param registryUrl host[:port] for the registry
   * @param runId main app run id
   * @param workspace dir containing the Dockerfile + app source
   * @param dockerfile Dockerfile path (relative or absolute)
   */
  public PushResult buildAndPush(
      String registryUrl, String runId, Path workspace, Path dockerfile) {
    if (registryUrl == null || registryUrl.isBlank()) {
      throw new IllegalArgumentException("registryUrl missing");
    }
    String shortSha = workspaceSha(workspace);
    String imageRef = registryUrl + "/preview/" + sanitize(runId) + ":" + shortSha;

    // Idempotency: if the tag already exists in registry, skip build+push.
    if (manifestExists(imageRef)) {
      log.info("[{}] image {} already in registry — skipping build+push", runId, imageRef);
      return new PushResult(imageRef, true);
    }

    log.info("[{}] docker build → {}", runId, imageRef);
    List<String> buildCmd =
        new ArrayList<>(
            List.of(
                "docker",
                "build",
                "-f",
                dockerfile.toAbsolutePath().toString(),
                "-t",
                imageRef,
                "--label",
                "executespec.preview-run=true",
                "--label",
                "executespec.run-id=" + sanitize(runId),
                "--label",
                "executespec.sha=" + shortSha,
                workspace.toAbsolutePath().toString()));
    exec(buildCmd, Duration.ofMinutes(15));

    log.info("[{}] docker push {}", runId, imageRef);
    exec(List.of("docker", "push", imageRef), Duration.ofMinutes(10));

    return new PushResult(imageRef, false);
  }

  // ───────────────────────────── helpers ─────────────────────────────

  private boolean manifestExists(String imageRef) {
    // `docker manifest inspect` doesn't auto-enable for insecure local
    // registries without config; use `docker buildx imagetools` which
    // works uniformly. Failure → assume the image isn't there.
    try {
      exec(List.of("docker", "buildx", "imagetools", "inspect", imageRef), Duration.ofSeconds(20));
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  /**
   * Content-addressed hash of the workspace. Audit 2026-05-12 review item I #58: was hashing (path,
   * size, mtime) — that's not actually content-addressed; two workspaces with the same file sizes +
   * mtimes (e.g. same `cp -p` copy) would collide and cache-poison a stale image. Now hashes (path,
   * SHA-256(content)). Slower but correct.
   */
  private String workspaceSha(Path workspace) {
    try {
      StringBuilder material = new StringBuilder();
      java.nio.file.Files.walk(workspace)
          .filter(java.nio.file.Files::isRegularFile)
          .filter(
              p ->
                  !p.toString().contains("/node_modules/")
                      && !p.toString().contains("/target/")
                      && !p.toString().contains("/__pycache__/")
                      && !p.getFileName().toString().startsWith("."))
          .sorted()
          .forEach(
              p -> {
                try {
                  byte[] contentBytes = java.nio.file.Files.readAllBytes(p);
                  MessageDigest contentMd = MessageDigest.getInstance("SHA-256");
                  byte[] contentHash = contentMd.digest(contentBytes);
                  StringBuilder contentHex = new StringBuilder(contentHash.length * 2);
                  for (byte b : contentHash) contentHex.append(String.format("%02x", b));
                  material
                      .append(workspace.relativize(p))
                      .append(':')
                      .append(contentHex)
                      .append('\n');
                } catch (Exception ignored) {
                  // Best-effort — skip unreadable files so we don't fail the whole hash.
                }
              });
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(material.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) hex.append(String.format("%02x", b));
      return hex.substring(0, 12);
    } catch (Exception e) {
      // Non-determinism as a last resort so we still get SOME tag.
      return Long.toHexString(System.nanoTime()).substring(0, 12);
    }
  }

  private static String sanitize(String s) {
    return s == null ? "unknown" : s.toLowerCase().replaceAll("[^a-z0-9-]", "-");
  }

  private static String exec(List<String> cmd, Duration timeout) {
    try {
      ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
      Process p = pb.start();
      StringBuilder sb = new StringBuilder();
      try (BufferedReader br =
          new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
      }
      if (!p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new RuntimeException("timed out: " + cmd);
      }
      if (p.exitValue() != 0) {
        throw new RuntimeException("exit=" + p.exitValue() + " cmd=" + cmd + " output=" + sb);
      }
      return sb.toString();
    } catch (Exception e) {
      if (e instanceof RuntimeException r) throw r;
      throw new RuntimeException(e);
    }
  }
}
