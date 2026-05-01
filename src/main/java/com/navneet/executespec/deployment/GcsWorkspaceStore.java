package com.navneet.executespec.deployment;

import java.io.IOException;
import java.io.InputStream;
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
 *   <li>{@code roles/iam.serviceAccountTokenCreator} — generate signed URLs
 * </ul>
 *
 * <p>GCS object key format: {@code {env}/workspace/{runId}.zip}
 */
@Slf4j
@Service
public class GcsWorkspaceStore {

  /** Legacy prefix for backward-compatible reads. */
  private static final String LEGACY_KEY_PREFIX = "workspaces/";

  private static final String KEY_SUFFIX = ".zip";

  private final Storage storage;
  private final DeploymentProperties props;
  private final String environment;

  public GcsWorkspaceStore(
      DeploymentProperties props, @Value("${spring.profiles.active:dev}") String environment) {
    this.props = props;
    this.environment = environment;
    Storage s = null;
    try {
      s = StorageOptions.getDefaultInstance().getService();
      // Verify bucket exists — if not, disable GCS operations to prevent
      // repeated 404 upload failures that spam logs every 5 minutes.
      String b = bucket();
      if (b != null && !b.isBlank() && s.get(b) == null) {
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
    }
    this.storage = s;
  }

  // ── Public API ─────────────────────────────────────────────────────────────

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
    byte[] bytes = storage.readAllBytes(BlobId.of(bucket(), effectiveKey));
    Files.write(tempFile, bytes);

    log.info("[GcsWorkspaceStore] Download complete: {} bytes", bytes.length);
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

  // ── Helpers ────────────────────────────────────────────────────────────────

  /** Object key for a given run: {@code {env}/workspace/{runId}.zip} */
  public String objectKey(String runId) {
    return environment + "/workspace/" + runId + KEY_SUFFIX;
  }

  private String bucket() {
    return props.workspace().gcsBucket();
  }
}
