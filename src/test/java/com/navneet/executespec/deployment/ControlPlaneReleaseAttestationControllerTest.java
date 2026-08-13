package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class ControlPlaneReleaseAttestationControllerTest {

  @Test
  void responseIsNoStoreAndCarriesTheExactBundle() {
    ControlPlaneReleaseAttestationService service =
        mock(ControlPlaneReleaseAttestationService.class);
    var bundle =
        new ControlPlaneReleaseAttestationService.Attestation(
            1,
            Instant.EPOCH,
            ControlPlaneReleaseAttestationService.BUNDLE_AUTHORITY,
            new ControlPlaneReleaseAttestationService.ReleaseEvidence(
                "a".repeat(40), "b".repeat(64), "deploy-1", "fixture", true),
            null,
            List.of(),
            null,
            null,
            null,
            false,
            false,
            List.of("fixture"),
            "c".repeat(64));
    when(service.attest()).thenReturn(bundle);

    var response = new ControlPlaneReleaseAttestationController(service).read();

    assertThat(response.getBody()).isSameAs(bundle);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
  }
}
