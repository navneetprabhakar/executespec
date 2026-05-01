package com.navneet.executespec.testgen.repository;

import java.util.Optional;

import com.navneet.executespec.testgen.model.TestPlan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TestPlanRepository extends JpaRepository<TestPlan, Long> {

  Optional<TestPlan> findByRunId(String runId);
}
