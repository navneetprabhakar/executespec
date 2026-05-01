package com.navneet.executespec.project.pr;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Polls GitHub for the current state of every non-terminal PR opened by the project. Used by the
 * dashboard "Refresh" button when webhooks aren't wired up (e.g. brand-new repos created via the
 * Launch flow). On any OPEN→MERGED transition delegates to {@link PostMergeCascadeService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrRefreshService {

  private final PullRequestRecordRepository prRepo;
  private final PipelineRunRepository pipelineRunRepo;
  private final GitHubRepoService github;
  private final PostMergeCascadeService cascade;

  public record RefreshResult(int checked, int updated, int newlyMerged) {}

  @Transactional
  public RefreshResult refreshProject(String projectId) {
    Set<String> projectRunIds = new HashSet<>();
    pipelineRunRepo
        .findByProjectIdOrderByStartedAtDesc(projectId, PageRequest.of(0, 500))
        .forEach(r -> projectRunIds.add(r.getRunId()));
    if (projectRunIds.isEmpty()) return new RefreshResult(0, 0, 0);

    int checked = 0, updated = 0, newlyMerged = 0;
    for (String runId : projectRunIds) {
      for (PullRequestRecord rec : prRepo.findByRunIdOrderByCreatedAtAsc(runId)) {
        if (rec.getStatus() == PullRequestRecord.Status.MERGED
            || rec.getStatus() == PullRequestRecord.Status.CLOSED) continue;
        checked++;
        try {
          var snap =
              github.getPullRequestSnapshot(rec.getRepoFullName(), rec.getPrNumber(), null, 0);
          if (snap == null) continue;
          PullRequestRecord.Status next = mapState(snap.state(), snap.merged());
          if (next != rec.getStatus()) {
            boolean wasOpen = rec.getStatus() == PullRequestRecord.Status.OPEN;
            rec.setStatus(next);
            rec.setUpdatedAt(Instant.now());
            if (next == PullRequestRecord.Status.MERGED) rec.setMergedAt(Instant.now());
            if (next == PullRequestRecord.Status.CLOSED) rec.setClosedAt(Instant.now());
            prRepo.save(rec);
            updated++;
            if (wasOpen && next == PullRequestRecord.Status.MERGED) {
              newlyMerged++;
              try {
                cascade.onMerged(rec);
              } catch (Exception cex) {
                log.warn("cascade failed: {}", cex.getMessage());
              }
            }
          }
        } catch (Exception e) {
          log.warn(
              "PrRefresh: snapshot failed for {}#{}: {}",
              rec.getRepoFullName(),
              rec.getPrNumber(),
              e.getMessage());
        }
      }
    }
    return new RefreshResult(checked, updated, newlyMerged);
  }

  private static PullRequestRecord.Status mapState(String state, boolean merged) {
    if (merged) return PullRequestRecord.Status.MERGED;
    if (state != null && "closed".equalsIgnoreCase(state)) return PullRequestRecord.Status.CLOSED;
    return PullRequestRecord.Status.OPEN;
  }
}
