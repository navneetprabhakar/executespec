package com.navneet.executespec.deployment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Reads the immutable deployment-host marker used by the deploy workflow's RUNNING_SHA gate. */
@Service
public class RunningReleaseIdentityService {

  static final String AUTHORITY = "DEPLOYMENT_HOST:/opt/app/RUNNING_SHA";
  private static final Path CANONICAL_RUNNING_SHA = Path.of("/opt/app/RUNNING_SHA");
  private static final Pattern COMMIT_SHA = Pattern.compile("[a-f0-9]{40}");

  private final Path runningShaFile;
  private final String authority;

  public RunningReleaseIdentityService(
      @Value("${executespec.release.running-sha-file:/opt/app/RUNNING_SHA}")
          String runningShaFile) {
    this.runningShaFile = Path.of(runningShaFile).toAbsolutePath().normalize();
    this.authority =
        this.runningShaFile.equals(CANONICAL_RUNNING_SHA)
            ? AUTHORITY
            : "CONFIGURED_FILE:" + this.runningShaFile;
  }

  public ReleaseIdentity read() {
    final String sha;
    try {
      sha =
          Files.readString(runningShaFile, StandardCharsets.US_ASCII)
              .trim()
              .toLowerCase(Locale.ROOT);
    } catch (IOException unavailable) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "RUNNING_RELEASE_IDENTITY_UNAVAILABLE", unavailable);
    }
    if (!COMMIT_SHA.matcher(sha).matches()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "RUNNING_RELEASE_IDENTITY_INVALID");
    }
    return new ReleaseIdentity(1, sha, authority, Instant.now());
  }

  public record ReleaseIdentity(
      int schemaVersion, String runningSha, String authority, Instant readAt) {}
}
