package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentGateDecisionCardFactoryTest {

  @Mock ProjectRepository projectRepo;

  @Test
  void buildsGateCardFromRunPlanAndProjectMetadata() {
    Project project = new Project();
    project.setId("p1");
    project.setName("ExecuteSpec");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(project));

    var factory = new AgentGateDecisionCardFactory(projectRepo, new ObjectMapper());
    var run =
        PipelineRun.builder()
            .runId("run-1")
            .projectId("p1")
            .runTitle("Add audit logging")
            .status("AWAITING_APPROVAL")
            .startedAt(Instant.parse("2026-06-20T00:00:00Z"))
            .aiProvider("DEEPSEEK")
            .plannerModel("deepseek-v4-flash-native")
            .workerModel("deepseek-v4-pro-native")
            .costEstimateHigh(0.42)
            .hardCapUsd(5.0)
            .creditsCharged(2)
            .runSummary("Plan waiting for approval")
            .planJson(
                """
{"plan":{"tasks":[{"name":"Create audit table"},{"title":"Record changes"},{"title":"Expose report"}]}}
""")
            .build();

    var card = factory.fromRun(run);

    assertThat(card.runId()).isEqualTo("run-1");
    assertThat(card.projectName()).isEqualTo("ExecuteSpec");
    assertThat(card.title()).isEqualTo("Add audit logging");
    assertThat(card.model()).isEqualTo("deepseek-v4-flash-native");
    assertThat(card.creditsRequired()).isEqualTo(2);
    assertThat(card.risk()).isEqualTo("MEDIUM");
    assertThat(card.tasks())
        .containsExactly("Create audit table", "Record changes", "Expose report");
  }

  @Test
  void fallsBackToRequestSummaryAndWorkerModelWhenPlanIsMalformed() {
    var factory = new AgentGateDecisionCardFactory(projectRepo, new ObjectMapper());
    String request =
        "Please implement the web agent control plane with managed AI state and plan gate cards.";
    var run =
        PipelineRun.builder()
            .runId("run-2")
            .status("AWAITING_APPROVAL")
            .userRequest(request)
            .workerModel("deepseek-v4-pro-native")
            .planJson("{not-json")
            .build();

    var card = factory.fromRun(run);

    assertThat(card.title()).startsWith("Please implement the web agent control plane");
    assertThat(card.summary()).isEqualTo(request);
    assertThat(card.model()).isEqualTo("deepseek-v4-pro-native");
    assertThat(card.tasks()).isEmpty();
    assertThat(card.risk()).isEqualTo("LOW");
  }
}
