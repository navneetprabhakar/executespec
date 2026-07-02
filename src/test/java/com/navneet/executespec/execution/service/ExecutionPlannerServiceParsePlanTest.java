package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class ExecutionPlannerServiceParsePlanTest {

  private final ExecutionPlannerService planner =
      new ExecutionPlannerService(
          mock(com.navneet.executespec.agent.FallbackLlmRouter.class),
          mock(com.navneet.executespec.router.ModelResolver.class),
          new ObjectMapper(),
          mock(com.navneet.executespec.execution.repository.ExecutionRunRepository.class),
          mock(com.navneet.executespec.execution.repository.ExecutionSolutionRepository.class),
          mock(com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository.class),
          mock(com.navneet.executespec.project.repository.ProjectRepoRepository.class),
          mock(com.navneet.executespec.memory.ProjectMemoryProvider.class),
          mock(FileConflictDetector.class),
          mock(com.navneet.executespec.sse.SseBroadcaster.class),
          mock(ExecutionRunCasUpdater.class));

  @Test
  void solutionDependsOnIsConvertedToDependencyEdges() throws Exception {
    var plan =
        planner.parsePlan(
            """
            {
              "solutions": [
                {
                  "sequenceNumber": 1,
                  "title": "API contract",
                  "description": "Add backend API",
                  "repoId": "11111111-1111-1111-1111-111111111111",
                  "repoName": "api",
                  "targetFiles": ["src/api.ts"],
                  "dependsOn": [],
                  "estimatedTokens": 15000,
                  "complexity": "LOW"
                },
                {
                  "sequenceNumber": 2,
                  "title": "Frontend consumer",
                  "description": "Call backend API",
                  "repoId": "22222222-2222-2222-2222-222222222222",
                  "repoName": "web",
                  "targetFiles": ["src/App.tsx"],
                  "dependsOn": [1],
                  "estimatedTokens": 30000,
                  "complexity": "MEDIUM"
                }
              ],
              "dependencyEdges": []
            }
            """);

    assertThat(plan.dependencyEdges()).extracting("from", "to").containsExactly(tuple(1, 2));
  }

  @Test
  void duplicateExplicitAndImplicitDependencyEdgesAreCollapsed() throws Exception {
    var plan =
        planner.parsePlan(
            """
            {
              "solutions": [
                {"sequenceNumber": 1, "title": "API", "dependsOn": []},
                {"sequenceNumber": 2, "title": "Web", "dependsOn": [1]}
              ],
              "dependencyEdges": [{"from":1,"to":2}]
            }
            """);

    assertThat(plan.dependencyEdges()).hasSize(1);
    assertThat(plan.dependencyEdges().get(0).from()).isEqualTo(1);
    assertThat(plan.dependencyEdges().get(0).to()).isEqualTo(2);
  }
}
