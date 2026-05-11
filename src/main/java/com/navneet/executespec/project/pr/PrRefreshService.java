package com.navneet.executespec.project.pr;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

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

  /**
   * Result of a refresh sweep.
   *
   * @param checked total non-terminal PRs hit by this sweep
   * @param updated PRs whose open/merged/closed state transitioned
   * @param newlyMerged subset of {@code updated} that flipped OPEN→MERGED (triggers cascade)
   * @param ciFailing PRs whose CI rollup ends up at FAILURE after this sweep — surfaced in the
   *     toast / Refresh-status copy so the user sees the bad-CI signal even on a webhook-less
   *     setup. 2026-05-11.
   */
  public record RefreshResult(int checked, int updated, int newlyMerged, int ciFailing) {
    /** Back-compat ctor for callers / tests that only care about state transitions. */
    public RefreshResult(int checked, int updated, int newlyMerged) {
      this(checked, updated, newlyMerged, 0);
    }
  }

  /** V82 contract — back-compat for non-controller callers. */
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
   *
   * <p>2026-05-04 (P0 hotfix #2) — dropped the method-level {@code @Transactional}. Previously a
   * single tx wrapped the whole loop; if any cascade.onMerged step (or any
   * inner-{@code @Transactional} bean it called) threw, Spring marked the outer tx rollback-only.
   * The per-PR try/catch swallowed the exception but couldn't undo the rollback flag — eventual
   * commit crashed with {@code UnexpectedRollbackException} → 500 (logs 2026-05-04 15:20:46Z trace
   * ac21134643495220dd1b8550b3851fcc). With no outer tx, each {@code prRepo.save(rec)} runs in its
   * own implicit tx (Spring Data default) and a downstream cascade failure is fully isolated to its
   * own scope. Refresh is also a logically idempotent operation, so per-row commit semantics match
   * what users expect (partial progress on a flaky network is preferable to "all or nothing").
   * Pairs with the cascade null-runId fix in {@code PostMergeCascadeService}.
   */
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

    int checked = 0, updated = 0, newlyMerged = 0, ciFailing = 0;
    // Track unique projectIds that had at least one OPEN→MERGED transition in this batch.
    // After the loop we fire ONE async architecture regen per project — without dedup, 3 PRs
    // landing into one project would fire 3 sequential ~30-60s LLM calls and blow the
    // Cloudflare 100s edge timeout. (P0 hotfix #3, 2026-05-04.)
    Set<String> projectsToRegen = new HashSet<>();
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
        boolean stateChanged = next != rec.getStatus();
        boolean wasOpen = rec.getStatus() == PullRequestRecord.Status.OPEN;
        if (stateChanged) {
          rec.setStatus(next);
          rec.setUpdatedAt(Instant.now());
          if (next == PullRequestRecord.Status.MERGED) rec.setMergedAt(Instant.now());
          if (next == PullRequestRecord.Status.CLOSED) rec.setClosedAt(Instant.now());
          updated++;
        }

        // 2026-05-11 (V186) — CI rollup poll. Only OPEN PRs need a check (merged
        // / closed PRs the user already moved on from). The headSha from the same
        // snapshot drives /commits/{sha}/check-runs so we get the exact PR-tip
        // signal, not a stale main-branch result. Failure / pending / success
        // persisted via setters; ciChecked_at always bumped on a successful poll
        // so the UI can render "as of …" even when nothing changed.
        if (next == PullRequestRecord.Status.OPEN
            && snap.headSha() != null
            && !snap.headSha().isBlank()) {
          try {
            var rollup = github.getCheckRunRollup(rec.getRepoFullName(), snap.headSha(), callerPat);
            PullRequestRecord.CiCheckStatus mapped = mapCiStatus(rollup.status());
            boolean ciChanged =
                mapped != rec.getCiStatus()
                    || rollup.failingCount()
                        != (rec.getCiFailingCount() == null ? 0 : rec.getCiFailingCount());
            if (ciChanged) {
              rec.setCiStatus(mapped);
              rec.setCiFailingCount(rollup.failingCount());
              rec.setUpdatedAt(Instant.now());
            }
            // Always stamp ciCheckedAt on a successful poll, so the dashboard
            // can show "as of 2 min ago" even when nothing about CI changed.
            rec.setCiCheckedAt(Instant.now());
            if (mapped == PullRequestRecord.CiCheckStatus.FAILURE) ciFailing++;
          } catch (Exception ciEx) {
            // CI rollup is best-effort; never fail the whole sweep over a single
            // check-runs poll glitch.
            log.debug(
                "PrRefresh: ci rollup failed for {}#{}: {}",
                rec.getRepoFullName(),
                rec.getPrNumber(),
                ciEx.getMessage());
          }
        }

        // Persist once after both state + ci updates so a single save catches both.
        if (stateChanged || rec.getCiCheckedAt() != null) {
          prRepo.save(rec);
        }

        if (stateChanged && wasOpen && next == PullRequestRecord.Status.MERGED) {
          newlyMerged++;
          // Per-PR fast steps (notify owner, write banner) — sync, ~ms.
          try {
            cascade.onMerged(rec);
          } catch (Exception cex) {
            log.warn("cascade.onMerged failed: {}", cex.getMessage());
          }
          // Capture project for the async per-project heavy regen below.
          String pid = cascade.resolveProjectId(rec);
          if (pid != null && !pid.isBlank()) projectsToRegen.add(pid);
        }
      } catch (Exception e) {
        log.warn(
            "PrRefresh: snapshot failed for {}#{}: {}",
            rec.getRepoFullName(),
            rec.getPrNumber(),
            e.getMessage());
      }
    }
    // Fire ONE async architecture regen per unique project. Fire-and-forget: the async method
    // owns its own try/catch so a regen failure can't propagate back to this thread (and even if
    // it did, the @Async proxy isolates it). Refresh returns to the caller in seconds.
    for (String pid : projectsToRegen) {
      try {
        cascade.regenerateAfterMerge(pid);
      } catch (Exception e) {
        log.warn("PrRefresh: failed to dispatch async regen for {}: {}", pid, e.getMessage());
      }
    }
    return new RefreshResult(checked, updated, newlyMerged, ciFailing);
  }

  private static PullRequestRecord.Status mapState(String state, boolean merged) {
    if (merged) return PullRequestRecord.Status.MERGED;
    if (state != null && "closed".equalsIgnoreCase(state)) return PullRequestRecord.Status.CLOSED;
    return PullRequestRecord.Status.OPEN;
  }

  /**
   * Translates the GitHub check-runs rollup string from {@link
   * com.navneet.executespec.repo.GitHubRepoService.CheckRunRollup#status} into the entity-level
   * {@link PullRequestRecord.CiCheckStatus}. Unknown / unmapped values degrade to {@code UNKNOWN}
   * so a typo at the GitHub side can never write a constraint-violating value (V186 adds the CHECK
   * constraint).
   */
  private static PullRequestRecord.CiCheckStatus mapCiStatus(String raw) {
    if (raw == null) return PullRequestRecord.CiCheckStatus.UNKNOWN;
    return switch (raw.toUpperCase()) {
      case "PENDING" -> PullRequestRecord.CiCheckStatus.PENDING;
      case "SUCCESS" -> PullRequestRecord.CiCheckStatus.SUCCESS;
      case "FAILURE" -> PullRequestRecord.CiCheckStatus.FAILURE;
      case "NEUTRAL" -> PullRequestRecord.CiCheckStatus.NEUTRAL;
      default -> PullRequestRecord.CiCheckStatus.UNKNOWN;
    };
  }
}
