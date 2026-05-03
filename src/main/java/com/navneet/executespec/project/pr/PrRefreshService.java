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

  /** V82 contract — back-compat for non-controller callers. */
  @Transactional
  public RefreshResult refreshProject(String projectId) {
    return refreshProject(projectId, null);
  }

  /**
   * 2026-05-02 — Two changes vs the V82 implementation:
   *
   * <ol>
   *   <li>Threads the caller's GitHub PAT through to {@code getPullRequestSnapshot} so private-repo
   *       PRs actually return a snapshot (used to silently 401 + no-op).
   *   <li>Iterates BOTH run-scoped PRs (V82 path: keyed on run_id) AND system PRs (V169 path: keyed
   *       on project_id, kind != RUN). System PRs from the bootstrap / architecture / contract /
   *       chat-tool flows had run_id=null, so the V82 loop missed them entirely — user saw the
   *       Refresh button do nothing for merged BOOTSTRAP / ARCHITECTURE PRs.
   * </ol>
   */
  @Transactional
  public RefreshResult refreshProject(String projectId, String callerPat) {
    Set<String> projectRunIds = new HashSet<>();
    pipelineRunRepo
        .findByProjectIdOrderByStartedAtDesc(projectId, PageRequest.of(0, 500))
        .forEach(r -> projectRunIds.add(r.getRunId()));

    // Build the union of PRs: run-scoped + system-scoped. Dedup by record id so a
    // record that's somehow tagged on both run_id AND project_id only refreshes once.
    java.util.LinkedHashMap<java.util.UUID, PullRequestRecord> toCheck =
        new java.util.LinkedHashMap<>();
    for (String runId : projectRunIds) {
      for (PullRequestRecord rec : prRepo.findByRunIdOrderByCreatedAtAsc(runId)) {
        toCheck.putIfAbsent(rec.getId(), rec);
      }
    }
    for (PullRequestRecord rec :
        prRepo.findByProjectIdAndKindNotOrderByCreatedAtDesc(
            projectId, PullRequestRecord.Kind.RUN)) {
      toCheck.putIfAbsent(rec.getId(), rec);
    }
    if (toCheck.isEmpty()) return new RefreshResult(0, 0, 0);

    int checked = 0, updated = 0, newlyMerged = 0;
    for (PullRequestRecord rec : toCheck.values()) {
      if (rec.getStatus() == PullRequestRecord.Status.MERGED
          || rec.getStatus() == PullRequestRecord.Status.CLOSED) continue;
      checked++;
      try {
        var snap =
            github.getPullRequestSnapshot(rec.getRepoFullName(), rec.getPrNumber(), callerPat, 0);
        if (snap == null) {
          log.warn(
              "PrRefresh: snapshot returned null for {}#{} — auth or 404 (PAT={})",
              rec.getRepoFullName(),
              rec.getPrNumber(),
              callerPat == null ? "absent" : "present");
          continue;
        }
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
    return new RefreshResult(checked, updated, newlyMerged);
  }

  private static PullRequestRecord.Status mapState(String state, boolean merged) {
    if (merged) return PullRequestRecord.Status.MERGED;
    if (state != null && "closed".equalsIgnoreCase(state)) return PullRequestRecord.Status.CLOSED;
    return PullRequestRecord.Status.OPEN;
  }
}
