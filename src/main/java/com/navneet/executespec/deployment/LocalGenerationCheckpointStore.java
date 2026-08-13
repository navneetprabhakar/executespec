package com.navneet.executespec.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.CRC32C;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Host-bound but byte-durable exact-generation checkpoint store for local/non-GCS execution. */
@Component
public class LocalGenerationCheckpointStore {

  private static final String SCHEME = "local-generation://";
  private final Path checkpointRoot;

  public LocalGenerationCheckpointStore(@Value("${agent.workspace:./workspace}") String workspace) {
    this.checkpointRoot =
        Path.of(workspace).toAbsolutePath().normalize().resolve(".generation-checkpoints");
  }

  public GcsWorkspaceStore.ArtifactReceipt store(
      String runId, String generation, String artifactDigest, Path source) throws IOException {
    requireIdentity(runId, generation, artifactDigest);
    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
      throw new IOException("LOCAL_CHECKPOINT_SOURCE_INVALID");
    }
    Path runDirectory = checkpointRoot.resolve(DigestUtils.sha256Hex(runId)).normalize();
    requireOwned(runDirectory);
    Files.createDirectories(runDirectory);
    rejectSymlinkComponents(checkpointRoot, runDirectory);
    Path target = runDirectory.resolve(generation + "-" + artifactDigest + ".tar.gz").normalize();
    requireOwned(target);
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(source, target);
      } catch (java.nio.file.FileAlreadyExistsException raced) {
        // A content-addressed retry may converge on the same immutable file.
      }
    }
    ArtifactIdentity identity = inspect(target, artifactDigest);
    Files.deleteIfExists(source);
    String key = SCHEME + hostIdentity() + target;
    return new GcsWorkspaceStore.ArtifactReceipt(
        key, "sha256:" + artifactDigest, identity.crc32c(), identity.sizeBytes(), artifactDigest);
  }

  public Path copyToTempExact(
      String key,
      String runId,
      String artifactDigest,
      String storageGeneration,
      long sizeBytes,
      String crc32c,
      String etag)
      throws IOException {
    requireIdentity(runId, "0".repeat(64), artifactDigest);
    String prefix = SCHEME + hostIdentity();
    if (key == null || !key.startsWith(prefix + "/")) {
      throw new IOException("LOCAL_CHECKPOINT_HOST_MISMATCH");
    }
    Path source = Path.of(key.substring(prefix.length())).toAbsolutePath().normalize();
    Path expectedRunDirectory = checkpointRoot.resolve(DigestUtils.sha256Hex(runId)).normalize();
    if (!source.getParent().equals(expectedRunDirectory)) {
      throw new IOException("LOCAL_CHECKPOINT_PATH_NOT_OWNED");
    }
    requireOwned(source);
    rejectSymlinkComponents(checkpointRoot, source);
    ArtifactIdentity identity = inspect(source, artifactDigest);
    requireMetadata(artifactDigest, storageGeneration, sizeBytes, crc32c, etag, identity);
    Path copy = Files.createTempFile("workspace-generation-restore-", ".tar.gz");
    try {
      Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
      ArtifactIdentity copied = inspect(copy, artifactDigest);
      requireMetadata(artifactDigest, storageGeneration, sizeBytes, crc32c, etag, copied);
      return copy;
    } catch (IOException failure) {
      Files.deleteIfExists(copy);
      throw failure;
    }
  }

  /** Deletes only a host-owned archive whose full physical identity still matches the receipt. */
  public void deleteExact(
      String key,
      String runId,
      String artifactDigest,
      String storageGeneration,
      long sizeBytes,
      String crc32c,
      String etag)
      throws IOException {
    Path source = exactSource(key, runId);
    // A janitor may crash after deleting the immutable object but before settling its durable
    // cleanup row. Ownership and path identity are still validated above; absence is the desired
    // converged physical state and must therefore be a successful replay.
    if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    ArtifactIdentity identity = inspect(source, artifactDigest);
    requireMetadata(artifactDigest, storageGeneration, sizeBytes, crc32c, etag, identity);
    Files.deleteIfExists(source);
  }

  private static void requireMetadata(
      String digest,
      String storageGeneration,
      long sizeBytes,
      String crc32c,
      String etag,
      ArtifactIdentity actual)
      throws IOException {
    if (!("sha256:" + digest).equals(storageGeneration)
        || sizeBytes <= 0
        || actual.sizeBytes() != sizeBytes
        || !actual.crc32c().equals(crc32c)
        || !digest.equals(etag)) {
      throw new IOException("LOCAL_CHECKPOINT_METADATA_MISMATCH");
    }
  }

  private static ArtifactIdentity inspect(Path path, String expectedDigest) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
      throw new IOException("LOCAL_CHECKPOINT_ARTIFACT_INVALID");
    }
    CRC32C crc = new CRC32C();
    MessageDigest sha256;
    try {
      sha256 = MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        if (read == 0) continue;
        crc.update(buffer, 0, read);
        sha256.update(buffer, 0, read);
      }
    }
    String digest = HexFormat.of().formatHex(sha256.digest());
    if (!expectedDigest.equals(digest)) {
      throw new IOException("LOCAL_CHECKPOINT_DIGEST_MISMATCH");
    }
    return new ArtifactIdentity(Files.size(path), HexFormat.of().toHexDigits((int) crc.getValue()));
  }

  private void requireOwned(Path path) throws IOException {
    if (!path.toAbsolutePath().normalize().startsWith(checkpointRoot)) {
      throw new IOException("LOCAL_CHECKPOINT_PATH_NOT_OWNED");
    }
  }

  private static void rejectSymlinkComponents(Path root, Path path) throws IOException {
    Path current = root;
    if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
      throw new IOException("LOCAL_CHECKPOINT_SYMLINK_REFUSED");
    }
    for (Path segment : root.relativize(path)) {
      current = current.resolve(segment);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
        throw new IOException("LOCAL_CHECKPOINT_SYMLINK_REFUSED");
      }
    }
  }

  private static void requireIdentity(String runId, String generation, String digest) {
    if (runId == null
        || runId.isBlank()
        || generation == null
        || !generation.matches("[0-9a-f]{64}")
        || digest == null
        || !digest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("LOCAL_CHECKPOINT_IDENTITY_INVALID");
    }
  }

  static String hostIdentity() {
    try {
      return java.net.InetAddress.getLocalHost().getHostName().replaceAll("[^A-Za-z0-9._-]", "_");
    } catch (Exception unavailable) {
      return "unknown-host";
    }
  }

  private Path exactSource(String key, String runId) throws IOException {
    String prefix = SCHEME + hostIdentity();
    if (key == null || !key.startsWith(prefix + "/")) {
      throw new IOException("LOCAL_CHECKPOINT_HOST_MISMATCH");
    }
    Path source = Path.of(key.substring(prefix.length())).toAbsolutePath().normalize();
    Path expectedRunDirectory = checkpointRoot.resolve(DigestUtils.sha256Hex(runId)).normalize();
    if (!source.getParent().equals(expectedRunDirectory)) {
      throw new IOException("LOCAL_CHECKPOINT_PATH_NOT_OWNED");
    }
    requireOwned(source);
    rejectSymlinkComponents(checkpointRoot, source);
    return source;
  }

  private record ArtifactIdentity(long sizeBytes, String crc32c) {}
}
