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

  // RR-1585 — key namespace bumped to v2 because the report gained explicit UNKNOWN /
  // NOT_MEASURABLE rows. A v1 payload written by an older node must not deserialize into the new
  // record shape and silently present unmeasured repos as fresh.
  static final String KEY_PREFIX = "project:stale:v2:";
  static final Duration TTL = Duration.ofMinutes(5);

  private final ProjectArchitectureService archService;
  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepoRepository repoRepo;
  private final GitHubRepoService gitHub;
  private final ObjectMapper objectMapper;
  private final StringRedisTemplate redis;

  /**
   * RR-1585 — per-repo freshness verdict. {@link #FRESH} and {@link #STALE} are the only states
   * backed by an actual HEAD probe; every other state means the system has no evidence and must not
   * claim the architecture matches HEAD.
   */
  public enum RepoState {
    /** Probe succeeded and the source has not moved since the codemap was generated. */
    FRESH,
    /** Probe succeeded and the source moved outside {@code .executespec/}. */
    STALE,
    /**
     * The current HEAD SHA could not be read — missing/expired PAT, SCM outage, rate limit, renamed
     * or deleted repo. Freshness is unknown; it is NOT fresh.
     */
    UNKNOWN,
    /**
     * The repo is outside this metric's reach at all: a LOCAL source, a non-GitHub SCM provider, or
     * a link whose registry row is gone. Counted, never silently dropped.
     */
    NOT_MEASURABLE
  }

  public record RepoStaleness(
      String repoFullName,
      String generatedFromSha,
      String currentSha,
      boolean stale,
      RepoState state,
      String reason) {}

  /**
   * RR-1585 — {@code stale} means "at least one repo was PROVEN stale". It is deliberately not
   * widened to cover UNKNOWN rows; callers that need "is this verdict trustworthy at all" read
   * {@link #verified()}, which is false whenever any linked repo could not be measured. {@code
   * totalCount} counts every linked repo including unmeasurable ones, so an all-LOCAL or
   * all-unprobeable project no longer reports {@code totalCount=0, stale=false}.
   */
  public record StalenessReport(
      boolean stale,
      int staleCount,
      int totalCount,
      int unknownCount,
      int notMeasurableCount,
      boolean verified,
      List<RepoStaleness> repos) {}

  public StalenessReport report(String projectId) {
    return report(projectId, null);
  }

  /**
   * v2.5.12 — caller supplies their GitHub PAT so the current-SHA probe can reach private repos.
   * Without this, {@code getBranchHeadSha} passes null and the system PAT fallback 404s on every
   * private-org repo, flooding logs. Cache is keyed on projectId only — a SHA that was actually
   * fetched is objective regardless of who fetched it.
   *
   * <p>RR-1585 — a report containing any {@link RepoState#UNKNOWN} row is NEVER written to the
   * shared cache. Previously an unauthenticated (or rate-limited) probe that null-returned for
   * every private repo cached "everything fresh" for five minutes and served that verdict to every
   * subsequent viewer, including authenticated ones. Only fully-probed reports are shareable.
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
    if (fresh.unknownCount() > 0) {
      log.debug(
          "Staleness report for project {} has {} unprobeable repo(s) — not caching",
          projectId,
          fresh.unknownCount());
      return fresh;
    }
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
    // V192 (PR-M, 2026-05-13) — per-repo branches the codemap was generated from. Falls back to
    // defaultBranch on legacy rows that never had this column populated.
    Map<String, String> generatedBranches = parseBranches(latest);

    List<ProjectRepoLink> links = linkRepo.findByProjectId(projectId);
    List<RepoStaleness> repoReports = new java.util.ArrayList<>();
    int staleCount = 0;
    int unknownCount = 0;
    int notMeasurableCount = 0;
    for (ProjectRepoLink link : links) {
      ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
      if (repo == null) {
        // RR-1585 — a link whose registry row is gone used to `continue`, dropping the repo out of
        // totalCount so the project looked smaller AND fully fresh. Record it as unmeasurable.
        notMeasurableCount++;
        repoReports.add(
            new RepoStaleness(
                String.valueOf(link.getRepoId()),
                null,
                null,
                false,
                RepoState.NOT_MEASURABLE,
                "linked repo record not found"));
        continue;
      }
      // Architecture/codemap generation is currently a GitHub-only capability. Exclude LOCAL and
      // Bitbucket browse-only sources from this GitHub SHA/diff metric instead of fabricating a
      // github.com/null URL and presenting the result as provider-neutral evidence.
      // RR-1585 — the exclusion stands, but the repo is now RECORDED as NOT_MEASURABLE rather than
      // skipped: an all-LOCAL project used to report totalCount=0 / stale=false, i.e. "verified
      // fresh", when nothing had been checked at all.
      if (ProjectRepo.SourceType.LOCAL.name().equals(repo.getSourceType())
          || (repo.getScmProvider() != null && !"GITHUB".equalsIgnoreCase(repo.getScmProvider()))) {
        notMeasurableCount++;
        repoReports.add(
            new RepoStaleness(
                repo.getGithubRepoFullName(),
                generatedShas.get(repo.getGithubRepoFullName()),
                null,
                false,
                RepoState.NOT_MEASURABLE,
                ProjectRepo.SourceType.LOCAL.name().equals(repo.getSourceType())
                    ? "LOCAL source — no remote HEAD to compare against"
                    : "non-GitHub SCM provider ("
                        + repo.getScmProvider()
                        + ") — freshness probe unavailable"));
        continue;
      }
      String generated = generatedShas.get(repo.getGithubRepoFullName());
      // V192 (PR-M) — probe the branch the codemap was generated from. Legacy rows
      // (generatedBranches empty for this repo) keep the old defaultBranch behaviour.
      String probeBranch =
          generatedBranches.getOrDefault(repo.getGithubRepoFullName(), repo.getDefaultBranch());
      String current =
          gitHub.getBranchHeadSha(
              "https://github.com/" + repo.getGithubRepoFullName(), probeBranch, overridePat);
      // Stale only if the SOURCE changed. Ignore .executespec/-only diffs — ExecuteSpec publishes
      // its own codemaps under .executespec/, which moves HEAD but doesn't change source; without
      // this the dashboard shows a permanent STALE after each published-codemap merge
      // (RL-2026-05-025).
      boolean stale;
      RepoState state;
      String reason = null;
      if (current == null) {
        // RR-1585 — a null HEAD SHA means the probe FAILED (missing/expired PAT, SCM outage, rate
        // limit, renamed or deleted repo). That is not evidence the codemap is current, so it no
        // longer collapses into stale=false / "fresh".
        stale = false;
        state = RepoState.UNKNOWN;
        reason = "current HEAD sha could not be read for branch " + probeBranch;
        unknownCount++;
      } else if (generated == null) {
        stale = true; // never generated
        state = RepoState.STALE;
        reason = "no codemap generated for this repo";
      } else if (generated.equals(current)) {
        stale = false;
        state = RepoState.FRESH;
      } else {
        stale =
            !gitHub.diffConfinedTo(
                repo.getGithubRepoFullName(), generated, current, ".executespec/", overridePat);
        state = stale ? RepoState.STALE : RepoState.FRESH;
        if (!stale) reason = "sha moved but the diff is confined to .executespec/";
      }
      if (stale) staleCount++;
      repoReports.add(
          new RepoStaleness(
              repo.getGithubRepoFullName(), generated, current, stale, state, reason));
    }
    return new StalenessReport(
        staleCount > 0,
        staleCount,
        repoReports.size(),
        unknownCount,
        notMeasurableCount,
        unknownCount == 0 && notMeasurableCount == 0,
        repoReports);
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

  /**
   * V192 (PR-M) — parse the per-repo branch map. Returns empty map on null/blank/malformed input so
   * the caller's getOrDefault gracefully falls back to defaultBranch.
   */
  private Map<String, String> parseBranches(ProjectArchitectureDoc doc) {
    if (doc == null
        || doc.getGeneratedFromBranches() == null
        || doc.getGeneratedFromBranches().isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(doc.getGeneratedFromBranches(), new TypeReference<>() {});
    } catch (Exception e) {
      log.warn(
          "Failed to parse generatedFromBranches for project {}: {}",
          doc.getProjectId(),
          e.getMessage());
      return Map.of();
    }
  }
}
