package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RunningReleaseIdentityServiceTest {

  @TempDir Path temp;

  @Test
  void readsAndNormalizesTheExactDeploymentHostMarker() throws Exception {
    Path marker = temp.resolve("RUNNING_SHA");
    Files.writeString(marker, "A".repeat(40) + "\n");

    var identity = new RunningReleaseIdentityService(marker.toString()).read();

    assertThat(identity.schemaVersion()).isOne();
    assertThat(identity.runningSha()).isEqualTo("a".repeat(40));
    assertThat(identity.authority()).isEqualTo("CONFIGURED_FILE:" + marker.toAbsolutePath());
    assertThat(identity.readAt()).isNotNull();
  }

  @Test
  void missingOrMalformedMarkerFailsClosed() throws Exception {
    assertUnavailable(new RunningReleaseIdentityService(temp.resolve("missing").toString()));

    Path malformed = temp.resolve("RUNNING_SHA");
    Files.writeString(malformed, "dev\n");
    assertUnavailable(new RunningReleaseIdentityService(malformed.toString()));
  }

  private static void assertUnavailable(RunningReleaseIdentityService service) {
    assertThatThrownBy(service::read)
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            failure ->
                assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }
}
