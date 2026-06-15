package com.navneet.executespec.cost;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RunStepUsageRepository extends JpaRepository<RunStepUsage, String> {
  List<RunStepUsage> findByRunIdOrderByCreatedAtAsc(String runId);
}
