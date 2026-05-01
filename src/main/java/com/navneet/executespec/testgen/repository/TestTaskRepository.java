package com.navneet.executespec.testgen.repository;

import java.util.List;

import com.navneet.executespec.testgen.model.TestTask;
import com.navneet.executespec.testgen.model.TestTaskStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TestTaskRepository extends JpaRepository<TestTask, Long> {

  List<TestTask> findByTestPlanId(Long testPlanId);

  List<TestTask> findByTestPlanIdAndStatus(Long testPlanId, TestTaskStatus status);
}
