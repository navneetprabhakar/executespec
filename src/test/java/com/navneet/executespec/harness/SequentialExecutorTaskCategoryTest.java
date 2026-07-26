package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.agent.PlannedTask;

import org.junit.jupiter.api.Test;

class SequentialExecutorTaskCategoryTest {

  @Test
  void greenfieldScaffoldRemainsGreenfieldWhenRequirementsIncludePytest() {
    PlannedTask task =
        PlannedTask.builder()
            .id("task-1")
            .title("Scaffold project: FastAPI app, SQLAlchemy/SQLite, uvicorn entry point")
            .description(
                "Create requirements.txt with FastAPI and pytest, then verify the app starts.")
            .complexity(PlannedTask.Complexity.LOW)
            .touchesFiles(List.of("requirements.txt", "main.py"))
            .hardConstraints(List.of("Include pytest integration tests"))
            .build();

    assertThat(TaskCategoryClassifier.classify(task)).isEqualTo("GREENFIELD_BUILD");
  }

  @Test
  void explicitFailingTestRepairStillWinsOverAddVerb() {
    PlannedTask task =
        PlannedTask.builder()
            .id("task-2")
            .title("Fix failing test by adding an idempotency guard")
            .description("Repair the pytest failure without changing the public contract.")
            .complexity(PlannedTask.Complexity.MEDIUM)
            .touchesFiles(List.of("tests/test_reservations.py"))
            .build();

    assertThat(TaskCategoryClassifier.classify(task)).isEqualTo("TEST_FIX");
  }

  @Test
  void repairNounWinsOverGenericImplementVerb() {
    PlannedTask task =
        PlannedTask.builder()
            .id("task-3")
            .title("Implement auth fix")
            .complexity(PlannedTask.Complexity.LOW)
            .touchesFiles(List.of("src/AuthService.java"))
            .build();

    assertThat(TaskCategoryClassifier.classify(task)).isEqualTo("BUG_FIX");
  }

  @Test
  void greenfieldEndpointTaskUsesImplementationIntentFromDescription() {
    PlannedTask task =
        PlannedTask.builder()
            .id("task-4")
            .title("Items endpoints and integration tests")
            .description("Implement POST /items with clear error handling and integration tests.")
            .complexity(PlannedTask.Complexity.MEDIUM)
            .touchesFiles(List.of("app/main.py", "tests/test_items.py"))
            .build();

    assertThat(TaskCategoryClassifier.classify(task)).isEqualTo("GREENFIELD_BUILD");
  }

  @Test
  void documentationOnlyTaskDoesNotRequireAProductCodeDiff() {
    PlannedTask task =
        PlannedTask.builder()
            .id("task-5")
            .title("Write README with install and run instructions")
            .description("Document configuration, endpoints, and test commands.")
            .complexity(PlannedTask.Complexity.LOW)
            .touchesFiles(List.of("README.md"))
            .build();

    assertThat(
            TaskCategoryClassifier.requiresMeaningfulDiff(
                TaskCategoryClassifier.classify(task), task))
        .isFalse();
  }

  @Test
  void mixedDocumentationAndSourceTaskStillRequiresAProductCodeDiff() {
    PlannedTask task =
        PlannedTask.builder()
            .id("task-6")
            .title("Implement endpoint and update README")
            .complexity(PlannedTask.Complexity.LOW)
            .touchesFiles(List.of("app/routes.py", "README.md"))
            .build();

    assertThat(
            TaskCategoryClassifier.requiresMeaningfulDiff(
                TaskCategoryClassifier.classify(task), task))
        .isTrue();
  }
}
