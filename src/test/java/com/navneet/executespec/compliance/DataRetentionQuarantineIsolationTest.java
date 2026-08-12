package com.navneet.executespec.compliance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.integration.RunLifecycleService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DataRetentionQuarantineIsolationTest {

  @Test
  void oneDatabaseHeldRunDoesNotStarveAnotherEligibleDeletion() {
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    ProjectRepository projects = mock(ProjectRepository.class);
    TierConfigRepository tiers = mock(TierConfigRepository.class);
    PendingErasureObligationRepository obligations = mock(PendingErasureObligationRepository.class);
    ErasureObligationStore obligationStore = mock(ErasureObligationStore.class);
    PipelineRun held = PipelineRun.builder().runId("held-history").build();
    PipelineRun eligible = PipelineRun.builder().runId("eligible-run").build();
    when(tiers.findAll()).thenReturn(List.of());
    when(runs.findSoftDeletedOlderThan(any(), anyList())).thenReturn(List.of(held, eligible));
    when(projects.findByDeletedAtBefore(any())).thenReturn(List.of());
    when(runs.findArchivedRunsOlderThan(any())).thenReturn(List.of());
    when(obligations.findAllByOrderByRequestedAtAsc()).thenReturn(List.of());
    doThrow(new IllegalStateException("legacy BUILD history is under retention hold"))
        .when(runs)
        .delete(held);

    new DataRetentionService(
            runs,
            projects,
            tiers,
            new SimpleMeterRegistry(),
            mock(RunLifecycleService.class),
            obligations,
            obligationStore)
        .runRetentionSweep();

    verify(runs).delete(held);
    verify(runs).delete(eligible);
  }
}
