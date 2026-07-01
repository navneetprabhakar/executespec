package com.navneet.executespec.billing.repository;

import java.util.List;

import com.navneet.executespec.billing.entity.ManagedAiUsageEvent;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ManagedAiUsageEventRepository extends JpaRepository<ManagedAiUsageEvent, String> {

  List<ManagedAiUsageEvent> findByPeriodIdOrderByCreatedAtDesc(String periodId, Pageable pageable);

  @Query(
      "SELECT COALESCE(SUM(e.aiCreditsCharged), 0) FROM ManagedAiUsageEvent e WHERE e.runId ="
          + " :runId")
  long sumAiCreditsChargedByRunId(@Param("runId") String runId);
}
