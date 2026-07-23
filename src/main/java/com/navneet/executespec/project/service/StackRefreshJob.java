package com.navneet.executespec.project.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Pilot v3.0 (deferral #3) — refreshes detected_stack + last_synced_sha for every {@code
 * project_repo} once a day at 03:00. Skips repos refreshed in the last 24h (idempotency under
 * retries) and rate-limits with a small sleep between API hits to stay well under GitHub's 5000
 * req/h authenticated limit.
 *
 * <p>Counters: {@code executespec.stack.refresh.changed}, {@code
 * executespec.stack.refresh.unchanged}, {@code executespec.stack.refresh.failed}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StackRefreshJob {

  private static final Duration MIN_REFRESH_AGE = Duration.ofHours(20);
  private static final long PACING_MS = 200;
  private static final int MAX_PER_SWEEP = 100;

  private final ProjectRepoRepository repoRepo;
  private final ProjectRepoLinkRepository linkRepo;
  private final AppUserRepository userRepo;
  private final StackDetector stackDetector;
  private final GitHubRepoService gitHub;
  private final MeterRegistry meterRegistry;

  @Scheduled(cron = "0 0 3 * * *")
  @SchedulerLock(name = "StackRefreshJob", lockAtMostFor = "55m", lockAtLeastFor = "1m")
  public void refreshAll() {
    runOnce();
  }

  /** Manual entry point for tests / admin tooling. */
  public void runOnce() {
    Counter changed = Counter.builder("executespec.stack.refresh.changed").register(meterRegistry);
    Counter unchanged =
        Counter.builder("executespec.stack.refresh.unchanged").register(meterRegistry);
    Counter failed = Counter.builder("executespec.stack.refresh.failed").register(meterRegistry);

    Instant cutoff = Instant.now().minus(MIN_REFRESH_AGE);
    List<ProjectRepo> candidates =
        repoRepo.findStackRefreshCandidates(cutoff, PageRequest.of(0, MAX_PER_SWEEP));
    int seen = 0;
    for (ProjectRepo repo : candidates) {
      String credentialUserId = resolveCredentialUserId(repo);
      String pat =
          credentialUserId == null
              ? null
              : userRepo.findById(credentialUserId).map(user -> user.getGithubPat()).orElse(null);
      if (pat == null || pat.isBlank()) {
        failed.increment();
        log.warn(
            "StackRefreshJob skipped repo {} because no current linked-user GitHub credential is"
                + " available",
            repo.getGithubRepoFullName());
        continue;
      }
      try {
        String fresh = stackDetector.detect(repo.getGithubRepoFullName(), pat);
        String headSha =
            gitHub.getBranchHeadSha(
                "https://github.com/" + repo.getGithubRepoFullName(), repo.getDefaultBranch(), pat);
        boolean stackChanged = !Objects.equals(repo.getDetectedStack(), fresh);
        boolean shaChanged = headSha != null && !Objects.equals(repo.getLastSyncedSha(), headSha);
        if (stackChanged) repo.setDetectedStack(fresh);
        if (shaChanged) repo.setLastSyncedSha(headSha);
        repo.setUpdatedAt(Instant.now());
        repoRepo.save(repo);
        if (stackChanged || shaChanged) changed.increment();
        else unchanged.increment();
      } catch (Exception e) {
        failed.increment();
        log.warn(
            "StackRefreshJob failed for repo {}: {}", repo.getGithubRepoFullName(), e.getMessage());
      }
      seen++;
      try {
        Thread.sleep(PACING_MS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    log.info(
        "StackRefreshJob processed {}/{} bounded candidates (max={})",
        seen,
        candidates.size(),
        MAX_PER_SWEEP);
  }

  private String resolveCredentialUserId(ProjectRepo repo) {
    if (repo.getOwnerUserId() != null && !repo.getOwnerUserId().isBlank()) {
      return repo.getOwnerUserId();
    }
    return linkRepo
        .findFirstByRepoIdOrderByIdAsc(repo.getId())
        .map(link -> link.getLinkedBy())
        .filter(id -> id != null && !id.isBlank())
        .orElse(null);
  }
}
