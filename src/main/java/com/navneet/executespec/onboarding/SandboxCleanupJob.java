package com.navneet.executespec.onboarding;

import java.time.Duration;
import java.time.Instant;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Wave 2.2 — hourly GC for abandoned sandbox projects.
 *
 * <p>A sandbox is "abandoned" when:
 *
 * <ul>
 *   <li>{@code is_sandbox = true}
 *   <li>its most recent pipeline run's {@code startedAt} is older than 24h (or no run exists AND
 *       project.updatedAt is older than 24h)
 * </ul>
 *
 * <p>On GC we archive the project row (status=ARCHIVED) — we do NOT hard- delete because foreign
 * keys on pipeline_run + validation_report would cascade and break historical analytics. The
 * frontend filters ARCHIVED sandboxes out of every list.
 *
 * <p>{@link SchedulerLock} prevents double-firing across app instances (MIG / Cloud Run).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SandboxCleanupJob {

  private static final Duration IDLE_THRESHOLD = Duration.ofHours(24);

  private final ProjectRepository projectRepo;
  private final AppUserRepository userRepo;
  private final PipelineRunRepository pipelineRunRepo;

  /** Hourly at :05 past the hour. */
  @Scheduled(cron = "0 5 * * * *")
  @SchedulerLock(name = "sandboxCleanup", lockAtLeastFor = "PT1M", lockAtMostFor = "PT15M")
  @Transactional
  public void cleanup() {
    Instant cutoff = Instant.now().minus(IDLE_THRESHOLD);
    long scanned = 0;
    long archived = 0;
    for (Project p : projectRepo.findByIsSandboxTrueAndStatus("ACTIVE")) {
      scanned++;
      // Last-run-based idle detection: use the most recent pipeline_run
      // on this project. No run + project > 24h old → archive. Any run
      // more recent than the cutoff → keep alive.
      long recentRuns =
          pipelineRunRepo.countByProjectIdAndStartedAtGreaterThanEqual(p.getId(), cutoff);
      if (recentRuns > 0) continue;

      Instant updated = p.getUpdatedAt() == null ? p.getCreatedAt() : p.getUpdatedAt();
      if (updated == null || !updated.isBefore(cutoff)) continue;

      p.setStatus("ARCHIVED");
      p.setUpdatedAt(Instant.now());
      projectRepo.save(p);

      // Null-out app_user.sandbox_project_id so the user gets a fresh
      // sandbox on next /welcome visit.
      userRepo
          .findById(p.getCreatedBy())
          .ifPresent(
              u -> {
                if (p.getId().equals(u.getSandboxProjectId())) {
                  u.setSandboxProjectId(null);
                  userRepo.save(u);
                }
              });
      archived++;
    }
    log.info("[SandboxCleanup] scanned={} archived={} cutoff={}", scanned, archived, cutoff);
  }
}
