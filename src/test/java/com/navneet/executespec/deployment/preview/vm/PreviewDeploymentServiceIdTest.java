package com.navneet.executespec.deployment.preview.vm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * RR-1618 — the VM preview id is the registry {@code @Id} (a merge, not an insert), the DNS label,
 * the Caddy site-config filename and the remote tmp dir. The prior 4-hex suffix carried only 16
 * bits of entropy over a caller-influenced project slug, so a same-named project collided with
 * p≈1/65536 per pair and an attacker who could name a project could overwrite a victim's row. The
 * id now carries a full 128-bit UUID suffix while staying within the 63-char DNS label limit.
 */
class PreviewDeploymentServiceIdTest {

  @Test
  void idCarriesFullUuidEntropyAndStaysWithinDnsLabelLimit() {
    String a = PreviewDeploymentService.generatePreviewId("my-app");
    String b = PreviewDeploymentService.generatePreviewId("my-app");

    assertThat(a).isNotEqualTo(b);
    assertThat(a.length()).isLessThanOrEqualTo(63);
    String suffix = a.substring(a.lastIndexOf('-') + 1);
    assertThat(suffix).hasSize(32).matches("[0-9a-f]{32}");
  }

  @Test
  void longProjectNameIsTruncatedButIdStaysValid() {
    String id = PreviewDeploymentService.generatePreviewId("a".repeat(200));
    assertThat(id.length()).isLessThanOrEqualTo(63);
    assertThat(id).matches("[a-z0-9]+-[0-9a-f]{32}");
  }
}
