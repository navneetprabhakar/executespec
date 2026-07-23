package com.navneet.executespec.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SharedProfileCryptoContractTest {

  @Test
  void everySharedRemoteProfileRequiresEncryption() throws Exception {
    for (String profile : java.util.List.of("dev", "prod", "benchmark")) {
      String resource = "/application-" + profile + ".yml";
      try (var stream = getClass().getResourceAsStream(resource)) {
        assertThat(stream).as(resource).isNotNull();
        String yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        assertThat(yaml).as(resource).contains("crypto:").contains("require-encryption: true");
      }
    }
  }
}
