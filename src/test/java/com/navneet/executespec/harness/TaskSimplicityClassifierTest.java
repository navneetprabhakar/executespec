package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.GoalDonenessState;
import com.navneet.executespec.execution.model.RunContinuationDecision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TaskSimplicityClassifierTest {

  @Test
  void boundedBrownfieldBugFixProducesReachableStepDownSignal() {
    TaskSimplicityClassifier.Signal signal =
        TaskSimplicityClassifier.assess(
            task("Fix auth regression", "Repair the broken token refresh.", List.of("Auth.java")),
            completedWorker(),
            continueDecision());

    assertThat(signal.simplerThanExpected()).isTrue();
    assertThat(signal.reason()).isEqualTo("BOUNDED_BROWNFIELD_REPAIR");
    assertThat(signal.category()).isEqualTo("BUG_FIX");
    assertThat(signal.observedFileWrites()).isEqualTo(1);
  }

  @Test
  void quickGreenfieldBuildNeverProducesStepDownSignal() {
    TaskSimplicityClassifier.Signal signal =
        TaskSimplicityClassifier.assess(
            task(
                "Build user endpoint",
                "Implement a new user endpoint.",
                List.of("UserController.java")),
            completedWorker(),
            continueDecision());

    assertThat(signal.simplerThanExpected()).isFalse();
    assertThat(signal.reason()).isEqualTo("CATEGORY_NOT_ELIGIBLE");
    assertThat(signal.category()).isEqualTo("GREENFIELD_BUILD");
  }

  @ParameterizedTest
  @MethodSource("boundedBrownfieldTasks")
  void boundedBrownfieldCategoriesAreEligible(PlannedTask task) {
    assertThat(
            TaskSimplicityClassifier.assess(task, completedWorker(), continueDecision())
                .simplerThanExpected())
        .isTrue();
  }

  @Test
  void broadBrownfieldScopeDoesNotProduceStepDownSignal() {
    PlannedTask task =
        task(
            "Fix checkout failure",
            "Repair the broken checkout flow.",
            List.of("Cart.java", "Checkout.java", "Payment.java"));

    TaskSimplicityClassifier.Signal signal =
        TaskSimplicityClassifier.assess(task, completedWorker(), continueDecision());

    assertThat(signal.simplerThanExpected()).isFalse();
    assertThat(signal.reason()).isEqualTo("PLANNED_SCOPE_NOT_BOUNDED");
  }

  @Test
  void signalWritesBoundedPersistablePolicyEvidence() {
    TaskSimplicityClassifier.Signal signal =
        TaskSimplicityClassifier.assess(
            task("Fix auth regression", "Repair the broken token refresh.", List.of("Auth.java")),
            completedWorker(),
            continueDecision());
    Map<String, Object> policy = new LinkedHashMap<>();

    signal.appendPolicyEvidence(policy);

    assertThat(policy)
        .containsEntry("taskSimplerThanExpected", true)
        .containsEntry("taskSimplicityReason", "BOUNDED_BROWNFIELD_REPAIR")
        .containsEntry("taskSimplicityCategory", "BUG_FIX")
        .containsEntry("taskSimplicityObservedIterations", 4)
        .containsEntry("taskSimplicityObservedToolCalls", 6)
        .containsEntry("taskSimplicityObservedFileWrites", 1);
  }

  @Test
  void continuationScopeCarriesStepDownSignalAndPolicyEvidenceTogether() {
    ContinuationConfirmationScope scope =
        new ContinuationConfirmationScope(false, null, null, null)
            .withSimplicity(
                task("Fix auth regression", "Repair token refresh.", List.of("Auth.java")),
                completedWorker(),
                continueDecision());
    Map<String, Object> policy = new LinkedHashMap<>();

    scope.appendSimplicityEvidence(policy);

    assertThat(scope.simplerThanExpected()).isTrue();
    assertThat(policy)
        .containsEntry("taskSimplerThanExpected", true)
        .containsEntry("taskSimplicityReason", "BOUNDED_BROWNFIELD_REPAIR");
  }

  private static Stream<PlannedTask> boundedBrownfieldTasks() {
    return Stream.of(
        task(
            "Fix failing test",
            "Repair the failing test without changing its contract.",
            List.of("src/test/AuthTest.java")),
        task(
            "Correct checkout behavior",
            "Apply the specified behavior correction.",
            List.of("Checkout.java")));
  }

  private static PlannedTask task(String title, String description, List<String> files) {
    return PlannedTask.builder()
        .id("T1")
        .title(title)
        .description(description)
        .complexity(PlannedTask.Complexity.HIGH)
        .touchesFiles(files)
        .hardConstraints(List.of("Preserve compatibility"))
        .meetsRequirements(List.of("REQ-1"))
        .build();
  }

  private static WorkerResult completedWorker() {
    return WorkerResult.builder()
        .outcome(WorkerResult.Outcome.COMPLETED)
        .iterations(4)
        .toolCallsDispatched(6)
        .repeatedAssistantTextCount(0)
        .toolCallLog(
            List.of(
                "readFile(sig=aa) [target=Auth.java] -> OK",
                "editFile(sig=bb) [target=Auth.java] -> OK"))
        .build();
  }

  private static GoalDonenessDecision continueDecision() {
    return new GoalDonenessDecision(
        GoalDonenessState.NOT_DONE_BUT_PROGRESSING,
        RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
        "downstream validation remains",
        "repair",
        List.of("implementation complete"),
        List.of("validation"),
        List.of("worker completed"),
        List.of(),
        false,
        false);
  }
}
