package com.navneet.executespec.project.pr;

import java.util.List;

import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.StalenessService;
import com.navneet.executespec.notification.InAppNotificationService;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs after a {@link PullRequestRecord} flips OPEN → MERGED. Best-effort: any failure logs and
 * moves on so refresh callers always see a consistent status update even if a downstream step blows
 * up.
 *
 * <p>Two entry points, split by latency profile:
 *
 * <ul>
 *   <li>{@link #onMerged(PullRequestRecord)} — per-PR fast path (notify owner + write the
 *       post-merge banner). Sync, ~ms, called once per merged PR. Safe to invoke inline from a
 *       request thread.
 *   <li>{@link #regenerateAfterMerge(String)} — per-project heavy path (staleness invalidation +
 *       architecture regeneration via {@code ProjectArchitectureService.generate}). Annotated
 *       {@code @Async}, fire-and-forget. Callers should DEDUPE by projectId — one merge batch
 *       producing 3 PR-merge events into the same project should fire this method once, not three
 *       times.
 * </ul>
 *
 * <p>2026-05-04 P0 fix #3 — the original {@code onMerged} folded both phases into a single sync
 * call. With 3 PRs all merging into one project, that produced 3 sequential {@code
 * architectureService.generate(projectId)} invocations, each of which spans codemap regeneration +
 * architecture-doc LLM call (~30-60s). Total wall-clock easily blew past the 100s Cloudflare edge
 * timeout → HTTP 524. Splitting + async-firing the heavy path lets refresh return fast.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PostMergeCascadeService {

  private final StalenessService stalenessService;
  private final ProjectArchitectureService architectureService;
  private final InAppNotificationService notifications;
  private final PipelineRunRepository pipelineRunRepo;
  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepoRepository repoRepo;
  private final org.springframework.data.redis.core.StringRedisTemplate redis;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  /**
   * Transient marker so the project dashboard can render a "Continue building on the merged code"
   * banner when the user next lands there. Polled by {@code GET /api/projects/{id}/post-merge-bar};
   * lives 10 min.
   */
  public record PostMergeBar(String repoFullName, Integer prNumber, String mergedAt) {}

  static final String POST_MERGE_KEY_PREFIX = "project:post-merge:";
  static final java.time.Duration POST_MERGE_TTL = java.time.Duration.ofMinutes(10);

  /** Max banners kept in the Redis list before older entries are trimmed away. */
  static final int POST_MERGE_MAX_BANNERS = 3;

  /**
   * Per-PR fast path. Notifies the run's creator + writes the dashboard "Continue building" banner.
   * Sync, ~ms. The heavy work (staleness invalidate + arch regen) lives in {@link
   * #regenerateAfterMerge(String)} — caller is responsible for firing that ONCE per unique project
   * after collecting every merge in the current batch.
   */
  public void onMerged(PullRequestRecord record) {
    if (record == null) return;
    String projectId = resolveProjectId(record);
    if (projectId == null) {
      log.debug("PostMergeCascade: PR {} has no project — skipping cascade", record.getId());
      return;
    }
    try {
      notifyOwner(record, projectId);
    } catch (Exception e) {
      log.warn("PostMergeCascade: notification failed for {}: {}", projectId, e.getMessage());
    }
    try {
      writePostMergeBar(record, projectId);
    } catch (Exception e) {
      log.warn("PostMergeCascade: bar write failed for {}: {}", projectId, e.getMessage());
    }
  }

  /**
   * Per-project heavy path. Invalidates staleness cache + regenerates project architecture. Async
   * fire-and-forget — callers must NOT await the future (none returned). Safe under the {@code
   * AsyncAllowlistTest} guard: takes a primitive String, no JPA navigation in this method body, and
   * {@code ProjectArchitectureService.generate} owns its own per-save txs (per the v2.5.2 design
   * note in {@code project/CLAUDE.md}). Internal try/catch ensures async-thread crashes never go
   * silent — every failure logs at WARN.
   */
  @Async
  public void regenerateAfterMerge(String projectId) {
    if (projectId == null || projectId.isBlank()) return;
    try {
      stalenessService.invalidate(projectId);
    } catch (Exception e) {
      log.warn("PostMergeCascade(async): invalidate failed for {}: {}", projectId, e.getMessage());
    }
    try {
      architectureService.generate(projectId);
      log.info("PostMergeCascade(async): regenerated arch for project {}", projectId);
    } catch (Exception e) {
      log.warn("PostMergeCascade(async): regenerate failed for {}: {}", projectId, e.getMessage());
    }
  }

  /**
   * Public so callers (currently {@link PrRefreshService}) can dedupe per-project before firing the
   * async {@link #regenerateAfterMerge(String)}. Same logic as before — V169 system PRs use the
   * {@code project_id} column directly; run-scoped PRs walk the {@code PipelineRun}; final fallback
   * is the repo→project link table.
   */
  public String resolveProjectId(PullRequestRecord record) {
    return resolveProjectIdInternal(record);
  }

  /**
   * Push a PostMergeBar entry onto the Redis list so the dashboard can render a "Continue building"
   * banner next time the user lands on it. The list is capped at {@value #POST_MERGE_MAX_BANNERS} —
   * rapid-fire merges get stacked up to that limit; older entries are trimmed. TTL refreshed on
   * every push so the window slides with activity.
   */
  private void writePostMergeBar(PullRequestRecord record, String projectId) throws Exception {
    String key = POST_MERGE_KEY_PREFIX + projectId;
    String mergedAt = record.getMergedAt() == null ? null : record.getMergedAt().toString();
    PostMergeBar bar = new PostMergeBar(record.getRepoFullName(), record.getPrNumber(), mergedAt);
    String payload = objectMapper.writeValueAsString(bar);
    redis.opsForList().leftPush(key, payload);
    redis.opsForList().trim(key, 0, POST_MERGE_MAX_BANNERS - 1);
    redis.expire(key, POST_MERGE_TTL);
  }

  /**
   * Read all live banner entries, newest first. Returns an empty list when no marker is active. The
   * TTL handles cleanup; we intentionally do NOT consume on read so reloads within the window keep
   * showing the banner.
   */
  public List<PostMergeBar> readPostMergeBars(String projectId) {
    try {
      List<String> cached = redis.opsForList().range(POST_MERGE_KEY_PREFIX + projectId, 0, -1);
      if (cached == null || cached.isEmpty()) return List.of();
      List<PostMergeBar> out = new java.util.ArrayList<>(cached.size());
      for (String s : cached) {
        try {
          out.add(objectMapper.readValue(s, PostMergeBar.class));
        } catch (Exception ignore) {
        }
      }
      return out;
    } catch (Exception e) {
      log.debug("readPostMergeBars failed for {}: {}", projectId, e.getMessage());
      return List.of();
    }
  }

  /** Dismiss every banner for a project — called when the user clicks the X. */
  public void dismissPostMergeBars(String projectId) {
    try {
      redis.delete(POST_MERGE_KEY_PREFIX + projectId);
    } catch (Exception e) {
      log.debug("dismissPostMergeBars failed for {}: {}", projectId, e.getMessage());
    }
  }

  /**
   * 2026-05-04 — V169 made {@code run_id} nullable so system PRs (BOOTSTRAP / ARCHITECTURE /
   * CONTRACT / CHAT_TOOL) can persist on the project dashboard. Spring Data JPA's {@code
   * findById(null)} throws {@code IllegalArgumentException("The given id must not be null")} which
   * the caller's try/catch swallows — but Spring's transaction interception has already marked the
   * outer {@code @Transactional} as rollback-only by then, so the eventual commit blows up with
   * {@code UnexpectedRollbackException} → 500. Two fixes below: (1) prefer {@code
   * record.getProjectId()} (V169 column) so we skip the run lookup entirely for system PRs, (2)
   * null-guard the runId before calling {@code findById}.
   */
  private String resolveProjectIdInternal(PullRequestRecord record) {
    // V169 system PRs set projectId directly — no run lookup needed.
    if (record.getProjectId() != null && !record.getProjectId().isBlank()) {
      return record.getProjectId();
    }
    // Run-scoped PRs: walk through the linked PipelineRun. Defensive null check so a stray
    // record with runId=null and projectId=null can't crash with "id must not be null".
    if (record.getRunId() != null && !record.getRunId().isBlank()) {
      PipelineRun run = pipelineRunRepo.findById(record.getRunId()).orElse(null);
      if (run != null && run.getProjectId() != null) return run.getProjectId();
    }
    // Fall back: find any project that links the same repo.
    if (record.getRepoId() != null) {
      List<ProjectRepoLink> links = linkRepo.findByRepoId(record.getRepoId());
      if (!links.isEmpty()) return links.get(0).getProjectId();
    }
    return null;
  }

  private void notifyOwner(PullRequestRecord record, String projectId) {
    // System PRs (V169) have no originating run, so there's no per-run owner to notify here.
    // The project's notification fan-out (NotificationPrefService → org admins) handles those.
    if (record.getRunId() == null || record.getRunId().isBlank()) return;
    PipelineRun run = pipelineRunRepo.findById(record.getRunId()).orElse(null);
    String owner = run != null ? run.getCreatedByUserId() : null;
    if (owner == null || owner.isBlank()) return;
    ProjectRepo repo =
        record.getRepoId() == null ? null : repoRepo.findById(record.getRepoId()).orElse(null);
    String repoName = repo != null ? repo.getGithubRepoFullName() : record.getRepoFullName();
    notifications.create(
        owner,
        "PR_MERGED",
        "PR merged: " + repoName + "#" + record.getPrNumber(),
        "Project codemap + architecture refreshed automatically.",
        "/projects/" + projectId + "/dashboard");
  }
}
