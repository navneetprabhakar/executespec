package com.navneet.executespec.billing.repository;

import java.util.List;

import com.navneet.executespec.billing.entity.ManagedAiUsageEvent;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagedAiUsageEventRepository extends JpaRepository<ManagedAiUsageEvent, String> {

  List<ManagedAiUsageEvent> findByPeriodIdOrderByCreatedAtDesc(String periodId, Pageable pageable);
}
