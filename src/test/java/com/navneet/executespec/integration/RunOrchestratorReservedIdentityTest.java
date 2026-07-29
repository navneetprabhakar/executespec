package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class RunOrchestratorReservedIdentityTest {

  @Test
  void durableCallerReservationWinsOverRandomRunIdentity() {
    String reserved = UUID.randomUUID().toString();

    assertThat(ReservedPipelineRunStore.runIdForSubmission(reserved)).isEqualTo(reserved);
  }

  @Test
  void ordinarySubmissionStillGetsFreshIdentity() {
    String first = ReservedPipelineRunStore.runIdForSubmission(null);
    String second = ReservedPipelineRunStore.runIdForSubmission(null);

    assertThat(UUID.fromString(first)).isNotNull();
    assertThat(UUID.fromString(second)).isNotNull();
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void concurrentReservedInsertReturnsTheExistingCanonicalRow() {
    PipelineRunRepository repository = mock(PipelineRunRepository.class);
    ReservedPipelineRunStore reservedStore = mock(ReservedPipelineRunStore.class);
    PipelineRun candidate = PipelineRun.builder().runId(UUID.randomUUID().toString()).build();
    PipelineRun canonical = PipelineRun.builder().runId(candidate.getRunId()).build();
    when(reservedStore.insert(candidate))
        .thenThrow(new DataIntegrityViolationException("duplicate reserved run id"));
    when(repository.findById(candidate.getRunId())).thenReturn(Optional.of(canonical));

    var result =
        ReservedPipelineRunStore.persistInitial(reservedStore, repository, candidate, true);

    assertThat(result.created()).isFalse();
    assertThat(result.run()).isSameAs(canonical);
  }

  @Test
  void ordinaryInsertNeverConvertsAnUnrelatedIntegrityFailureIntoReplay() {
    PipelineRunRepository repository = mock(PipelineRunRepository.class);
    ReservedPipelineRunStore reservedStore = mock(ReservedPipelineRunStore.class);
    PipelineRun candidate = PipelineRun.builder().runId(UUID.randomUUID().toString()).build();
    DataIntegrityViolationException failure =
        new DataIntegrityViolationException("other constraint");
    when(repository.save(candidate)).thenThrow(failure);

    assertThatThrownBy(
            () ->
                ReservedPipelineRunStore.persistInitial(
                    reservedStore, repository, candidate, false))
        .isSameAs(failure);
  }
}
