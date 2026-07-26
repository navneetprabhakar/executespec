package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SkeletonRequirementAllocationPolicyTest {

  @Test
  void validSkeletonDoesNotSpendARepairCall() {
    AtomicInteger retries = new AtomicInteger();
    Plan valid = skeleton(List.of(task("T1", "R1")));

    Plan result =
        SkeletonRequirementAllocationPolicy.enforce(
            valid,
            ignored -> {
              retries.incrementAndGet();
              return valid;
            });

    assertThat(result).isSameAs(valid);
    assertThat(retries).hasValue(0);
  }

  @Test
  void invalidSkeletonGetsOneBoundedRepairWithActionableFeedback() {
    AtomicInteger retries = new AtomicInteger();
    Plan invalid = skeleton(List.of(task("T1")));
    Plan repaired = skeleton(List.of(task("T1", "R1")));

    Plan result =
        SkeletonRequirementAllocationPolicy.enforce(
            invalid,
            feedback -> {
              retries.incrementAndGet();
              assertThat(feedback)
                  .contains("every task must list the requirement IDs")
                  .contains("every requirement must have at least one owner");
              return repaired;
            });

    assertThat(result).isSameAs(repaired);
    assertThat(retries).hasValue(1);
  }

  @Test
  void invalidRepairFailsBeforeExpansion() {
    Plan invalid = skeleton(List.of(task("T1")));

    assertThatThrownBy(
            () -> SkeletonRequirementAllocationPolicy.enforce(invalid, ignored -> invalid))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("remained invalid after retry")
        .hasMessageContaining("R1");
  }

  @Test
  void retryThatTradesOneCoverageOmissionForAnotherFailsClosed() {
    List<RequirementSpec> requirements =
        List.of(
            new RequirementSpec(
                "R1", "Create warehouses", RequirementSpec.RequirementKind.FUNCTIONAL),
            new RequirementSpec(
                "R2", "Create products", RequirementSpec.RequirementKind.FUNCTIONAL),
            new RequirementSpec("R3", "Create orders", RequirementSpec.RequirementKind.FUNCTIONAL));
    Plan first =
        skeletonWithRequirements(List.of(task("T1", "R1"), task("T2", "R2")), requirements);
    Plan retry =
        skeletonWithRequirements(List.of(task("T1", "R1", "R3"), task("T2")), requirements);

    assertThatThrownBy(() -> SkeletonRequirementAllocationPolicy.enforce(first, ignored -> retry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("remained invalid after retry")
        .hasMessageContaining("R2");
  }

  @Test
  void retryCannotRelyOnStaleOwnershipFromInvalidAttempt() {
    Plan first = skeleton(List.of(task("T1", "R1", "R99")));
    Plan retry = skeleton(List.of(task("T1")));

    assertThatThrownBy(() -> SkeletonRequirementAllocationPolicy.enforce(first, ignored -> retry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("remained invalid after retry")
        .hasMessageContaining("R1");
  }

  private static Plan skeleton(List<PlannedTask> tasks) {
    return skeletonWithRequirements(
        tasks,
        List.of(
            new RequirementSpec(
                "R1", "Create warehouses", RequirementSpec.RequirementKind.FUNCTIONAL)));
  }

  private static Plan skeletonWithRequirements(
      List<PlannedTask> tasks, List<RequirementSpec> requirements) {
    return Plan.builder()
        .planTitle("Warehouse")
        .planSummary("Inventory")
        .projectType("python")
        .runtimeSurface(Plan.RuntimeSurface.HTTP)
        .classification(Classification.MULTI_TASK)
        .requirements(requirements)
        .tasks(tasks)
        .build();
  }

  private static PlannedTask task(String id, String... requirementIds) {
    return PlannedTask.builder()
        .id(id)
        .title("Warehouse task")
        .complexity(PlannedTask.Complexity.MEDIUM)
        .taskType(PlannedTask.TaskType.LEAF)
        .meetsRequirements(List.of(requirementIds))
        .build();
  }
}
