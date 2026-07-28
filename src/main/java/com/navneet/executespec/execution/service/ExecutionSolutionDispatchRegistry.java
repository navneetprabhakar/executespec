package com.navneet.executespec.execution.service;

import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.execution.entity.ExecutionSolutionRun;
import com.navneet.executespec.execution.repository.ExecutionSolutionRunRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Durable identity registry for one approved Execution View solution attempt.
 *
 * <p>The V90 unique key on {@code (solution_id, attempt_number)} is the atomic claim. The winning
 * insert reserves the child pipeline id <em>before</em> the orchestrator is invoked, so a
 * concurrent caller or a caller recovering after a process crash always discovers the same child
 * id. Provider, SCM, and pipeline side effects remain outside the repository transaction.
 */
@Service
@RequiredArgsConstructor
public class ExecutionSolutionDispatchRegistry {

  private final ExecutionSolutionRunRepository attemptRepository;
  private final PipelineRunRepository pipelineRunRepository;

  public Reservation reserve(String solutionId, int attemptGeneration) {
    if (solutionId == null || solutionId.isBlank()) {
      throw new IllegalArgumentException("solutionId is required");
    }
    if (attemptGeneration <= 0) {
      throw new IllegalArgumentException("attemptGeneration must be positive");
    }
    Optional<ExecutionSolutionRun> existing =
        attemptRepository.findBySolutionIdAndAttemptNumber(solutionId, attemptGeneration);
    if (existing.isPresent()) return new Reservation(existing.get(), false);

    ExecutionSolutionRun candidate =
        ExecutionSolutionRun.builder()
            .solutionId(solutionId)
            .pipelineRunId(UUID.randomUUID().toString())
            .attemptNumber(attemptGeneration)
            .build();
    try {
      return new Reservation(attemptRepository.saveAndFlush(candidate), true);
    } catch (DataIntegrityViolationException collision) {
      ExecutionSolutionRun winner =
          attemptRepository
              .findBySolutionIdAndAttemptNumber(solutionId, attemptGeneration)
              .orElseThrow(() -> collision);
      return new Reservation(winner, false);
    }
  }

  public Optional<PipelineRun> findCanonicalRun(Reservation reservation) {
    if (reservation == null
        || reservation.attempt() == null
        || reservation.attempt().getPipelineRunId() == null) {
      return Optional.empty();
    }
    return pipelineRunRepository.findById(reservation.attempt().getPipelineRunId());
  }

  public record Reservation(ExecutionSolutionRun attempt, boolean newlyReserved) {
    public Reservation {
      if (attempt == null) throw new IllegalArgumentException("attempt is required");
      if (attempt.getPipelineRunId() == null || attempt.getPipelineRunId().isBlank()) {
        throw new IllegalArgumentException("attempt must reserve a pipeline run id");
      }
    }
  }
}
