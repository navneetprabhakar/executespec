package com.navneet.executespec.testgen.repository;

import com.navneet.executespec.testgen.model.TestTask;
import com.navneet.executespec.testgen.model.TestTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestTaskRepository extends JpaRepository<TestTask, Long> {

    List<TestTask> findByTestPlanId(Long testPlanId);

    List<TestTask> findByTestPlanIdAndStatus(Long testPlanId, TestTaskStatus status);
}
