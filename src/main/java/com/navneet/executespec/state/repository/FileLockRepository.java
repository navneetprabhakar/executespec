package com.navneet.executespec.state.repository;

import java.util.List;

import com.navneet.executespec.state.entity.FileLock;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FileLockRepository extends JpaRepository<FileLock, String> {

  List<FileLock> findByRunId(String runId);

  void deleteByRunId(String runId);

  List<FileLock> findByTaskId(String taskId);
}
