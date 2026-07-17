package com.navneet.executespec.deployment.preview.vm;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class PreviewGarbageCollectorTest {

  private final PreviewDeploymentService deployment = mock(PreviewDeploymentService.class);
  private final PreviewRegistryRepository registry = mock(PreviewRegistryRepository.class);
  private final PreviewGarbageCollector collector =
      new PreviewGarbageCollector(deployment, registry);

  @Test
  void onlyTheNodeThatAtomicallyClaimsAnExpiredPreviewTearsItDown() {
    PreviewRegistry won = expired("won");
    PreviewRegistry lost = expired("lost");
    when(registry.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
            eq("active"), any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of(won, lost));
    when(registry.claimExpiredForGc(eq("won"), any(Instant.class))).thenReturn(1);
    when(registry.claimExpiredForGc(eq("lost"), any(Instant.class))).thenReturn(0);

    collector.collectExpired();

    verify(deployment).teardownClaimed("won");
    verify(deployment, never()).teardownClaimed("lost");
  }

  private static PreviewRegistry expired(String id) {
    return PreviewRegistry.builder()
        .id(id)
        .executionId("run-" + id)
        .url("https://preview.example/" + id)
        .vmId("vm-1")
        .vmIp("10.0.0.1")
        .status("active")
        .createdAt(Instant.now().minusSeconds(600))
        .expiresAt(Instant.now().minusSeconds(60))
        .ttlHours(1)
        .build();
  }
}
