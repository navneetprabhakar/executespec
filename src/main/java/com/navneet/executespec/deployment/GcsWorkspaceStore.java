package com.navneet.executespec.deployment;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the Google Cloud Storage SDK for workspace archiving.
 *
 * <p><b>Authentication:</b> uses Application Default Credentials (ADC).
 * On GCE, this works automatically via the attached service account — no extra
 * auth code or key files needed.  For local development run:
 * {@code gcloud auth application-default login}.
 *
 * <p><b>Required IAM roles on the service account:</b>
 * <ul>
 *   <li>{@code roles/storage.objectAdmin} — upload / delete objects in the bucket</li>
 *   <li>{@code roles/iam.serviceAccountTokenCreator} — generate signed URLs</li>
 * </ul>
 *
 * <p>GCS object key format: {@code workspaces/{runId}.zip}
 */
@Slf4j
@Service
public class GcsWorkspaceStore {

    private static final String KEY_PREFIX = "workspaces/";
    private static final String KEY_SUFFIX = ".zip";

    private final Storage storage;
    private final DeploymentProperties props;

    public GcsWorkspaceStore(DeploymentProperties props) throws IOException {
        this.props = props;
        this.storage = StorageOptions.getDefaultInstance().getService();
        log.info("[GcsWorkspaceStore] Initialised — bucket={}", bucket());
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Uploads a pre-built zip to GCS.
     *
     * @param runId   the pipeline run ID
     * @param zipPath local path of the zip file to upload
     * @return GCS object key (e.g. {@code workspaces/{runId}.zip})
     */
    public String uploadZip(String runId, Path zipPath) throws IOException {
        String key = objectKey(runId);
        BlobId blobId = BlobId.of(bucket(), key);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
            .setContentType("application/zip")
            .setMetadata(Map.of("runId", runId))
            .build();

        log.info("[GcsWorkspaceStore] Uploading workspace zip for run {} → gs://{}/{} ({} bytes)",
            runId, bucket(), key, Files.size(zipPath));

        try (InputStream in = Files.newInputStream(zipPath)) {
            storage.createFrom(blobInfo, in);
        }

        log.info("[GcsWorkspaceStore] Upload complete: gs://{}/{}", bucket(), key);
        return key;
    }

    /**
     * Downloads a GCS object to a temp file and returns the path.
     * Caller is responsible for deleting the temp file.
     *
     * @param gcsKey the object key returned by {@link #uploadZip}
     * @param runId  used only for naming the temp file
     */
    public Path downloadToTemp(String gcsKey, String runId) throws IOException {
        String shortId = runId.substring(0, Math.min(8, runId.length()));
        Path tempFile = Files.createTempFile("gcs-workspace-" + shortId, ".zip");
        tempFile.toFile().deleteOnExit();

        log.info("[GcsWorkspaceStore] Downloading gs://{}/{} → {}", bucket(), gcsKey, tempFile);
        byte[] bytes = storage.readAllBytes(BlobId.of(bucket(), gcsKey));
        Files.write(tempFile, bytes);

        log.info("[GcsWorkspaceStore] Download complete: {} bytes", bytes.length);
        return tempFile;
    }

    /**
     * Generates a signed URL that lets the client download the zip directly from GCS.
     * Valid for {@code ttl} (recommended: 5 minutes).
     *
     * <p>Using a signed URL means the download byte-stream bypasses the app server —
     * the client fetches directly from GCS, minimising latency and server bandwidth.
     *
     * @param gcsKey GCS object key
     * @param ttl    how long the URL should be valid
     * @return pre-signed HTTPS URL
     */
    public URL signedDownloadUrl(String gcsKey, Duration ttl) {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucket(), gcsKey)).build();
        return storage.signUrl(
            blobInfo,
            ttl.toSeconds(),
            TimeUnit.SECONDS,
            Storage.SignUrlOption.httpMethod(HttpMethod.GET),
            Storage.SignUrlOption.withV4Signature()
        );
    }

    /**
     * Returns true if the object exists in GCS.
     */
    public boolean exists(String gcsKey) {
        try {
            return storage.get(BlobId.of(bucket(), gcsKey)) != null;
        } catch (StorageException e) {
            log.warn("[GcsWorkspaceStore] Existence check failed for {}: {}", gcsKey, e.getMessage());
            return false;
        }
    }

    /**
     * Deletes an object from GCS (used by the retention cleanup job for old archives).
     */
    public void delete(String gcsKey) {
        boolean deleted = storage.delete(BlobId.of(bucket(), gcsKey));
        if (deleted) {
            log.info("[GcsWorkspaceStore] Deleted gs://{}/{}", bucket(), gcsKey);
        } else {
            log.warn("[GcsWorkspaceStore] Object not found for deletion: gs://{}/{}", bucket(), gcsKey);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Object key for a given run: {@code workspaces/{runId}.zip} */
    public static String objectKey(String runId) {
        return KEY_PREFIX + runId + KEY_SUFFIX;
    }

    private String bucket() {
        return props.workspace().gcsBucket();
    }
}
