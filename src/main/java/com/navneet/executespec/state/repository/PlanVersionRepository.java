package com.navneet.executespec.state.repository;

import java.util.List;

import com.navneet.executespec.state.entity.PlanVersion;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanVersionRepository extends JpaRepository<PlanVersion, String> {
  List<PlanVersion> findByRunIdOrderByVersionDesc(String runId);
}
