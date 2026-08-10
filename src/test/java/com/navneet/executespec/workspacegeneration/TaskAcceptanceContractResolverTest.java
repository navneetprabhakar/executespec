package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;

import org.junit.jupiter.api.Test;

class TaskAcceptanceContractResolverTest {

  private final TaskAcceptanceContractResolver resolver =
      new TaskAcceptanceContractResolver(new ObjectMapper());

  @Test
  void canonicalContractIsTypedSortedAndIndependentOfPlannerOrdering() {
    PlannedTask first =
        PlannedTask.builder()
            .id("T2")
            .title("Implement service")
            .description("Add behavior")
            .touchesFiles(List.of("src/Z.java", "src/A.java"))
            .build();
    PlannedTask reordered =
        first.toBuilder().touchesFiles(List.of("src/A.java", "src/Z.java")).build();

    TaskAcceptanceContract contract = resolver.resolve(first, 1);
    TaskAcceptanceContract same = resolver.resolve(reordered, 1);

    assertThat(contract.schemaVersion()).isEqualTo(1);
    assertThat(contract.taskId()).isEqualTo("T2");
    assertThat(contract.taskIndex()).isEqualTo(1);
    assertThat(contract.verificationPolicy()).isEqualTo("SERVER_RESOLVED_V1");
    assertThat(contract.buildRequirement()).isEqualTo("REQUIRED_NON_NOOP");
    assertThat(contract.testRequirement()).isEqualTo("REQUIRED");
    assertThat(contract.declaredTouchPaths()).containsExactly("src/A.java", "src/Z.java");
    assertThat(contract.digest()).isEqualTo(same.digest()).matches("[0-9a-f]{64}");
  }

  @Test
  void documentationOnlyTaskMayOmitTestsButActualCodeClassificationCannot() {
    PlannedTask documentation =
        PlannedTask.builder()
            .id("T1")
            .title("Documentation update")
            .description("Update README copy")
            .touchesFiles(List.of("README.md"))
            .build();
    PlannedTask code = documentation.toBuilder().touchesFiles(List.of("src/main/App.java")).build();

    assertThat(resolver.resolve(documentation, 0).testRequirement()).isEqualTo("OPTIONAL");
    assertThat(resolver.resolve(code, 0).testRequirement()).isEqualTo("REQUIRED");
  }

  @Test
  void authorityDigestBindsPlanGoalAndWorkspaceSelection() {
    PlannedTask task = PlannedTask.builder().id("T1").title("Task").build();
    Plan plan = Plan.builder().planTitle("Plan").tasks(List.of(task)).build();

    String baseline = resolver.authorityDigest(plan, "goal-a", "repo-a");

    assertThat(resolver.authorityDigest(plan, "goal-b", "repo-a")).isNotEqualTo(baseline);
    assertThat(resolver.authorityDigest(plan, "goal-a", "repo-b")).isNotEqualTo(baseline);
    assertThat(baseline).matches("[0-9a-f]{64}");
  }
}
