package com.navneet.executespec.testgen.repository;

import java.util.List;

import com.navneet.executespec.testgen.model.TestResult;
import com.navneet.executespec.testgen.model.TestResultStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TestResultRepository extends JpaRepository<TestResult, Long> {

  List<TestResult> findByTestPlanId(Long testPlanId);

  int countByTestPlanIdAndStatus(Long testPlanId, TestResultStatus status);
}
