package com.navneet.executespec.state.repository;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.state.entity.FileLock;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Locks are keyed on {@code (runId, filePath)} (RR-1544). There is deliberately NO lookup by {@code
 * filePath} alone: a bare relative path is not an identity, and treating it as one let one run
 * observe, block, or overwrite another tenant's lock.
 */
public interface FileLockRepository extends JpaRepository<FileLock, FileLock.FileLockId> {

  List<FileLock> findByRunId(String runId);

  void deleteByRunId(String runId);

  Optional<FileLock> findByRunIdAndFilePath(String runId, String filePath);

  void deleteByRunIdAndFilePath(String runId, String filePath);

  /**
   * Task ids are unique per run in practice; the run filter is kept explicit so a caller cannot
   * accidentally sweep another run's rows.
   */
  List<FileLock> findByRunIdAndTaskId(String runId, String taskId);

  List<FileLock> findByTaskId(String taskId);
}
