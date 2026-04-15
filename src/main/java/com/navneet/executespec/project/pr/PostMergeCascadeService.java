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
