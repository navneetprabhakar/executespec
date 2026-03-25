package com.navneet.executespec.testgen.repository;

import com.navneet.executespec.testgen.model.TestPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TestPlanRepository extends JpaRepository<TestPlan, Long> {

    Optional<TestPlan> findByRunId(String runId);
}
