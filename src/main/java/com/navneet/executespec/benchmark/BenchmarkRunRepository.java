package com.navneet.executespec.benchmark;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link BenchmarkRun}. */
public interface BenchmarkRunRepository extends JpaRepository<BenchmarkRun, String> {

  List<BenchmarkRun> findTop50ByOrderByCreatedAtDesc();
}
