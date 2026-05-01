package com.navneet.executespec.project.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * v2.5.2 — per-repo workspace health for the Project Overview tab.
 *
 * <p>Rolls up four signals per linked repo:
 *
 * <ul>
 *   <li><b>SHA drift:</b> latest codemap's {@code generatedFromSha} vs current origin HEAD on the
 *       repo's default branch.
 *   <li><b>Webhook installed:</b> {@link ProjectRepo#isWebhookInstalled()}.
 *   <li><b>Open PR count:</b> {@code pull_request_record.status='OPEN'} for the repo's full name.
 *   <li><b>Last analysed at:</b> newest codemap's {@code generatedAt}.
 * </ul>
 *
 * <p>Current-origin SHA lookups are cached in Redis for 10 min per repo ({@code
 * project:origin-sha:<repoId>}) so the dashboard doesn't hammer the GitHub API on polls. Cache key
 * is repo-scoped, not project-scoped, so multiple projects linking the same repo share the cached
 * lookup.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceHealthService {

  static final String ORIGIN_SHA_PREFIX = "project:origin-sha:";
  static final Duration ORIGIN_SHA_TTL = Duration.ofMinutes(10);

  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepoRepository repoRepo;
  private final ProjectCodeMapRepository codeMapRepo;
  private final PullRequestRecordRepository prRepo;
  private final GitHubRepoService gitHub;
  private final AppUserRepository userRepo;
  private final StringRedisTemplate redis;

  public record RepoHealth(
      String repoId,
      String repoName,
      String defaultBranch,
      String lastCodeMapSha,
      String currentOriginSha,
      boolean isStale,
      boolean webhookInstalled,
      long openPrCount,
      Instant lastAnalysedAt) {}

  public record Report(List<RepoHealth> repos, Instant lastRefreshedAt) {}

  public Report report(String projectId, String callerUserId) {
    String pat =
        callerUserId == null
            ? null
            : userRepo.findById(callerUserId).map(AppUser::getGithubPat).orElse(null);

    List<ProjectRepoLink> links = linkRepo.findByProjectId(projectId);
    Map<java.util.UUID, ProjectCodeMap> latestByRepo = new java.util.HashMap<>();
    for (ProjectCodeMap cm : codeMapRepo.findByProjectId(projectId)) {
      ProjectCodeMap prev = latestByRepo.get(cm.getRepoId());
      if (prev == null
          || (cm.getVersion() != null
              && (prev.getVersion() == null || cm.getVersion() > prev.getVersion()))) {
        latestByRepo.put(cm.getRepoId(), cm);
      }
    }

    List<RepoHealth> rows = new ArrayList<>();
    for (ProjectRepoLink link : links) {
      ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
      if (repo == null) continue;
      ProjectCodeMap cm = latestByRepo.get(repo.getId());
      String lastSha = cm == null ? null : cm.getGeneratedFromSha();
      String currentSha = resolveOriginSha(repo, pat);
      boolean stale = currentSha != null && (lastSha == null || !lastSha.equals(currentSha));
      long openPrs =
          prRepo.countByRepoFullNameAndStatus(
              repo.getGithubRepoFullName(), PullRequestRecord.Status.OPEN);
      rows.add(
          new RepoHealth(
              repo.getId().toString(),
              repo.getGithubRepoFullName(),
              repo.getDefaultBranch(),
              lastSha,
              currentSha,
              stale,
              repo.isWebhookInstalled(),
              openPrs,
              cm == null ? null : cm.getGeneratedAt()));
    }
    return new Report(rows, Instant.now());
  }

  /** Cached origin-HEAD SHA for the repo's default branch. Null on GitHub failure. */
  private String resolveOriginSha(ProjectRepo repo, String pat) {
    String key = ORIGIN_SHA_PREFIX + repo.getId();
    try {
      String cached = redis.opsForValue().get(key);
      if (cached != null && !cached.isBlank()) return cached;
    } catch (Exception e) {
      log.debug("Redis origin-sha read failed for {}: {}", repo.getId(), e.getMessage());
    }
    String fresh =
        gitHub.getBranchHeadSha(
            "https://github.com/" + repo.getGithubRepoFullName(), repo.getDefaultBranch(), pat);
    if (fresh != null && !fresh.isBlank()) {
      try {
        redis.opsForValue().set(key, fresh, ORIGIN_SHA_TTL);
      } catch (Exception e) {
        log.debug("Redis origin-sha write failed for {}: {}", repo.getId(), e.getMessage());
      }
    }
    return fresh;
  }
}
