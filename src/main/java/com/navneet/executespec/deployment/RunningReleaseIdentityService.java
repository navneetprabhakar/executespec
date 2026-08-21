package com.navneet.executespec.deployment;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Attests the release tuple captured by this JVM at startup. */
@Service
public class RunningReleaseIdentityService {

  static final String AUTHORITY = "PROCESS_STARTUP_ENVIRONMENT";
  private static final Pattern COMMIT_SHA = Pattern.compile("[a-f0-9]{40}");
  private static final Pattern ARTIFACT_SHA256 = Pattern.compile("[a-f0-9]{64}");
  private static final Pattern ATTEMPT_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

  private final String runningSha;
  private final String artifactSha256;
  private final String deploymentAttemptId;

  public RunningReleaseIdentityService(
      @Value("${EXECUTESPEC_RELEASE_SOURCE_SHA:}") String runningSha,
      @Value("${EXECUTESPEC_RELEASE_ARTIFACT_SHA256:}") String artifactSha256,
      @Value("${EXECUTESPEC_RELEASE_ATTEMPT_ID:}") String deploymentAttemptId) {
    this.runningSha = normalize(runningSha);
    this.artifactSha256 = normalize(artifactSha256);
    this.deploymentAttemptId = deploymentAttemptId == null ? "" : deploymentAttemptId.trim();
  }

  public ReleaseIdentity read() {
    if (!COMMIT_SHA.matcher(runningSha).matches()
        || !ARTIFACT_SHA256.matcher(artifactSha256).matches()
        || !ATTEMPT_ID.matcher(deploymentAttemptId).matches()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "RUNNING_RELEASE_IDENTITY_INVALID");
    }
    return new ReleaseIdentity(
        2, runningSha, artifactSha256, deploymentAttemptId, AUTHORITY, Instant.now());
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  public record ReleaseIdentity(
      int schemaVersion,
      String runningSha,
      String artifactSha256,
      String deploymentAttemptId,
      String authority,
      Instant readAt) {}
}
