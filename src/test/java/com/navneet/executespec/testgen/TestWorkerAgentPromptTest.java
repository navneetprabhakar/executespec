package com.navneet.executespec.testgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.testgen.model.TestCategory;
import com.navneet.executespec.testgen.model.TestPlan;
import com.navneet.executespec.testgen.model.TestTask;
import com.navneet.executespec.testgen.repository.TestPlanRepository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class TestWorkerAgentPromptTest {

  private final TestPlanRepository testPlanRepository = mock(TestPlanRepository.class);
  private final TestWorkerAgent agent =
      new TestWorkerAgent(
          mock(com.navneet.executespec.agent.LlmRouter.class),
          mock(com.navneet.executespec.router.ModelResolver.class),
          mock(com.navneet.executespec.state.StateStore.class),
          mock(com.navneet.executespec.testgen.repository.TestTaskRepository.class),
          testPlanRepository,
          mock(TestProjectScaffold.class),
          new ObjectMapper());

  @Test
  void plannedMethodNamesComeFromTestPlanJson() {
    TestTask task = task();
    when(testPlanRepository.findById(42L)).thenReturn(Optional.of(plan()));

    String methodsJson = agent.plannedTestMethodsJson(task);

    assertThat(methodsJson).contains("shouldCreateUserWithHashedPassword");
    assertThat(methodsJson).contains("shouldReturn409WhenEmailAlreadyExists");
    assertThat(methodsJson).doesNotContain("Generate auth tests");
  }

  @Test
  void retryPromptKeepsApiProbeEvidence() {
    TestTask task = task();
    when(testPlanRepository.findById(42L)).thenReturn(Optional.of(plan()));
    agent.setPromptResourceForTest(
        new ByteArrayResource(
            """
            Methods={test_methods_json}
            Probe={api_probe_results}
            Name={task_name}
            Description={task_description}
            Target={target_entity}
            Category={category}
            Conventions={conventions_doc}
            Spec={user_request}
            Tech={technical_requirements}
            Framework={framework_instructions}
            Status={status_code_conventions}
            """
                .getBytes(StandardCharsets.UTF_8)));

    String prompt =
        agent.buildPrompt(
            task, "Use RestAssured", "Create user auth", false, false, "- GET /api/health -> 200");

    assertThat(prompt).contains("LIVE API PROBE RESULTS:");
    assertThat(prompt).contains("- GET /api/health -> 200");
    assertThat(prompt).contains("shouldCreateUserWithHashedPassword");
  }

  private static TestTask task() {
    return TestTask.builder()
        .id(7L)
        .testPlanId(42L)
        .taskName("UserAuthIT")
        .taskDescription("Generate auth tests")
        .targetEntity("User")
        .testCategory(TestCategory.AUTH)
        .build();
  }

  private static TestPlan plan() {
    return TestPlan.builder()
        .id(42L)
        .runId("run-1")
        .planJson(
            """
            [
              {
                "taskName": "UserAuthIT",
                "description": "Generate auth tests",
                "testMethods": [
                  {
                    "name": "shouldCreateUserWithHashedPassword",
                    "description": "create user and assert password hash is not plaintext"
                  },
                  {
                    "name": "shouldReturn409WhenEmailAlreadyExists",
                    "description": "duplicate email returns conflict"
                  }
                ]
              }
            ]
            """)
        .build();
  }
}
