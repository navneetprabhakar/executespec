package com.navneet.executespec.project.pr;

import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.StalenessService;
import com.navneet.executespec.notification.InAppNotificationService;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runs after a {@link PullRequestRecord} flips OPEN → MERGED. Best-effort:
 * any failure logs and moves on so refresh callers always see a consistent
 * status update even if a downstream step blows up.
 *
 * <p>Cascade steps:
 * <ol>
 *   <li>Invalidate the project's staleness cache (next dashboard fetch shows fresh STALE state).</li>
 *   <li>Trigger a fresh {@code /analyse} for the project so codemaps + architecture pick up the merged code.</li>
 *   <li>Notify the run's creator that the PR landed.</li>
 * </ol>
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
     * Transient marker so the project dashboard can render a "Continue building
     * on the merged code" banner when the user next lands there. Polled by
     * {@code GET /api/projects/{id}/post-merge-bar}; lives 10 min.
     */
    public record PostMergeBar(
            String repoFullName, Integer prNumber, String mergedAt) {}

    static final String POST_MERGE_KEY_PREFIX = "project:post-merge:";
    static final java.time.Duration POST_MERGE_TTL = java.time.Duration.ofMinutes(10);

    public void onMerged(PullRequestRecord record) {
        if (record == null) return;
        String projectId = resolveProjectId(record);
        if (projectId == null) {
            log.debug("PostMergeCascade: PR {} has no project — skipping cascade", record.getId());
            return;
        }
        try {
            stalenessService.invalidate(projectId);
        } catch (Exception e) {
            log.warn("PostMergeCascade: invalidate failed for {}: {}", projectId, e.getMessage());
        }
        try {
            architectureService.generate(projectId);
            log.info("PostMergeCascade: regenerated arch for project {} after PR {} merged",
                    projectId, record.getRepoFullName() + "#" + record.getPrNumber());
        } catch (Exception e) {
            log.warn("PostMergeCascade: regenerate failed for {}: {}", projectId, e.getMessage());
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
     * Writes the PostMergeBar marker to Redis so the dashboard can render a
     * "Continue building" banner next time the user lands on it. Absent key =
     * no banner; TTL keeps the banner from going stale after the user has
     * already seen it.
     */
    private void writePostMergeBar(PullRequestRecord record, String projectId) throws Exception {
        String key = POST_MERGE_KEY_PREFIX + projectId;
        String mergedAt = record.getMergedAt() == null ? null : record.getMergedAt().toString();
        PostMergeBar bar = new PostMergeBar(record.getRepoFullName(), record.getPrNumber(), mergedAt);
        redis.opsForValue().set(key, objectMapper.writeValueAsString(bar), POST_MERGE_TTL);
    }

    /** Read + consume the marker — called by the controller. Returns null when
     *  absent. We intentionally do NOT delete on read; the TTL handles cleanup
     *  and the user can refresh the page to re-see the banner within its window. */
    public PostMergeBar readPostMergeBar(String projectId) {
        try {
            String cached = redis.opsForValue().get(POST_MERGE_KEY_PREFIX + projectId);
            if (cached == null) return null;
            return objectMapper.readValue(cached, PostMergeBar.class);
        } catch (Exception e) {
            log.debug("readPostMergeBar failed for {}: {}", projectId, e.getMessage());
            return null;
        }
    }

    /** Dismiss the marker — called by the controller when the user clicks
     *  "Start next requirement" or explicitly dismisses the banner. */
    public void dismissPostMergeBar(String projectId) {
        try {
            redis.delete(POST_MERGE_KEY_PREFIX + projectId);
        } catch (Exception e) {
            log.debug("dismissPostMergeBar failed for {}: {}", projectId, e.getMessage());
        }
    }

    private String resolveProjectId(PullRequestRecord record) {
        // Prefer the run's own projectId.
        PipelineRun run = pipelineRunRepo.findById(record.getRunId()).orElse(null);
        if (run != null && run.getProjectId() != null) return run.getProjectId();
        // Fall back: find any project that links the same repo.
        if (record.getRepoId() != null) {
            List<ProjectRepoLink> links = linkRepo.findByRepoId(record.getRepoId());
            if (!links.isEmpty()) return links.get(0).getProjectId();
        }
        return null;
    }

    private void notifyOwner(PullRequestRecord record, String projectId) {
        PipelineRun run = pipelineRunRepo.findById(record.getRunId()).orElse(null);
        String owner = run != null ? run.getCreatedByUserId() : null;
        if (owner == null || owner.isBlank()) return;
        ProjectRepo repo = record.getRepoId() == null ? null
                : repoRepo.findById(record.getRepoId()).orElse(null);
        String repoName = repo != null ? repo.getGithubRepoFullName() : record.getRepoFullName();
        notifications.create(
                owner,
                "PR_MERGED",
                "PR merged: " + repoName + "#" + record.getPrNumber(),
                "Project codemap + architecture refreshed automatically.",
                "/projects/" + projectId + "/dashboard");
    }
}
