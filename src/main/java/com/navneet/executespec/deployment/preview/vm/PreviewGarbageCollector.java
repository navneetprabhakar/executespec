package com.navneet.executespec.deployment.preview.vm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Authoritative GC for VM-based previews. Runs every 10 minutes,
 * finds previews past expiry in the registry and tears them down.
 * VM-level cron GC is a safety net.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "preview.vm", name = "enabled", havingValue = "true")
public class PreviewGarbageCollector {

    private final PreviewDeploymentService previewService;
    private final PreviewRegistryRepository registryRepo;

    @Scheduled(fixedRate = 600_000) // 10 minutes
    public void collectExpired() {
        List<PreviewRegistry> expired = registryRepo
                .findByStatusAndExpiresAtBefore("active", Instant.now());

        for (PreviewRegistry entry : expired) {
            try {
                previewService.teardown(entry.getId());
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
