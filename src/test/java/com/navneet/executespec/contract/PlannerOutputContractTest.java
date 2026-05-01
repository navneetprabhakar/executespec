package com.navneet.executespec.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.TaskPlan;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that known planner output fixtures deserialize correctly into TaskPlan. These tests act
 * as a contract: if the model changes its output format, these break and alert us to update the
 * parsing logic before it reaches production.
 */
@DisplayName("Planner output contract")
class PlannerOutputContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static TaskPlan validPlan;

  @BeforeAll
  static void loadFixtures() throws IOException {
    validPlan = load("fixtures/llm/planner-valid.json", TaskPlan.class);
  }

  @Test
  @DisplayName("valid planner JSON parses to TaskPlan with correct task count")
  void validPlanTaskCount() {
    assertThat(validPlan.getTasks()).hasSize(3);
  }

  @Test
  @DisplayName("plan summary is present and non-blank")
  void planSummaryPresent() {
    assertThat(validPlan.getPlanSummary()).isNotBlank();
  }

  @Test
  @DisplayName("each task has required fields: task_id, title, objective, complexity")
  void taskRequiredFields() {
    for (TaskDefinition task : validPlan.getTasks()) {
      assertThat(task.getTaskId()).as("taskId for %s", task.getTitle()).isNotBlank();
      assertThat(task.getTitle()).as("title").isNotBlank();
      assertThat(task.getObjective()).as("objective for %s", task.getTaskId()).isNotBlank();
      assertThat(task.getComplexity())
          .as("complexity for %s", task.getTaskId())
          .isIn("LOW", "MEDIUM", "HIGH");
    }
  }

  @Test
  @DisplayName("task dependencies reference existing task IDs")
  void dependenciesReferenceExistingTasks() {
    List<String> allIds = validPlan.getTasks().stream().map(TaskDefinition::getTaskId).toList();
    for (TaskDefinition task : validPlan.getTasks()) {
      if (task.getDependsOn() != null) {
        for (String dep : task.getDependsOn()) {
          assertThat(allIds)
              .as("dependency '%s' for task '%s' must exist", dep, task.getTaskId())
              .contains(dep);
        }
      }
    }
  }

  @Test
  @DisplayName("task IDs are unique within the plan")
  void taskIdsAreUnique() {
    List<String> ids = validPlan.getTasks().stream().map(TaskDefinition::getTaskId).toList();
    assertThat(ids).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("markdown-fenced JSON is parseable after stripping fences")
  void markdownWrappedParses() throws IOException {
    String raw = loadString("fixtures/llm/planner-markdown-wrapped.json");
    String cleaned = raw.trim().replaceAll("(?s)^```json\\s*", "").replaceAll("```$", "").trim();

    TaskPlan plan = MAPPER.readValue(cleaned, TaskPlan.class);
    assertThat(plan.getTasks()).hasSize(1);
    assertThat(plan.getTasks().get(0).getTaskId()).isEqualTo("task-dark-toggle");
  }

  @Test
  @DisplayName("tasks in topological order (no task depends on a later task)")
  void topologicalOrderValid() {
    List<String> seenIds = new java.util.ArrayList<>();
    for (TaskDefinition task : validPlan.getTasks()) {
      if (task.getDependsOn() != null) {
        for (String dep : task.getDependsOn()) {
          assertThat(seenIds)
              .as(
                  "dependency '%s' of task '%s' must appear before it in the plan",
                  dep, task.getTaskId())
              .contains(dep);
        }
      }
      seenIds.add(task.getTaskId());
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static <T> T load(String resourcePath, Class<T> type) throws IOException {
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
      assertThat(in).as("fixture not found: " + resourcePath).isNotNull();
      return MAPPER.readValue(in, type);
    }
  }

  private static String loadString(String resourcePath) throws IOException {
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
      assertThat(in).as("fixture not found: " + resourcePath).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
