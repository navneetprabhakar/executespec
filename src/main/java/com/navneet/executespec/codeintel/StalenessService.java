package com.navneet.executespec.codeintel;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pilot v3.0 (deferral #4) — compares the SHAs captured when each repo's codemap was generated
 * against the current HEAD of each repo's default branch.
 *
 * <p>Cache lives in Redis so every MIG instance sees the same staleness picture. A Caffeine cache
 * (the pre-v2.5.2 implementation) kept drift after a MIG scale-out: invalidation on VM1 was
 * invisible to VM2, so a freshly regenerated architecture could still show as STALE from another
 * node. Key {@code project:stale:<id>} holds the serialized {@link StalenessReport}; absence =
 * recompute. TTL 5 min.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StalenessService {

  static final String KEY_PREFIX = "project:stale:";
  static final Duration TTL = Duration.ofMinutes(5);

  private final ProjectArchitectureService archService;
  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepoRepository repoRepo;
  private final GitHubRepoService gitHub;
  private final ObjectMapper objectMapper;
  private final StringRedisTemplate redis;

  public record RepoStaleness(
      String repoFullName, String generatedFromSha, String currentSha, boolean stale) {}

  public record StalenessReport(
      boolean stale, int staleCount, int totalCount, List<RepoStaleness> repos) {}

  public StalenessReport report(String projectId) {
    return report(projectId, null);
  }

  /**
   * v2.5.12 — caller supplies their GitHub PAT so the current-SHA probe can reach private repos.
   * Without this, {@code getBranchHeadSha} passes null and the system PAT fallback 404s on every
   * private-org repo, flooding logs. Cache is still keyed on projectId only (the SHA result is
   * objective once fetched) — first-authenticated-read wins; subsequent anonymous probes read from
   * cache until generate() invalidates.
   */
  public StalenessReport report(String projectId, String overridePat) {
    String key = KEY_PREFIX + projectId;
    try {
      String cached = redis.opsForValue().get(key);
      if (cached != null) {
        return objectMapper.readValue(cached, StalenessReport.class);
      }
    } catch (Exception e) {
      log.warn("Staleness cache read failed for project {}: {}", projectId, e.getMessage());
    }
    StalenessReport fresh = compute(projectId, overridePat);
    try {
      redis.opsForValue().set(key, objectMapper.writeValueAsString(fresh), TTL);
    } catch (Exception e) {
      log.warn("Staleness cache write failed for project {}: {}", projectId, e.getMessage());
    }
    return fresh;
  }

  /**
   * Force a refresh — call after generate() so every MIG node sees fresh SHAs on the next read.
   * Deleting the key (not writing a tombstone) lets the next caller recompute on demand.
   */
  public void invalidate(String projectId) {
    redis.delete(KEY_PREFIX + projectId);
  }

  private StalenessReport compute(String projectId, String overridePat) {
    ProjectArchitectureDoc latest = archService.latest(projectId).orElse(null);
    Map<String, String> generatedShas = parseShas(latest);

    List<ProjectRepoLink> links = linkRepo.findByProjectId(projectId);
    List<RepoStaleness> repoReports = new java.util.ArrayList<>();
    int staleCount = 0;
    for (ProjectRepoLink link : links) {
      ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
      if (repo == null) continue;
      String generated = generatedShas.get(repo.getGithubRepoFullName());
      String current =
          gitHub.getBranchHeadSha(
              "https://github.com/" + repo.getGithubRepoFullName(),
              repo.getDefaultBranch(),
              overridePat);
      boolean stale = current != null && (generated == null || !generated.equals(current));
      if (stale) staleCount++;
      repoReports.add(new RepoStaleness(repo.getGithubRepoFullName(), generated, current, stale));
    }
    return new StalenessReport(staleCount > 0, staleCount, repoReports.size(), repoReports);
  }

  private Map<String, String> parseShas(ProjectArchitectureDoc doc) {
    if (doc == null || doc.getGeneratedFromShas() == null || doc.getGeneratedFromShas().isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(doc.getGeneratedFromShas(), new TypeReference<>() {});
    } catch (Exception e) {
      log.warn(
          "Failed to parse generatedFromShas for project {}: {}",
          doc.getProjectId(),
          e.getMessage());
      return new LinkedHashMap<>();
    }
  }
}
