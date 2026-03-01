package com.navneet.agentteam.state.repository;

import com.navneet.agentteam.state.entity.FileLock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FileLockRepository extends JpaRepository<FileLock, String> {

    List<FileLock> findByRunId(String runId);

    void deleteByRunId(String runId);

    List<FileLock> findByTaskId(String taskId);
}
