package com.navneet.executespec.export;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.GcsWorkspaceStore;
import com.navneet.executespec.security.ArtifactPublicationGuard;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Private object boundary for completed exports. GCS is preferred when configured; a stable local
 * root keeps local/single-VM installations restart-safe without storing archive bytes in Postgres
 * or JVM heap.
 */
@Component
@RequiredArgsConstructor
public class ProjectExportObjectStore {

  private static final String GCS_PREFIX = "gcs:";
  private static final String LOCAL_PREFIX = "local:";

  public record Artifact(String objectKey, long sizeBytes, String sha256) {}

  private final AgentProperties agentProperties;
  private final ProjectExportProperties properties;
  private final GcsWorkspaceStore gcs;
  private final ArtifactPublicationGuard publicationGuard;

  public Path createStagingFile(UUID jobId, long fencingToken) throws IOException {
    Path staging = root().resolve(".staging");
    Files.createDirectories(staging);
    ownerOnlyDirectory(staging);
    Path path = Files.createTempFile(staging, jobId + "-" + fencingToken + "-", ".tar.gz");
    ownerOnly(path);
    return path;
  }

  public Artifact publish(
      UUID jobId, long fencingToken, String projectId, Path archive, String contentType)
      throws IOException {
    if (!Files.isRegularFile(archive)) throw new IOException("export staging artifact missing");
    publicationGuard.assertTarGzipSafe(archive);
    long size = Files.size(archive);
    String sha256 = sha256(archive);
    String relative = "project-exports/" + jobId + "/" + fencingToken + ".tar.gz";

    if (properties.isGcsEnabled() && gcs.isConfigured()) {
      String key =
          gcs.uploadArtifact(
              relative,
              archive,
              contentType,
              Map.of("exportJobId", jobId.toString(), "projectId", projectId));
      return new Artifact(GCS_PREFIX + key, size, sha256);
    }

    Path destination = checkedLocal(relative);
    Files.createDirectories(destination.getParent());
    ownerOnlyDirectory(destination.getParent());
    try {
      Files.move(
          archive,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(archive, destination, StandardCopyOption.REPLACE_EXISTING);
    }
    ownerOnly(destination);
    return new Artifact(LOCAL_PREFIX + relative, size, sha256);
  }

  public void copyTo(String objectKey, OutputStream destination) throws IOException {
    if (objectKey == null) throw new IOException("export artifact unavailable");
    if (objectKey.startsWith(GCS_PREFIX)) {
      Path validation = Files.createTempFile(root(), "project-export-validation-", ".tar.gz");
      ownerOnly(validation);
      try {
        try (OutputStream file = Files.newOutputStream(validation)) {
          gcs.copyObjectTo(objectKey.substring(GCS_PREFIX.length()), file);
        }
        publicationGuard.assertTarGzipSafe(validation);
        Files.copy(validation, destination);
      } finally {
        Files.deleteIfExists(validation);
      }
      return;
    }
    Path source = localObjectPath(objectKey);
    publicationGuard.assertTarGzipSafe(source);
    try (InputStream in = Files.newInputStream(source)) {
      in.transferTo(destination);
    }
  }

  public boolean exists(String objectKey) {
    if (objectKey == null) return false;
    if (objectKey.startsWith(GCS_PREFIX)) {
      return gcs.exists(objectKey.substring(GCS_PREFIX.length()));
    }
    try {
      return Files.isRegularFile(localObjectPath(objectKey));
    } catch (IOException ignored) {
      return false;
    }
  }

  public void delete(String objectKey) throws IOException {
    if (objectKey == null) return;
    if (objectKey.startsWith(GCS_PREFIX)) {
      gcs.delete(objectKey.substring(GCS_PREFIX.length()));
      return;
    }
    Path path = localObjectPath(objectKey);
    Files.deleteIfExists(path);
    Path parent = path.getParent();
    if (parent != null && !parent.equals(root())) {
      try {
        Files.deleteIfExists(parent);
      } catch (IOException ignored) {
        // Another fenced attempt may still have an object in the directory.
      }
    }
  }

  private Path localObjectPath(String objectKey) throws IOException {
    if (!objectKey.startsWith(LOCAL_PREFIX)) throw new IOException("unknown export object scheme");
    return checkedLocal(objectKey.substring(LOCAL_PREFIX.length()));
  }

  private Path checkedLocal(String relative) throws IOException {
    if (relative == null || relative.isBlank() || Path.of(relative).isAbsolute()) {
      throw new IOException("invalid export object key");
    }
    Path root = root();
    Path resolved = root.resolve(relative).normalize();
    if (!resolved.startsWith(root)) throw new IOException("invalid export object key");
    return resolved;
  }

  private Path root() throws IOException {
    String configured = properties.getStorageRoot();
    String workspace = agentProperties.getWorkspace();
    Path root =
        configured == null || configured.isBlank()
            ? Path.of(workspace == null || workspace.isBlank() ? "./workspace" : workspace)
                .resolve("project-export-store")
            : Path.of(configured);
    Path normalized = root.toAbsolutePath().normalize();
    Files.createDirectories(normalized);
    ownerOnlyDirectory(normalized);
    return normalized;
  }

  private static String sha256(Path path) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream raw = Files.newInputStream(path);
          DigestInputStream in = new DigestInputStream(raw, digest)) {
        in.transferTo(OutputStream.nullOutputStream());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static void ownerOnly(Path path) {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException | IOException ignored) {
      // Windows/non-POSIX test filesystems do not expose POSIX permissions.
    }
  }

  private static void ownerOnlyDirectory(Path path) {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException | IOException ignored) {
      // Windows/non-POSIX test filesystems do not expose POSIX permissions.
    }
  }
}
