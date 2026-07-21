package com.navneet.executespec.benchmark;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link BenchmarkRun}. */
public interface BenchmarkRunRepository extends JpaRepository<BenchmarkRun, String> {

  List<BenchmarkRun> findTop50ByOrderByCreatedAtDesc();

  List<BenchmarkRun> findTop50ByStatusOrderByCreatedAtDesc(String status);

  /** Serialize task/event ingestion for one run across application instances. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from BenchmarkRun r where r.id = :id")
  Optional<BenchmarkRun> findByIdForUpdate(@Param("id") String id);
}
