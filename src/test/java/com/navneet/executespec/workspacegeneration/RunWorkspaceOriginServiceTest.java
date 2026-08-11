package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

class RunWorkspaceOriginServiceTest {

  private final PipelineRunRepository runs = mock(PipelineRunRepository.class);
  private final EntityManager entityManager = mock(EntityManager.class);
  private final RunWorkspaceOriginService service =
      new RunWorkspaceOriginService(runs, entityManager);

  @Test
  void persistedOriginWinsOverHistoricalInference() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-1")
            .projectId("project-1")
            .workspaceOrigin("GREENFIELD")
            .build();
    when(runs.findById("run-1")).thenReturn(Optional.of(run));

    assertThat(service.load("run-1", List.of("repo-1"))).isEqualTo(WorkspaceOrigin.GREENFIELD);
  }

  @Test
  void projectAdmissionRequiresExactlyOneDurableUpdate() {
    Query query = mock(Query.class);
    when(entityManager.createQuery(
            "UPDATE PipelineRun r SET r.workspaceOrigin = :origin WHERE r.runId = :runId"))
        .thenReturn(query);
    when(query.setParameter("origin", "MULTI_REPOSITORY")).thenReturn(query);
    when(query.setParameter("runId", "run-1")).thenReturn(query);
    when(query.executeUpdate()).thenReturn(0);

    assertThatThrownBy(() -> service.refineProjectAdmission("run-1", null, 2))
        .hasMessage("WORKSPACE_ORIGIN_PERSIST_FAILED:run-1");
  }

  @Test
  void blankSourceRunDoesNotMasqueradeAsIteration() {
    Query query = mock(Query.class);
    when(entityManager.createQuery(
            "UPDATE PipelineRun r SET r.workspaceOrigin = :origin WHERE r.runId = :runId"))
        .thenReturn(query);
    when(query.setParameter("origin", "GREENFIELD")).thenReturn(query);
    when(query.setParameter("runId", "run-1")).thenReturn(query);
    when(query.executeUpdate()).thenReturn(1);

    service.refineProjectAdmission("run-1", "", 0);
  }

  @Test
  void iterationNeverGetsReclassifiedFromProjectRepositories() {
    service.refineProjectAdmission("run-1", "source-run", 2);
    verifyNoInteractions(entityManager);
  }
}
