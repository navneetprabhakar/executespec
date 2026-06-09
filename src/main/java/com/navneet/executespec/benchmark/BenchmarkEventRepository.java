package com.navneet.executespec.benchmark;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link BenchmarkEvent}. */
public interface BenchmarkEventRepository extends JpaRepository<BenchmarkEvent, String> {

  List<BenchmarkEvent> findByBenchmarkRunIdOrderBySeqAsc(String benchmarkRunId);

  long countByBenchmarkRunId(String benchmarkRunId);
}
