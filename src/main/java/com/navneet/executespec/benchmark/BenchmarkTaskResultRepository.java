package com.navneet.executespec.benchmark;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link BenchmarkTaskResult}. */
public interface BenchmarkTaskResultRepository extends JpaRepository<BenchmarkTaskResult, String> {

  List<BenchmarkTaskResult> findByBenchmarkRunIdOrderByTaskNameAscAttemptAsc(String benchmarkRunId);

  long countByBenchmarkRunId(String benchmarkRunId);
}
