package com.navneet.executespec.deployment;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.state.entity.Deployment;
import com.navneet.executespec.state.entity.Deployment.DeployStatus;
import com.navneet.executespec.state.repository.DeploymentRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeploymentCleanupJob {

  private final DeploymentRepository deploymentRepo;
  private final DeploymentService deploymentService;

  @Scheduled(cron = "${agent.deployment.cleanup-cron}")
  public void cleanupExpired() {
    if (!deploymentService.isEnabled()) return;

    String now = Instant.now().toString();
    List<Deployment> expired =
        deploymentRepo.findByStatusAndTtlExpiresAtBefore(DeployStatus.LIVE, now);

    if (expired.isEmpty()) {
      log.debug("TTL cleanup: no expired deployments");
      return;
    }

    log.info("TTL cleanup: {} expired deployments", expired.size());

    for (Deployment d : expired) {
      try {
        deploymentService.cleanupDeployment(d, "ttl_expired");
      } catch (Exception e) {
        log.error("TTL cleanup failed for {}: {}", d.getVersionTag(), e.getMessage());
      }
    }

    // Cost warning
    long liveCount = deploymentRepo.findByStatus(DeployStatus.LIVE).size();
    if (liveCount > 5) {
      log.warn("⚠ {} live deployments — consider cleaning up to reduce cost", liveCount);
    }
  }
}
