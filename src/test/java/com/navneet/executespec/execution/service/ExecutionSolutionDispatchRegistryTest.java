package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.execution.entity.ExecutionSolutionRun;
import com.navneet.executespec.execution.repository.ExecutionSolutionRunRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Pins the V90 unique row as the pre-dispatch atomic claim rather than post-dispatch bookkeeping.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionSolutionDispatchRegistryTest {

  @Mock ExecutionSolutionRunRepository attemptRepository;
  @Mock PipelineRunRepository pipelineRunRepository;
  @InjectMocks ExecutionSolutionDispatchRegistry registry;

  @Test
  void firstCallerReservesCanonicalChildBeforeSubmission() {
    when(attemptRepository.findBySolutionIdAndAttemptNumber("sol-1", 1))
        .thenReturn(Optional.empty());
    when(attemptRepository.saveAndFlush(any(ExecutionSolutionRun.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var reservation = registry.reserve("sol-1", 1);

    assertThat(reservation.newlyReserved()).isTrue();
    assertThat(reservation.attempt().getSolutionId()).isEqualTo("sol-1");
    assertThat(reservation.attempt().getAttemptNumber()).isEqualTo(1);
    assertThat(reservation.attempt().getPipelineRunId()).isNotBlank();
  }

  @Test
  void concurrentUniqueCollisionConvergesOnWinningReservation() {
    ExecutionSolutionRun winner = attempt("sol-1", 1, UUID.randomUUID().toString());
    when(attemptRepository.findBySolutionIdAndAttemptNumber("sol-1", 1))
        .thenReturn(Optional.empty(), Optional.of(winner));
    when(attemptRepository.saveAndFlush(any(ExecutionSolutionRun.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate solution generation"));

    var reservation = registry.reserve("sol-1", 1);

    assertThat(reservation.newlyReserved()).isFalse();
    assertThat(reservation.attempt()).isSameAs(winner);
    verify(attemptRepository, times(2)).findBySolutionIdAndAttemptNumber("sol-1", 1);
  }

  @Test
  void crashRecoveryFindsPipelineByPreReservedCanonicalId() {
    String runId = UUID.randomUUID().toString();
    var reservation =
        new ExecutionSolutionDispatchRegistry.Reservation(attempt("sol-1", 3, runId), false);
    PipelineRun canonical = PipelineRun.builder().runId(runId).build();
    when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(canonical));

    assertThat(registry.findCanonicalRun(reservation).orElseThrow()).isSameAs(canonical);
  }

  private static ExecutionSolutionRun attempt(String solutionId, int generation, String runId) {
    return ExecutionSolutionRun.builder()
        .id(UUID.randomUUID().toString())
        .solutionId(solutionId)
        .attemptNumber(generation)
        .pipelineRunId(runId)
        .build();
  }
}
