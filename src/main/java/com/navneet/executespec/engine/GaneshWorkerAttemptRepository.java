package com.navneet.executespec.engine;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GaneshWorkerAttemptRepository extends JpaRepository<GaneshWorkerAttempt, String> {
  List<GaneshWorkerAttempt> findByRunIdOrderByTaskIdAscAttemptAsc(String runId);
}
