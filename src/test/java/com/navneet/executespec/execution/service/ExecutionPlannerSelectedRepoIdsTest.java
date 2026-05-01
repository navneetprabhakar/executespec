package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * R-324 Checkpoint 1 — pins the launch-time repo selection parser used by the execution planner.
 * The parser is tolerant of every shape the column might hold today (NULL, "[]", legacy mis-typed
 * values) so the planner never crashes on weird selected_repo_ids data; it always either returns a
 * valid Set or an empty one.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionPlannerSelectedRepoIdsTest {

  @Mock com.navneet.executespec.agent.LlmRouter llmRouter;
  @Mock com.navneet.executespec.router.ModelResolver modelResolver;
  @Mock com.navneet.executespec.execution.repository.ExecutionRunRepository runRepo;
  @Mock com.navneet.executespec.execution.repository.ExecutionSolutionRepository solutionRepo;
  @Mock com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository codeMapRepo;
  @Mock com.navneet.executespec.project.repository.ProjectRepoRepository projectRepoRepo;
  @Mock com.navneet.executespec.memory.ProjectMemoryProvider memoryProvider;
  @Mock FileConflictDetector conflictDetector;
  @Mock com.navneet.executespec.sse.SseBroadcaster broadcaster;

  ExecutionPlannerService planner;

  @BeforeEach
  void wire() {
    planner =
        new ExecutionPlannerService(
            llmRouter,
            modelResolver,
            new ObjectMapper(),
            runRepo,
            solutionRepo,
            codeMapRepo,
            projectRepoRepo,
            memoryProvider,
            conflictDetector,
            broadcaster,
            new ExecutionRunCasUpdater(runRepo));
  }

  @Test
  void parsesValidJsonArrayOfUuids() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    String json = "[\"" + a + "\",\"" + b + "\"]";

    Set<UUID> parsed = planner.parseSelectedRepoIds(json);

    assertThat(parsed).containsExactlyInAnyOrder(a, b);
  }

  @Test
  void returnsEmptySetForNull() {
    assertThat(planner.parseSelectedRepoIds(null)).isEmpty();
  }

  @Test
  void returnsEmptySetForBlank() {
    assertThat(planner.parseSelectedRepoIds("")).isEmpty();
    assertThat(planner.parseSelectedRepoIds("   ")).isEmpty();
  }

  @Test
  void returnsEmptySetForExplicitlyEmptyJsonArray() {
    assertThat(planner.parseSelectedRepoIds("[]")).isEmpty();
  }

  @Test
  void skipsNonUuidEntriesGracefully() {
    UUID good = UUID.randomUUID();
    String json = "[\"" + good + "\",\"not-a-uuid\",\"\"]";

    Set<UUID> parsed = planner.parseSelectedRepoIds(json);

    assertThat(parsed).containsExactly(good);
  }

  @Test
  void returnsEmptySetForMalformedJson() {
    // Legacy / accidentally-truncated value — must not crash.
    assertThat(planner.parseSelectedRepoIds("[bad json")).isEmpty();
  }

  @Test
  void returnsEmptySetForJsonObject() {
    // Only top-level arrays are valid; everything else is "no selection".
    assertThat(planner.parseSelectedRepoIds("{\"foo\":\"bar\"}")).isEmpty();
  }
}
