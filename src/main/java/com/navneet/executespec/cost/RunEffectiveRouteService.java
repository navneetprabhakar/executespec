package com.navneet.executespec.cost;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Single read seam for the physical route that actually served a run or one of its tasks.
 *
 * <p>Callers must not re-derive this from a requested/tier-resolved value. {@code agent_task},
 * {@code RunDetail} and the benchmark attribution path all read through here (or through {@link
 * EffectiveRouteProjection} directly) so "effective route" has exactly one definition: the distinct
 * successful {@code (provider, model)} pairs in the canonical {@link RunStepUsage} ledger.
 */
@Service
@RequiredArgsConstructor
public class RunEffectiveRouteService {

  private final RunStepUsageRepository repository;

  /** Effective route across every physical attempt recorded for the run. */
  public EffectiveRouteProjection forRun(String runId) {
    if (runId == null || runId.isBlank()) {
      return EffectiveRouteProjection.UNATTRIBUTED;
    }
    return EffectiveRouteProjection.from(repository.findByRunIdOrderByCreatedAtAsc(runId));
  }

  /** Effective route across the physical attempts recorded for one plan task. */
  public EffectiveRouteProjection forTask(String runId, String taskId) {
    if (runId == null || runId.isBlank() || taskId == null || taskId.isBlank()) {
      return EffectiveRouteProjection.UNATTRIBUTED;
    }
    List<RunStepUsage> rows = repository.findByRunIdAndTaskIdOrderByCreatedAtAsc(runId, taskId);
    return EffectiveRouteProjection.from(rows);
  }

  /**
   * Distinct successful models across the run's whole physical-attempt ledger, first-seen order.
   * Empty when no successful attempt names a real model.
   */
  public List<String> distinctSuccessfulModelsForRun(String runId) {
    if (runId == null || runId.isBlank()) {
      return List.of();
    }
    return EffectiveRouteProjection.distinctSuccessfulModels(
        repository.findByRunIdOrderByCreatedAtAsc(runId));
  }

  /** Physical route(s) that served planning, excluding the separately attributed classifier. */
  public EffectiveRouteProjection forPlanner(String runId) {
    return forRunRowsMatching(runId, row -> isPlannerRole(row.getAgentRole()));
  }

  /** Physical route(s) that served reviewer worker passes. */
  public EffectiveRouteProjection forReviewer(String runId) {
    return forRunRowsMatching(
        runId,
        row ->
            row.getTaskId() != null
                && row.getTaskId().toLowerCase(java.util.Locale.ROOT).contains("-reviewer-"));
  }

  /**
   * Most recent successful physical route for a task. Continuation policy needs the route that
   * produced the terminal observation, while the durable task projection intentionally remains
   * {@code MIXED} when more than one route served earlier turns.
   */
  public EffectiveRouteProjection latestSuccessfulForTask(String runId, String taskId) {
    if (runId == null || runId.isBlank() || taskId == null || taskId.isBlank()) {
      return EffectiveRouteProjection.UNATTRIBUTED;
    }
    List<RunStepUsage> rows = repository.findByRunIdAndTaskIdOrderByCreatedAtAsc(runId, taskId);
    if (rows == null) return EffectiveRouteProjection.UNATTRIBUTED;
    for (int i = rows.size() - 1; i >= 0; i--) {
      RunStepUsage row = rows.get(i);
      if (row != null && "SUCCESS".equalsIgnoreCase(row.getAttemptStatus())) {
        return EffectiveRouteProjection.from(List.of(row));
      }
    }
    return EffectiveRouteProjection.UNATTRIBUTED;
  }

  /**
   * Captures the exact physical-attempt boundary immediately before one retry operation starts.
   *
   * <p>The id set prevents an earlier row from satisfying a later approval even when an in-memory
   * run cost tracker restarted and reused a call index. The call-index floor separately binds the
   * proof to calls recorded after the current process-local retry snapshot.
   */
  public AttemptFence captureAttemptFence(String runId, String taskId, long callIndexFloor) {
    List<RunStepUsage> rows =
        runId == null || runId.isBlank() || taskId == null || taskId.isBlank()
            ? List.of()
            : repository.findByRunIdAndTaskIdOrderByCreatedAtAsc(runId, taskId);
    Set<String> priorAttemptIds =
        rows == null
            ? Set.of()
            : rows.stream()
                .filter(java.util.Objects::nonNull)
                .map(RunStepUsage::getPhysicalAttemptId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    return new AttemptFence(runId, taskId, Math.max(0L, callIndexFloor), priorAttemptIds);
  }

  /**
   * Projects only successful physical calls created after the supplied retry-operation fence.
   * Missing new evidence remains UNATTRIBUTED and therefore fails exact-route admission.
   */
  public EffectiveRouteProjection successfulForTaskAfter(AttemptFence fence) {
    return successfulTaskAttemptAfter(fence).route();
  }

  /**
   * Returns the exact successful physical attempts created after a task retry fence.
   *
   * <p>The projection alone is insufficient durable approval evidence: a later publication check
   * must be able to distinguish the approved correction attempt from an older successful task
   * attempt on another route. Physical attempt ids are therefore part of this result.
   */
  public AttemptRouteEvidence successfulTaskAttemptAfter(AttemptFence fence) {
    if (fence == null || fence.runId() == null || fence.taskId() == null) {
      return AttemptRouteEvidence.UNATTRIBUTED;
    }
    List<RunStepUsage> rows =
        repository.findByRunIdAndTaskIdOrderByCreatedAtAsc(fence.runId(), fence.taskId());
    if (rows == null || rows.isEmpty()) {
      return AttemptRouteEvidence.UNATTRIBUTED;
    }
    return evidenceFrom(rowsAfterFence(rows, fence.callIndexFloor(), fence.priorAttemptIds()));
  }

  /** Captures the physical reviewer-attempt boundary immediately before an approved re-review. */
  public ReviewerAttemptFence captureReviewerAttemptFence(String runId, long callIndexFloor) {
    List<RunStepUsage> rows =
        runId == null || runId.isBlank()
            ? List.of()
            : repository.findByRunIdOrderByCreatedAtAsc(runId);
    Set<String> priorAttemptIds =
        rows == null
            ? Set.of()
            : rows.stream()
                .filter(java.util.Objects::nonNull)
                .filter(RunEffectiveRouteService::isReviewerRow)
                .map(RunStepUsage::getPhysicalAttemptId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    return new ReviewerAttemptFence(runId, Math.max(0L, callIndexFloor), priorAttemptIds);
  }

  /**
   * Returns only reviewer calls created after the approved correction's reviewer fence.
   *
   * <p>This deliberately does not use {@link #forReviewer(String)} because that run-wide projection
   * includes earlier reviewer routes and becomes MIXED after a model correction.
   */
  public AttemptRouteEvidence successfulReviewerAttemptAfter(ReviewerAttemptFence fence) {
    if (fence == null || fence.runId() == null || fence.runId().isBlank()) {
      return AttemptRouteEvidence.UNATTRIBUTED;
    }
    List<RunStepUsage> rows = repository.findByRunIdOrderByCreatedAtAsc(fence.runId());
    if (rows == null || rows.isEmpty()) {
      return AttemptRouteEvidence.UNATTRIBUTED;
    }
    return evidenceFrom(
        rowsAfterFence(rows, fence.callIndexFloor(), fence.priorAttemptIds()).stream()
            .filter(RunEffectiveRouteService::isReviewerRow)
            .toList());
  }

  /**
   * Rehydrates a persisted approval attestation from the canonical immutable usage ledger.
   *
   * <p>Every requested physical id must exist on the same run. A partial lookup is unattributed
   * rather than silently proving the route from the surviving subset.
   */
  public AttemptRouteEvidence forPhysicalAttempts(
      String runId, java.util.Collection<String> physicalAttemptIds) {
    if (runId == null
        || runId.isBlank()
        || physicalAttemptIds == null
        || physicalAttemptIds.isEmpty()) {
      return AttemptRouteEvidence.UNATTRIBUTED;
    }
    LinkedHashSet<String> requested =
        physicalAttemptIds.stream()
            .filter(java.util.Objects::nonNull)
            .map(String::trim)
            .filter(id -> !id.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (requested.isEmpty()) {
      return AttemptRouteEvidence.UNATTRIBUTED;
    }
    List<RunStepUsage> rows = repository.findByRunIdAndPhysicalAttemptIdIn(runId, requested);
    if (rows == null) {
      return AttemptRouteEvidence.UNATTRIBUTED;
    }
    Set<String> found =
        rows.stream()
            .filter(java.util.Objects::nonNull)
            .map(RunStepUsage::getPhysicalAttemptId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
    if (!found.equals(requested)) {
      return AttemptRouteEvidence.UNATTRIBUTED;
    }
    return evidenceFrom(rows);
  }

  public AttemptRouteEvidence forTaskPhysicalAttempts(
      String runId, String taskId, java.util.Collection<String> physicalAttemptIds) {
    if (runId == null || taskId == null || physicalAttemptIds == null) {
      return AttemptRouteEvidence.UNATTRIBUTED;
    }
    List<RunStepUsage> taskRows = repository.findByRunIdAndTaskIdOrderByCreatedAtAsc(runId, taskId);
    return evidenceForExactIds(
        taskRows == null
            ? List.of()
            : taskRows.stream()
                .filter(row -> row != null && taskId.equals(row.getTaskId()))
                .toList(),
        physicalAttemptIds);
  }

  public AttemptRouteEvidence forReviewerPhysicalAttempts(
      String runId, java.util.Collection<String> physicalAttemptIds) {
    if (runId == null || physicalAttemptIds == null) {
      return AttemptRouteEvidence.UNATTRIBUTED;
    }
    List<RunStepUsage> rows = repository.findByRunIdOrderByCreatedAtAsc(runId);
    return evidenceForExactIds(
        rows == null
            ? List.of()
            : rows.stream().filter(RunEffectiveRouteService::isReviewerRow).toList(),
        physicalAttemptIds);
  }

  private static AttemptRouteEvidence evidenceForExactIds(
      List<RunStepUsage> candidateRows, java.util.Collection<String> physicalAttemptIds) {
    Set<String> requested =
        physicalAttemptIds.stream()
            .filter(java.util.Objects::nonNull)
            .map(String::trim)
            .filter(id -> !id.isBlank())
            .collect(Collectors.toSet());
    if (requested.isEmpty() || candidateRows == null) {
      return AttemptRouteEvidence.UNATTRIBUTED;
    }
    List<RunStepUsage> rows =
        candidateRows.stream()
            .filter(
                row ->
                    row != null
                        && row.getPhysicalAttemptId() != null
                        && requested.contains(row.getPhysicalAttemptId()))
            .toList();
    Set<String> found =
        rows.stream().map(RunStepUsage::getPhysicalAttemptId).collect(Collectors.toSet());
    return found.equals(requested) ? evidenceFrom(rows) : AttemptRouteEvidence.UNATTRIBUTED;
  }

  public record AttemptFence(
      String runId, String taskId, long callIndexFloor, Set<String> priorAttemptIds) {
    public AttemptFence {
      priorAttemptIds = priorAttemptIds == null ? Set.of() : Set.copyOf(priorAttemptIds);
    }
  }

  public record ReviewerAttemptFence(
      String runId, long callIndexFloor, Set<String> priorAttemptIds) {
    public ReviewerAttemptFence {
      priorAttemptIds = priorAttemptIds == null ? Set.of() : Set.copyOf(priorAttemptIds);
    }
  }

  public record AttemptRouteEvidence(
      EffectiveRouteProjection route,
      List<String> physicalAttemptIds,
      long firstCallIndex,
      long lastCallIndex) {
    public static final AttemptRouteEvidence UNATTRIBUTED =
        new AttemptRouteEvidence(EffectiveRouteProjection.UNATTRIBUTED, List.of(), 0L, 0L);

    public AttemptRouteEvidence {
      route = route == null ? EffectiveRouteProjection.UNATTRIBUTED : route;
      physicalAttemptIds = physicalAttemptIds == null ? List.of() : List.copyOf(physicalAttemptIds);
    }

    public boolean isExact(String provider, String model) {
      return route.attribution() == EffectiveRouteProjection.Attribution.LEDGER
          && !route.divergesFrom(provider, model)
          && !physicalAttemptIds.isEmpty();
    }
  }

  private static List<RunStepUsage> rowsAfterFence(
      List<RunStepUsage> rows, long callIndexFloor, Set<String> priorAttemptIds) {
    Set<String> prior = priorAttemptIds == null ? Set.of() : priorAttemptIds;
    return rows.stream()
        .filter(java.util.Objects::nonNull)
        .filter(
            row ->
                row.getPhysicalAttemptId() != null
                    && !row.getPhysicalAttemptId().isBlank()
                    && !prior.contains(row.getPhysicalAttemptId()))
        .filter(row -> row.getCallIndex() > callIndexFloor)
        .toList();
  }

  private static AttemptRouteEvidence evidenceFrom(List<RunStepUsage> rows) {
    if (rows == null || rows.isEmpty()) {
      return AttemptRouteEvidence.UNATTRIBUTED;
    }
    List<RunStepUsage> successful =
        rows.stream().filter(row -> "SUCCESS".equalsIgnoreCase(row.getAttemptStatus())).toList();
    if (successful.isEmpty()) {
      return AttemptRouteEvidence.UNATTRIBUTED;
    }
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    long first = Long.MAX_VALUE;
    long last = 0L;
    for (RunStepUsage row : successful) {
      ids.add(row.getPhysicalAttemptId());
      first = Math.min(first, row.getCallIndex());
      last = Math.max(last, row.getCallIndex());
    }
    return new AttemptRouteEvidence(
        EffectiveRouteProjection.from(successful),
        List.copyOf(ids),
        first == Long.MAX_VALUE ? 0L : first,
        last);
  }

  private EffectiveRouteProjection forRunRowsMatching(
      String runId, java.util.function.Predicate<RunStepUsage> predicate) {
    if (runId == null || runId.isBlank()) return EffectiveRouteProjection.UNATTRIBUTED;
    List<RunStepUsage> rows = repository.findByRunIdOrderByCreatedAtAsc(runId);
    if (rows == null || rows.isEmpty()) return EffectiveRouteProjection.UNATTRIBUTED;
    return EffectiveRouteProjection.from(rows.stream().filter(predicate).toList());
  }

  private static boolean isPlannerRole(String agentRole) {
    return "OutlinePlannerAgent".equalsIgnoreCase(agentRole)
        || "TaskExpanderAgent".equalsIgnoreCase(agentRole)
        || "CoherencePlannerAgent".equalsIgnoreCase(agentRole);
  }

  private static boolean isReviewerRow(RunStepUsage row) {
    return row != null
        && row.getTaskId() != null
        && row.getTaskId().toLowerCase(java.util.Locale.ROOT).contains("-reviewer-");
  }
}
