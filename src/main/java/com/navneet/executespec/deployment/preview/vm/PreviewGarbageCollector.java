package com.navneet.executespec.deployment.preview.vm;

import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Authoritative GC for VM-based previews. Runs every 10 minutes, finds previews past expiry in the
 * registry and tears them down. VM-level cron GC is a safety net.
 */
@Component
@Slf4j
@RequiredArgsConstructor
// RR-1044: the VM/SSH preview machinery must be gated on BOTH the selected mode AND the
// preview.vm.enabled switch (default false), so selecting mode=vm without enabling it no longer
// instantiates SSH machinery. When absent, PreviewService falls back to its "enable preview.vm"
// error.
@ConditionalOnExpression("'${preview.mode:}'.equals('vm') and ${preview.vm.enabled:false}")
public class PreviewGarbageCollector {

  private static final int MAX_PER_SWEEP = 100;

  private final PreviewDeploymentService previewService;
  private final PreviewRegistryRepository registryRepo;

  @Scheduled(fixedRate = 600_000) // 10 minutes
  public void collectExpired() {
    Instant cutoff = Instant.now();
    List<PreviewRegistry> expired =
        registryRepo.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
            "active", cutoff, PageRequest.of(0, MAX_PER_SWEEP));

    for (PreviewRegistry entry : expired) {
      if (registryRepo.claimExpiredForGc(entry.getId(), cutoff) != 1) {
        continue;
      }
      try {
        previewService.teardownClaimed(entry.getId());
        log.info("GC: torn down expired preview {}", entry.getId());
      } catch (Exception e) {
        log.error("GC: failed to teardown {}", entry.getId(), e);
        entry.setStatus("failed");
        entry.setErrorMessage("GC teardown failed: " + e.getMessage());
        registryRepo.save(entry);
      }
    }
  }
}
