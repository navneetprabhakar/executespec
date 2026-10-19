package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanBootstrapVerificationContractTest {
  private Plan plan(List<String> paths, List<String> required) {
    return Plan.builder()
        .projectType("python")
        .tasks(
            List.of(
                PlannedTask.builder()
                    .id("T1")
                    .title("Bootstrap library configuration")
                    .description("Configure pytest dependencies")
                    .touchesFiles(paths)
                    .requiredArtifacts(required)
                    .build()))
        .build();
  }

  @Test
  void manifestOnlyBootstrapIsRepairedBeforeApproval() {
    assertThat(
            PlanBootstrapVerificationContract.violations(
                plan(List.of("requirements.txt"), List.of("requirements.txt"))))
        .singleElement()
        .asString()
        .contains("maintained pytest configuration tests");
  }

  @Test
  void serverOwnedTestsDoNotSatisfyCandidateOwnership() {
    assertThat(
            PlanBootstrapVerificationContract.violations(
                plan(
                    List.of(".executespec/bootstrap/test_acceptance.py"),
                    List.of("requirements.txt"))))
        .isNotEmpty();
  }

  @Test
  void bootstrapCanTestConfigurationWithoutPlaceholderFeatureCode() {
    var plan =
        plan(
            List.of("requirements.txt", "tests/test_configuration.py"),
            List.of("requirements.txt"));
    assertThat(PlanBootstrapVerificationContract.violations(plan)).isEmpty();
  }

  @Test
  void repairedOwnershipIsReboundToTheSameCustomerArtifacts() {
    var initial = plan(List.of("tests/test_configuration.py"), List.of());
    var corrected =
        initial.toBuilder()
            .tasks(
                List.of(
                    initial.tasks().getFirst().toBuilder()
                        .touchesFiles(List.of("requirements.txt", "tests/test_configuration.py"))
                        .build(),
                    PlannedTask.builder()
                        .id("T2")
                        .title("Feature")
                        .touchesFiles(List.of("main.py"))
                        .build()))
            .build();
    var bound = SingleShotArtifactContract.bindRequiredPaths(corrected, "Build a Python library");
    assertThat(bound.tasks().getFirst().requiredArtifacts()).containsExactly("requirements.txt");
    assertThat(PlanBootstrapVerificationContract.violations(bound)).isEmpty();
  }
}
