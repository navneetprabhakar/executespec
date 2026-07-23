package com.navneet.executespec.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * RR-424 — the Redis echo-suppression instance id must be deployment-unique. The previous {@code
 * loopbackHostName + "-" + pid} identity collided across replicas that share a container hostname
 * and run as PID 1, causing each to discard the other's pub/sub events as self-echoes. The id now
 * carries a per-boot random UUID suffix so two replicas can never compute the same value.
 */
class SseBroadcasterInstanceIdTest {

  @Test
  void instanceIdCarriesAPerBootUuidSuffixSoReplicasCannotCollide() throws Exception {
    Field field = SseBroadcaster.class.getDeclaredField("INSTANCE_ID");
    field.setAccessible(true);
    String instanceId = (String) field.get(null);

    assertThat(instanceId).contains("-");
    // The trailing segment must be a real UUID — host/PID alone is no longer the whole identity.
    String suffix = instanceId.substring(instanceId.length() - 36);
    UUID parsed = UUID.fromString(suffix);
    assertThat(parsed.toString()).isEqualTo(suffix);
  }
}
