package com.navneet.executespec.benchmark;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the restart-safe in-flight benchmark attempt projection. */
public interface BenchmarkActiveAttemptRepository
    extends JpaRepository<BenchmarkActiveAttempt, String> {
  List<BenchmarkActiveAttempt> findByBenchmarkRunIdOrderByStartedAtAsc(String benchmarkRunId);

  Optional<BenchmarkActiveAttempt> findByBenchmarkRunIdAndTaskNameAndAttempt(
      String benchmarkRunId, String taskName, int attempt);

  long countByBenchmarkRunId(String benchmarkRunId);

  void deleteByBenchmarkRunIdAndTaskNameAndAttempt(
      String benchmarkRunId, String taskName, int attempt);

  void deleteByBenchmarkRunId(String benchmarkRunId);
}
