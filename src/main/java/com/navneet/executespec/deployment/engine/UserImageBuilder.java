package com.navneet.executespec.deployment.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.zip.GZIPOutputStream;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.devtools.cloudbuild.v1.CloudBuildClient;
import com.google.cloud.devtools.cloudbuild.v1.CloudBuildSettings;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloudbuild.v1.*;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Builds Docker images in the <em>user's</em> GCP project using Cloud Build.
 *
 * <p>Security: user code never executes on our server. The source is uploaded to the user's GCS
 * bucket and built remotely by Cloud Build in their project.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>Tar+gzip the workspace
 *   <li>Upload to user's GCS (temp bucket)
 *   <li>Submit Cloud Build job: docker build → push to Artifact Registry
 *   <li>Wait for completion
 * </ol>
 */
@Service
@Slf4j
public class UserImageBuilder {

  private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(15);
  private static final String TEMP_BUCKET_PREFIX = "executespec-build-";

  /**
   * Build and push an image using Cloud Build in the user's GCP project.
   *
   * @param credentials user's GCP credentials
   * @param gcpProject user's GCP project ID
   * @param region GCP region for Artifact Registry
   * @param workspacePath path to workspace with Dockerfile
   * @param imageTag version tag (e.g., "abc12345-v1")
   * @param arRepo Artifact Registry repository name
   * @return build result with image URI and build log
   */
  public BuildResult build(
      GoogleCredentials credentials,
      String gcpProject,
      String region,
      Path workspacePath,
      String imageTag,
      String arRepo)
      throws IOException {

    String arHost = region + "-docker.pkg.dev";
    String fullImageUri = "%s/%s/%s/%s".formatted(arHost, gcpProject, arRepo, imageTag);

    // 1. Create tar.gz of workspace
    Path tarball = createTarball(workspacePath);
    log.info("Created build tarball: {} ({} bytes)", tarball, Files.size(tarball));

    try {
      // 2. Upload source to user's GCS
      String bucketName = TEMP_BUCKET_PREFIX + gcpProject;
      String objectName = "source/" + imageTag + ".tar.gz";
      uploadToGcs(credentials, gcpProject, bucketName, objectName, tarball);
      log.info("Uploaded source to gs://{}/{}", bucketName, objectName);

      // 3. Submit Cloud Build
      CloudBuildSettings settings =
          CloudBuildSettings.newBuilder()
              .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
              .build();

      try (CloudBuildClient client = CloudBuildClient.create(settings)) {
        Build buildSpec =
            Build.newBuilder()
                .setSource(
                    Source.newBuilder()
                        .setStorageSource(
                            StorageSource.newBuilder()
                                .setBucket(bucketName)
                                .setObject(objectName)
                                .build())
                        .build())
                .addSteps(
                    BuildStep.newBuilder()
                        .setName("gcr.io/cloud-builders/docker")
                        .addArgs("build")
                        .addArgs("-t")
                        .addArgs(fullImageUri)
                        .addArgs(".")
                        .build())
                .addImages(fullImageUri)
                .setTimeout(
                    com.google.protobuf.Duration.newBuilder()
                        .setSeconds(BUILD_TIMEOUT.toSeconds())
                        .build())
                .setOptions(
                    BuildOptions.newBuilder()
                        .setMachineType(BuildOptions.MachineType.E2_HIGHCPU_8)
                        .setLogging(BuildOptions.LoggingMode.GCS_ONLY)
                        .build())
                .build();

        var op = client.createBuildAsync(gcpProject, buildSpec);
        log.info("Cloud Build submitted for {}", imageTag);

        Build result = op.get();

        if (result.getStatus() != Build.Status.SUCCESS) {
          String buildLog = result.getLogUrl();
          throw new IOException(
              "Cloud Build failed (status: " + result.getStatus() + "). Logs: " + buildLog);
        }

        log.info("Cloud Build succeeded: {} → {}", imageTag, fullImageUri);

        return new BuildResult(fullImageUri, result.getLogUrl(), result.getId());
      }
    } catch (Exception e) {
      if (e instanceof IOException ioe) throw ioe;
      throw new IOException("Image build failed: " + e.getMessage(), e);
    } finally {
      Files.deleteIfExists(tarball);
    }
  }

  /**
   * Ensure the Artifact Registry repository exists in the user's project. Creates it if missing.
   */
  public void ensureArtifactRegistryRepo(
      GoogleCredentials credentials, String gcpProject, String region, String repoName)
      throws IOException {
    try {
      var settings =
          com.google.devtools.artifactregistry.v1.ArtifactRegistrySettings.newBuilder()
              .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
              .build();

      try (var client =
          com.google.devtools.artifactregistry.v1.ArtifactRegistryClient.create(settings)) {
        String parent = "projects/" + gcpProject + "/locations/" + region;
        String repoFullName = parent + "/repositories/" + repoName;

        try {
          client.getRepository(repoFullName);
          log.info("AR repo already exists: {}", repoFullName);
        } catch (com.google.api.gax.rpc.NotFoundException e) {
          var repo =
              com.google.devtools.artifactregistry.v1.Repository.newBuilder()
                  .setFormat(com.google.devtools.artifactregistry.v1.Repository.Format.DOCKER)
                  .setDescription("ExecuteSpec deployment images")
                  .build();

          var op = client.createRepositoryAsync(parent, repo, repoName);
          op.get();
          log.info("Created AR repo: {}", repoFullName);
        }
      }
    } catch (Exception e) {
      if (e instanceof IOException ioe) throw ioe;
      throw new IOException("Failed to ensure AR repo: " + e.getMessage(), e);
    }
  }

  /** Ensure the temp GCS bucket exists for Cloud Build source uploads. */
  public void ensureBuildBucket(GoogleCredentials credentials, String gcpProject, String region)
      throws IOException {
    String bucketName = TEMP_BUCKET_PREFIX + gcpProject;
    try {
      Storage storage =
          StorageOptions.newBuilder()
              .setCredentials(credentials)
              .setProjectId(gcpProject)
              .build()
              .getService();

      if (storage.get(bucketName) == null) {
        storage.create(
            com.google.cloud.storage.BucketInfo.newBuilder(bucketName)
                .setLocation(region)
                .setLifecycleRules(
                    java.util.List.of(
                        new com.google.cloud.storage.BucketInfo.LifecycleRule(
                            com.google.cloud.storage.BucketInfo.LifecycleRule.LifecycleAction
                                .newDeleteAction(),
                            com.google.cloud.storage.BucketInfo.LifecycleRule.LifecycleCondition
                                .newBuilder()
                                .setAge(1) // auto-delete after 1 day
                                .build())))
                .build());
        log.info("Created build bucket: gs://{}", bucketName);
      }
    } catch (Exception e) {
      throw new IOException("Failed to ensure build bucket: " + e.getMessage(), e);
    }
  }

  private void uploadToGcs(
      GoogleCredentials credentials,
      String gcpProject,
      String bucketName,
      String objectName,
      Path file)
      throws IOException {
    Storage storage =
        StorageOptions.newBuilder()
            .setCredentials(credentials)
            .setProjectId(gcpProject)
            .build()
            .getService();

    BlobInfo blobInfo =
        BlobInfo.newBuilder(BlobId.of(bucketName, objectName))
            .setContentType("application/gzip")
            .build();

    storage.createFrom(blobInfo, file);
  }

  /** Create a tar.gz of the workspace, excluding common non-build files. */
  private Path createTarball(Path workspacePath) throws IOException {
    Path tarball = Files.createTempFile("executespec-build-", ".tar.gz");

    try (OutputStream fos = Files.newOutputStream(tarball);
        GZIPOutputStream gzos = new GZIPOutputStream(fos);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzos)) {

      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

      Files.walkFileTree(
          workspacePath,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              String name = workspacePath.relativize(dir).toString();
              if (shouldExclude(name)) return FileVisitResult.SKIP_SUBTREE;
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              String relativePath = workspacePath.relativize(file).toString();
              if (shouldExclude(relativePath)) return FileVisitResult.CONTINUE;

              TarArchiveEntry entry = new TarArchiveEntry(file, relativePath);
              entry.setSize(attrs.size());
              tar.putArchiveEntry(entry);
              Files.copy(file, tar);
              tar.closeArchiveEntry();
              return FileVisitResult.CONTINUE;
            }
          });
    }

    return tarball;
  }

  private boolean shouldExclude(String path) {
    if (path.isEmpty()) return false;
    return path.startsWith(".git")
        || path.equals("node_modules")
        || path.startsWith("node_modules/")
        || path.equals("target")
        || path.startsWith("target/")
        || path.equals("build")
        || path.startsWith("build/")
        || path.equals("__pycache__")
        || path.startsWith("__pycache__/")
        || path.equals(".venv")
        || path.startsWith(".venv/")
        || path.equals("venv")
        || path.startsWith("venv/")
        || path.endsWith(".class")
        || path.endsWith(".jar");
  }

  public record BuildResult(String imageUri, String buildLogUrl, String buildId) {}
}
