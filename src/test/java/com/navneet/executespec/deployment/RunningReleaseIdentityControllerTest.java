package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class RunningReleaseIdentityControllerTest {

  @Test
  void responseIsNoStoreAndCarriesTheServiceIdentity() {
    RunningReleaseIdentityService service = mock(RunningReleaseIdentityService.class);
    var identity =
        new RunningReleaseIdentityService.ReleaseIdentity(
            2,
            "a".repeat(40),
            "b".repeat(64),
            "1-1",
            RunningReleaseIdentityService.AUTHORITY,
            Instant.EPOCH);
    when(service.read()).thenReturn(identity);

    var response = new RunningReleaseIdentityController(service).read();

    assertThat(response.getBody()).isSameAs(identity);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
  }
}
