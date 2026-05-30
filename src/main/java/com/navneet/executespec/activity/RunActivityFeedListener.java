package com.navneet.executespec.activity;

import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RL-2026-05-041 (audit risk #11 — incomplete audit trail) — bridges the run lifecycle to the
 * project activity feed.
 *
 * <p>The {@link ActivityEventType} taxonomy declared run-lifecycle events (RUN_SUBMITTED,
 * RUN_PLAN_READY, RUN_COMPLETED, RUN_FAILED) but nothing emitted them — the feed only carried
 * contracts/standards/architecture/bootstrap events. This listener subscribes to the existing
 * {@link PipelineEvent} bus (the same decoupled bus {@code RunCompletionEmailListener} uses) and
 * emits the corresponding activity-feed entries, so a project's Activity tab finally reflects the
 * core product action: runs.
 *
 * <p>Decoupled from {@code RunOrchestrator} on purpose — no new dependency on the god-object, and
 * the dispatch is {@code @Async} so feed emission never blocks the pipeline. Standalone
 * (non-project) runs have a null {@code projectId}; for those the emitter no-ops (the feed is
 * project-scoped), which is the intended behavior.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunActivityFeedListener {

  private final PipelineRunRepository runRepo;
  private final ActivityEventEmitter emitter;

  @Async
  @EventListener
  public void onRunStarted(PipelineEvent.RunStarted event) {
    PipelineRun run = lookup(event.runId());
    if (run == null) return;
    // Actor = the human who submitted. RunStarted fires at planning-start, milliseconds after
    // submit, so it is the earliest reliable lifecycle signal carrying the run's project + owner.
    emit(run, ActivityEventType.RUN_SUBMITTED, actorOf(run), "Run submitted" + titleSuffix(run));
  }

  @Async
  @EventListener
  public void onGateOpened(PipelineEvent.GateOpened event) {
    if (!"PLAN_APPROVAL".equals(event.gateType()))
      return; // only the plan gate maps to a feed event
    PipelineRun run = lookup(event.runId());
    if (run == null) return;
    emit(
        run,
        ActivityEventType.RUN_PLAN_READY,
        null, // SYSTEM — the planner produced the plan
        "Plan ready for approval"
            + (event.credits() > 0 ? " (" + event.credits() + " credit(s))" : "")
            + titleSuffix(run));
  }

  @Async
  @EventListener
  public void onRunCompleted(PipelineEvent.RunCompleted event) {
    PipelineRun run = lookup(event.runId());
    if (run == null) return;
    emit(run, ActivityEventType.RUN_COMPLETED, null, "Run completed" + titleSuffix(run));
  }

  @Async
  @EventListener
  public void onRunFailed(PipelineEvent.RunFailed event) {
    PipelineRun run = lookup(event.runId());
    if (run == null) return;
    String reason =
        event.reason() != null && !event.reason().isBlank() ? ": " + event.reason() : "";
    emit(run, ActivityEventType.RUN_FAILED, null, "Run failed" + titleSuffix(run) + reason);
  }

  private PipelineRun lookup(String runId) {
    try {
      return runRepo.findById(runId).orElse(null);
    } catch (Exception e) {
      log.debug("[RunActivityFeedListener] run {} lookup failed: {}", runId, e.getMessage());
      return null;
    }
  }

  private void emit(PipelineRun run, ActivityEventType type, UUID actor, String summary) {
    UUID projectId = parseUuid(run.getProjectId());
    if (projectId == null) return; // standalone (non-project) run — feed is project-scoped
    emitter.emit(
        projectId,
        type,
        actor,
        "RUN",
        run.getRunId(),
        summary,
        Map.of(
            "runId", run.getRunId(),
            "status", run.getStatus() == null ? "" : run.getStatus(),
            "runMode", run.getRunMode() == null ? "" : run.getRunMode()));
  }

  private static UUID actorOf(PipelineRun run) {
    return parseUuid(run.getCreatedByUserId());
  }

  private static String titleSuffix(PipelineRun run) {
    return run.getRunTitle() != null && !run.getRunTitle().isBlank()
        ? ": " + run.getRunTitle()
        : "";
  }

  private static UUID parseUuid(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
