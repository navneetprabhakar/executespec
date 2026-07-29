package com.navneet.executespec.harness;

import java.util.Map;
import java.util.Set;

import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.RunContinuationDecision;

/**
 * Produces the deterministic evidence used to decide whether a PLANNING-tier task may be retried on
 * a cheaper model.
 *
 * <p>The signal is deliberately asymmetric: a short greenfield implementation is not evidence that
 * the remaining work is simple. Only a tightly scoped brownfield repair that completed cleanly with
 * a small observed tool footprint can qualify.
 */
final class TaskSimplicityClassifier {

  private static final Set<String> ELIGIBLE_CATEGORIES =
      Set.of("BUG_FIX", "TEST_FIX", "PROJECT_PATCH");
  private static final int MAX_PLANNED_FILES = 2;
  private static final int MAX_REQUIREMENTS = 2;
  private static final int MAX_CONSTRAINTS = 2;
  private static final int MAX_ITERATIONS = 12;
  private static final int MAX_TOOL_CALLS = 24;
  private static final int MAX_FILE_WRITES = 2;

  private TaskSimplicityClassifier() {}

  static Signal assess(
      PlannedTask task, WorkerResult workerResult, GoalDonenessDecision goalDecision) {
    String category = TaskCategoryClassifier.classify(task);
    if (task == null || task.complexity() != PlannedTask.Complexity.HIGH) {
      return Signal.no("PLANNED_COMPLEXITY_NOT_HIGH", category, task, workerResult);
    }
    if (!ELIGIBLE_CATEGORIES.contains(category)) {
      return Signal.no("CATEGORY_NOT_ELIGIBLE", category, task, workerResult);
    }
    int plannedFiles = task.touchesFiles().size();
    if (plannedFiles == 0 || plannedFiles > MAX_PLANNED_FILES) {
      return Signal.no("PLANNED_SCOPE_NOT_BOUNDED", category, task, workerResult);
    }
    if (task.meetsRequirements().size() > MAX_REQUIREMENTS
        || task.hardConstraints().size() > MAX_CONSTRAINTS) {
      return Signal.no("PLANNED_CONTRACT_NOT_BOUNDED", category, task, workerResult);
    }
    if (workerResult == null
        || workerResult.outcome() != WorkerResult.Outcome.COMPLETED
        || workerResult.salvagedUnverified()) {
      return Signal.no("WORKER_NOT_CLEANLY_COMPLETED", category, task, workerResult);
    }
    int fileWrites = WorkerToolLogEvidence.countFileWrites(workerResult.toolCallLog());
    if (workerResult.iterations() <= 0
        || workerResult.iterations() > MAX_ITERATIONS
        || workerResult.toolCallsDispatched() <= 0
        || workerResult.toolCallsDispatched() > MAX_TOOL_CALLS
        || fileWrites <= 0
        || fileWrites > MAX_FILE_WRITES
        || workerResult.repeatedAssistantTextCount() > 0) {
      return Signal.no("OBSERVED_SCOPE_NOT_BOUNDED", category, task, workerResult);
    }
    if (goalDecision == null
        || goalDecision.anotherModelProviderMightHelp()
        || goalDecision.recommendedDecision()
            != RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER) {
      return Signal.no("GOAL_DECISION_NOT_ELIGIBLE", category, task, workerResult);
    }
    return Signal.yes(category, task, workerResult);
  }

  record Signal(
      boolean simplerThanExpected,
      String reason,
      String category,
      String plannedComplexity,
      int plannedFileCount,
      int observedIterations,
      int observedToolCalls,
      int observedFileWrites) {

    private static Signal yes(String category, PlannedTask task, WorkerResult workerResult) {
      return of(true, "BOUNDED_BROWNFIELD_REPAIR", category, task, workerResult);
    }

    private static Signal no(
        String reason, String category, PlannedTask task, WorkerResult workerResult) {
      return of(false, reason, category, task, workerResult);
    }

    private static Signal of(
        boolean simpler,
        String reason,
        String category,
        PlannedTask task,
        WorkerResult workerResult) {
      return new Signal(
          simpler,
          reason,
          category,
          task == null || task.complexity() == null ? "UNKNOWN" : task.complexity().name(),
          task == null ? 0 : task.touchesFiles().size(),
          workerResult == null ? 0 : workerResult.iterations(),
          workerResult == null ? 0 : workerResult.toolCallsDispatched(),
          workerResult == null
              ? 0
              : WorkerToolLogEvidence.countFileWrites(workerResult.toolCallLog()));
    }

    void appendPolicyEvidence(Map<String, Object> data) {
      data.put("taskSimplerThanExpected", simplerThanExpected);
      data.put("taskSimplicityReason", reason);
      data.put("taskSimplicityCategory", category);
      data.put("taskSimplicityPlannedComplexity", plannedComplexity);
      data.put("taskSimplicityPlannedFileCount", plannedFileCount);
      data.put("taskSimplicityObservedIterations", observedIterations);
      data.put("taskSimplicityObservedToolCalls", observedToolCalls);
      data.put("taskSimplicityObservedFileWrites", observedFileWrites);
    }
  }
}
