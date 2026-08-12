package com.navneet.executespec.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.cloud.storage.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Thin wrapper around the Google Cloud Storage SDK for workspace archiving.
 *
 * <p><b>Authentication:</b> uses Application Default Credentials (ADC). On GCE, this works
 * automatically via the attached service account — no extra auth code or key files needed. For
 * local development run: {@code gcloud auth application-default login}.
 *
 * <p><b>Required IAM roles on the service account:</b>
 *
 * <ul>
 *   <li>{@code roles/storage.objectAdmin} — upload / delete objects in the bucket
 *   <li>{@code roles/storage.bucketViewer} — read the configured bucket during startup admission
 *   <li>{@code roles/iam.serviceAccountTokenCreator} on the runtime service account itself —
 *       generate signed URLs
 * </ul>
 *
 * <p>GCS object key format: {@code {env}/workspace/{runId}.zip}
 */
@Slf4j
@Service
public class GcsWorkspaceStore {

  /** Exact physical identity returned by an immutable artifact write. */
  public record ArtifactReceipt(
      String key, String storageGeneration, String crc32c, long sizeBytes, String etag) {}

  /** Legacy prefix for backward-compatible reads. */
  private static final String LEGACY_KEY_PREFIX = "workspaces/";

  private static final String KEY_SUFFIX = ".zip";

  private final Storage storage;
  private final DeploymentProperties props;
  private final String environment;

  @org.springframework.beans.factory.annotation.Autowired
  public GcsWorkspaceStore(
      DeploymentProperties props, @Value("${spring.profiles.active:dev}") String environment) {
    this.props = props;
    this.environment = environment;
    Storage s = null;
    String configuredBucket = bucket();
    if (props.workspace() == null
        || !props.workspace().gcsEnabled()
        || configuredBucket == null
        || configuredBucket.isBlank()) {
      log.info("[GcsWorkspaceStore] Disabled — workspace GCS bucket is not configured");
      this.storage = null;
      return;
    }
    try {
      s = StorageOptions.getDefaultInstance().getService();
      // Verify bucket exists — if not, disable GCS operations to prevent
      // repeated 404 upload failures that spam logs every 5 minutes.
      String b = configuredBucket;
      if (s.get(b) == null) {
        log.warn(
            "[GcsWorkspaceStore] Bucket '{}' does not exist — disabling GCS workspace operations. "
                + "Create the bucket or set GCS_WORKSPACE_BUCKET='' to silence this.",
            b);
        s = null;
      } else {
        log.info("[GcsWorkspaceStore] Initialised — bucket={}, env={}", b, environment);
      }
    } catch (Exception e) {
      log.warn(
          "[GcsWorkspaceStore] GCS unavailable (ADC may need refresh): {}. "
              + "Run 'gcloud auth application-default login' to fix. "
              + "Workspace archiving disabled until credentials are refreshed.",
          e.getMessage());
      // RL-2026-05-059 — the bucket-existence probe (s.get(bucket)) runs a NETWORK call that throws
      // on expired ADC (invalid_rapt). `s` is already the (broken) client at that point, so without
      // this reset `storage` stays non-null and every run-end upload re-attempts + hangs on the
      // dead
      // creds (observed: 20-min benchmark instances). The catch's own log says archiving is
      // disabled
      // — make that true so isConfigured() reports false and callers skip the zip+upload entirely.
      s = null;
    }
    this.storage = s;
  }

  GcsWorkspaceStore(DeploymentProperties props, String environment, Storage storage) {
    this.props = props;
    this.environment = environment;
    this.storage = storage;
  }

  /**
   * RL-2026-05-041 (audit #2) — true when GCS is actually wired (ADC + bucket resolved at startup).
   * Lets callers (e.g. the mid-run checkpoint) skip the expensive workspace-zip step entirely when
   * GCS is unconfigured, e.g. on local dev — uploads would no-op anyway.
   */
  public boolean isConfigured() {
    return storage != null;
  }

  /**
   * Uploads a pre-built zip to GCS.
   *
   * @param runId the pipeline run ID
   * @param zipPath local path of the zip file to upload
   * @return GCS object key (e.g. {@code workspaces/{runId}.zip})
   */
  public String uploadZip(String runId, Path zipPath) throws IOException {
    if (storage == null) {
      log.warn("[GcsWorkspaceStore] GCS unavailable — skipping upload for run {}", runId);
      return null;
    }
    String key = objectKey(runId);
    BlobId blobId = BlobId.of(bucket(), key);
    BlobInfo blobInfo =
        BlobInfo.newBuilder(blobId)
            .setContentType("application/zip")
            .setMetadata(Map.of("runId", runId))
            .build();

    log.info(
        "[GcsWorkspaceStore] Uploading workspace zip for run {} → gs://{}/{} ({} bytes)",
        runId,
        bucket(),
        key,
        Files.size(zipPath));

    try (InputStream in = Files.newInputStream(zipPath)) {
      storage.createFrom(blobInfo, in);
    }

    log.info("[GcsWorkspaceStore] Upload complete: gs://{}/{}", bucket(), key);
    return key;
  }

  /**
   * Stream an already-built non-workspace artifact into the configured private bucket. The caller
   * owns the object key; credentials and signed URLs never enter the database.
   */
  public ArtifactReceipt uploadArtifactExact(
      String objectKey, Path source, String contentType, Map<String, String> metadata)
      throws IOException {
    if (storage == null) {
      throw new IOException("GCS unavailable");
    }
    String effectiveKey = environment + "/" + objectKey;
    BlobInfo blobInfo =
        BlobInfo.newBuilder(BlobId.of(bucket(), effectiveKey))
            .setContentType(contentType)
            .setMetadata(metadata == null ? Map.of() : Map.copyOf(metadata))
            .build();
    Blob blob;
    try (InputStream in = Files.newInputStream(source)) {
      try {
        blob = storage.createFrom(blobInfo, in, Storage.BlobWriteOption.doesNotExist());
      } catch (StorageException raced) {
        if (raced.getCode() != 412) throw raced;
        blob = storage.get(blobInfo.getBlobId());
        if (blob == null) throw raced;
      }
    }
    Map<String, String> expectedMetadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    if (!expectedMetadata.equals(blob.getMetadata())) {
      throw new IOException("GCS_ARTIFACT_METADATA_MISMATCH");
    }
    return receipt(effectiveKey, blob);
  }

  /** Compatibility adapter for legacy non-generation artifact callers. */
  public String uploadArtifact(
      String objectKey, Path source, String contentType, Map<String, String> metadata)
      throws IOException {
    return uploadArtifactExact(objectKey, source, contentType, metadata).key();
  }

  /** Exact generation-artifact read. No legacy key fallback is permitted. */
  public Path downloadArtifactToTempExact(
      String objectKey,
      String runId,
      String storageGeneration,
      long sizeBytes,
      String crc32c,
      String etag)
      throws IOException {
    if (storage == null) throw new IOException("GCS unavailable");
    Blob blob = storage.get(BlobId.of(bucket(), objectKey));
    if (blob == null) throw new IOException("GCS exact artifact not found: " + objectKey);
    ArtifactReceipt actual = receipt(objectKey, blob);
    if (!actual.storageGeneration().equals(storageGeneration)
        || actual.sizeBytes() != sizeBytes
        || !java.util.Objects.equals(actual.crc32c(), crc32c)
        || !java.util.Objects.equals(actual.etag(), etag)) {
      throw new IOException("GCS_ARTIFACT_IDENTITY_MISMATCH");
    }
    String shortId = runId.substring(0, Math.min(8, runId.length()));
    Path temp = Files.createTempFile("gcs-generation-" + shortId, ".tar.gz");
    try {
      blob.downloadTo(temp);
      if (Files.size(temp) != sizeBytes) throw new IOException("GCS_ARTIFACT_SIZE_MISMATCH");
      return temp;
    } catch (IOException failure) {
      Files.deleteIfExists(temp);
      throw failure;
    }
  }

  /** Deletes only the exact immutable generation returned by the original create-only write. */
  public void deleteArtifactExact(String objectKey, String storageGeneration) throws IOException {
    if (storage == null) throw new IOException("GCS unavailable");
    long generation;
    try {
      generation = Long.parseLong(storageGeneration);
    } catch (NumberFormatException malformed) {
      throw new IOException("GCS_ARTIFACT_GENERATION_INVALID", malformed);
    }
    try {
      storage.delete(
          BlobId.of(bucket(), objectKey), Storage.BlobSourceOption.generationMatch(generation));
    } catch (StorageException failure) {
      if (failure.getCode() != 404) throw failure;
    }
  }

  private static ArtifactReceipt receipt(String key, Blob blob) {
    return new ArtifactReceipt(
        key,
        String.valueOf(blob.getGeneration()),
        blob.getCrc32c(),
        blob.getSize(),
        blob.getEtag());
  }

  /** Stream an object without materialising it as a byte array in the application heap. */
  public void copyObjectTo(String objectKey, OutputStream destination) throws IOException {
    if (storage == null) throw new IOException("GCS unavailable");
    Blob blob = storage.get(BlobId.of(bucket(), objectKey));
    if (blob == null) throw new IOException("artifact not found");
    blob.downloadTo(destination);
  }

  /**
   * Downloads a GCS object to a temp file and returns the path. Caller is responsible for deleting
   * the temp file.
   *
   * <p>If the given key is not found, tries the legacy {@code workspaces/{runId}.zip} prefix as a
   * migration fallback.
   *
   * @param gcsKey the object key returned by {@link #uploadZip}
   * @param runId used only for naming the temp file
   */
  public Path downloadToTemp(String gcsKey, String runId) throws IOException {
    if (storage == null) {
      throw new IOException("GCS unavailable — run 'gcloud auth application-default login' to fix");
    }
    String shortId = runId.substring(0, Math.min(8, runId.length()));
    Path tempFile = Files.createTempFile("gcs-workspace-" + shortId, ".zip");
    tempFile.toFile().deleteOnExit();

    // Try the stored key first; fall back to legacy prefix if not found
    String effectiveKey = gcsKey;
    if (!exists(gcsKey)) {
      String legacyKey = LEGACY_KEY_PREFIX + runId + KEY_SUFFIX;
      if (exists(legacyKey)) {
        log.info("[GcsWorkspaceStore] Key {} not found, using legacy key {}", gcsKey, legacyKey);
        effectiveKey = legacyKey;
      }
    }

    log.info("[GcsWorkspaceStore] Downloading gs://{}/{} → {}", bucket(), effectiveKey, tempFile);
    // RR-161: stream the object straight to the temp file. `storage.readAllBytes` materialised the
    // whole archive in the application heap first, so a large (or crafted) archived workspace could
    // exhaust the heap during an ordinary restore/chat/Apply/preview request.
    Blob blob = storage.get(BlobId.of(bucket(), effectiveKey));
    if (blob == null) {
      throw new IOException("GCS object not found: " + effectiveKey);
    }
    blob.downloadTo(tempFile);

    log.info("[GcsWorkspaceStore] Download complete: {} bytes", Files.size(tempFile));
    return tempFile;
  }

  /**
   * Generates a signed URL that lets the client download the zip directly from GCS. Valid for
   * {@code ttl} (recommended: 5 minutes).
   *
   * <p>Using a signed URL means the download byte-stream bypasses the app server — the client
   * fetches directly from GCS, minimising latency and server bandwidth.
   *
   * @param gcsKey GCS object key
   * @param ttl how long the URL should be valid
   * @return pre-signed HTTPS URL
   */
  public URL signedDownloadUrl(String gcsKey, Duration ttl) {
    if (storage == null) {
      throw new StorageException(
          0, "GCS unavailable — run 'gcloud auth application-default login' to fix");
    }
    BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucket(), gcsKey)).build();
    return storage.signUrl(
        blobInfo,
        ttl.toSeconds(),
        TimeUnit.SECONDS,
        Storage.SignUrlOption.httpMethod(HttpMethod.GET),
        Storage.SignUrlOption.withV4Signature());
  }

  /** Returns true if the object exists in GCS. */
  public boolean exists(String gcsKey) {
    if (storage == null) return false;
    try {
      return storage.get(BlobId.of(bucket(), gcsKey)) != null;
    } catch (StorageException e) {
      log.warn("[GcsWorkspaceStore] Existence check failed for {}: {}", gcsKey, e.getMessage());
      return false;
    }
  }

  /** Deletes an object from GCS (used by the retention cleanup job for old archives). */
  public void delete(String gcsKey) {
    if (storage == null) {
      log.warn("[GcsWorkspaceStore] GCS unavailable — skipping delete for {}", gcsKey);
      return;
    }
    boolean deleted = storage.delete(BlobId.of(bucket(), gcsKey));
    if (deleted) {
      log.info("[GcsWorkspaceStore] Deleted gs://{}/{}", bucket(), gcsKey);
    } else {
      log.warn("[GcsWorkspaceStore] Object not found for deletion: gs://{}/{}", bucket(), gcsKey);
    }
  }

  /** Object key for a given run: {@code {env}/workspace/{runId}.zip} */
  public String objectKey(String runId) {
    return environment + "/workspace/" + runId + KEY_SUFFIX;
  }

  private String bucket() {
    return props.workspace().gcsBucket();
  }
}
