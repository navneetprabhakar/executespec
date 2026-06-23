package com.navneet.executespec.agentsession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.GateDecisionCard;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class AgentGateDecisionCardFactory {

  private final ProjectRepository projectRepo;
  private final ObjectMapper objectMapper;

  GateDecisionCard fromRun(PipelineRun run) {
    Project project =
        run.getProjectId() == null ? null : projectRepo.findById(run.getProjectId()).orElse(null);
    return new GateDecisionCard(
        run.getRunId(),
        run.getProjectId(),
        project == null ? null : project.getName(),
        AgentText.firstNonBlank(
            run.getRunTitle(), AgentText.trim(run.getUserRequest(), 80), "Untitled run"),
        run.getStatus(),
        run.getStartedAt(),
        run.getAiProvider(),
        AgentText.firstNonBlank(
            run.getPlannerActualModel(), run.getPlannerModel(), run.getWorkerModel()),
        run.getCostEstimateHigh(),
        run.getHardCapUsd(),
        run.getCreditsCharged(),
        inferRisk(run).toUpperCase(Locale.ROOT),
        AgentText.firstNonBlank(run.getRunSummary(), AgentText.trim(run.getUserRequest(), 220)),
        "Review and approve if the plan, cost, and changed areas match the requested work.",
        parseTasks(run.getPlanJson()));
  }

  private List<String> parseTasks(String planJson) {
    if (planJson == null || planJson.isBlank()) return List.of();
    try {
      JsonNode root = objectMapper.readTree(planJson);
      JsonNode tasks = root.path("tasks");
      if (!tasks.isArray()) tasks = root.path("plan").path("tasks");
      if (!tasks.isArray()) return List.of();
      List<String> out = new ArrayList<>();
      for (JsonNode t : tasks) {
        String title =
            AgentText.firstNonBlank(t.path("title").asText(null), t.path("name").asText(null));
        if (title != null) out.add(title);
        if (out.size() >= 8) break;
      }
      return out;
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private String inferRisk(PipelineRun run) {
    int tasks = parseTasks(run.getPlanJson()).size();
    if (tasks >= 6) return "high";
    if (tasks >= 3) return "medium";
    return "low";
  }
}
