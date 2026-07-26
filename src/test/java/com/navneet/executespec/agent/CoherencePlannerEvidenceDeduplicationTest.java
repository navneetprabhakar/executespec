package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;

class CoherencePlannerEvidenceDeduplicationTest {

  @Test
  void repeatedGlobalConstraintIsRenderedOnceThenReferenced() {
    String shared = "Every mutation writes its audit record in the same transaction.";
    Plan plan =
        Plan.builder()
            .tasks(
                List.of(
                    task("T1", shared, "Schema owner must apply migrations."),
                    task("T2", shared, "API owner returns sanitized errors.")))
            .requirements(List.of())
            .build();
    PlannerRequest request =
        PlannerRequest.builder()
            .spec("Build a durable API.")
            .classification(Classification.MULTI_TASK)
            .planStrategy(PlanStrategy.LARGE)
            .provider("deepseek")
            .model("deepseek-v4-flash-native")
            .context(new ExecutionContext("run-1", "__planner__", Path.of("/tmp/repo")))
            .build();

    String evidence = CoherencePlannerEvidence.build(plan, request);

    assertThat(occurrences(evidence, shared)).isEqualTo(1);
    assertThat(evidence).contains("same-as T1");
    assertThat(evidence)
        .contains("Schema owner must apply migrations.", "API owner returns sanitized errors.");
  }

  private static PlannedTask task(String id, String... constraints) {
    return PlannedTask.builder()
        .id(id)
        .title("Task " + id)
        .description("Implement " + id)
        .touchesFiles(List.of("src/" + id + ".ts"))
        .dependsOn(List.of())
        .hardConstraints(List.of(constraints))
        .meetsRequirements(List.of())
        .taskType(PlannedTask.TaskType.LEAF)
        .build();
  }

  private static int occurrences(String value, String needle) {
    int count = 0;
    int cursor = 0;
    while ((cursor = value.indexOf(needle, cursor)) >= 0) {
      count++;
      cursor += needle.length();
    }
    return count;
  }
}
