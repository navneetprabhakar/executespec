package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RunningReleaseIdentityServiceTest {

  @Test
  void returnsTheExactStartupCapturedReleaseTuple() {
    var identity =
        new RunningReleaseIdentityService("A".repeat(40) + "\n", "B".repeat(64), "12345-2").read();

    assertThat(identity.schemaVersion()).isEqualTo(2);
    assertThat(identity.runningSha()).isEqualTo("a".repeat(40));
    assertThat(identity.artifactSha256()).isEqualTo("b".repeat(64));
    assertThat(identity.deploymentAttemptId()).isEqualTo("12345-2");
    assertThat(identity.authority()).isEqualTo(RunningReleaseIdentityService.AUTHORITY);
    assertThat(identity.readAt()).isNotNull();
  }

  @Test
  void anyMissingOrMalformedTupleFieldFailsClosed() {
    assertUnavailable(new RunningReleaseIdentityService("", "b".repeat(64), "1-1"));
    assertUnavailable(new RunningReleaseIdentityService("a".repeat(40), "bad", "1-1"));
    assertUnavailable(new RunningReleaseIdentityService("a".repeat(40), "b".repeat(64), "bad id"));
  }

  private static void assertUnavailable(RunningReleaseIdentityService service) {
    assertThatThrownBy(service::read)
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            failure ->
                assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }
}
