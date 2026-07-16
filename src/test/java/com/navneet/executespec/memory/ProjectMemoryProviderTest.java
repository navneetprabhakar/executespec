package com.navneet.executespec.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.memory.entity.ProjectMemorySnapshot;
import com.navneet.executespec.memory.entity.ProjectRunLedger;
import com.navneet.executespec.memory.repository.ProjectMemorySnapshotRepository;
import com.navneet.executespec.memory.repository.ProjectRunLedgerRepository;

import org.junit.jupiter.api.Test;

class ProjectMemoryProviderTest {

  @Test
  void boundsPlannerMemoryAndKeepsDurableBaselineBeforeRecentRuns() {
    ProjectRunLedgerRepository ledger = mock(ProjectRunLedgerRepository.class);
    ProjectMemorySnapshotRepository snapshots = mock(ProjectMemorySnapshotRepository.class);
    String baseline = "baseline-" + "B".repeat(ProjectMemoryProvider.MAX_MEMORY_CHARS);
    when(snapshots.findTopByProjectIdOrderByVersionDesc("p1"))
        .thenReturn(
            Optional.of(
                ProjectMemorySnapshot.builder()
                    .projectId("p1")
                    .version(3)
                    .coversRunsFrom(1)
                    .coversRunsTo(30)
                    .content(baseline)
                    .build()));
    when(ledger.findTop10ByProjectIdAndCompactedIntoIsNullOrderByRunNumberDesc("p1"))
        .thenReturn(List.of(entry(31, "newest"), entry(30, "older")));

    String context = new ProjectMemoryProvider(ledger, snapshots).buildPlannerContext("p1");

    assertThat(context.length()).isLessThanOrEqualTo(ProjectMemoryProvider.MAX_MEMORY_CHARS);
    assertThat(context).startsWith("## PROJECT HISTORY");
    assertThat(context).doesNotContain("## RECENT RUNS");
  }

  @Test
  void includesNewestCompleteLedgerEntriesWhenBaselineLeavesRoom() {
    ProjectRunLedgerRepository ledger = mock(ProjectRunLedgerRepository.class);
    ProjectMemorySnapshotRepository snapshots = mock(ProjectMemorySnapshotRepository.class);
    when(snapshots.findTopByProjectIdOrderByVersionDesc("p1")).thenReturn(Optional.empty());
    when(ledger.findTop10ByProjectIdAndCompactedIntoIsNullOrderByRunNumberDesc("p1"))
        .thenReturn(List.of(entry(3, "newest"), entry(2, "middle"), entry(1, "oldest")));

    String context = new ProjectMemoryProvider(ledger, snapshots).buildPlannerContext("p1");

    assertThat(context).contains("## RECENT RUNS");
    assertThat(context.indexOf("oldest")).isLessThan(context.indexOf("middle"));
    assertThat(context.indexOf("middle")).isLessThan(context.indexOf("newest"));
  }

  private static ProjectRunLedger entry(int run, String value) {
    return ProjectRunLedger.builder()
        .projectId("p1")
        .runId("r" + run)
        .runNumber(run)
        .entry(value)
        .build();
  }
}
