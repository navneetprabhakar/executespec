package com.navneet.executespec.memory.repository;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.memory.entity.ProjectRunLedger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRunLedgerRepository extends JpaRepository<ProjectRunLedger, String> {

  int countByProjectId(String projectId);

  List<ProjectRunLedger> findTop10ByProjectIdAndCompactedIntoIsNullOrderByRunNumberDesc(
      String projectId);

  @Query(
      "SELECT l FROM ProjectRunLedger l WHERE l.projectId = :projectId "
          + "AND l.compactedInto IS NULL AND l.runNumber <= :beforeRunNumber "
          + "ORDER BY l.runNumber ASC")
  List<ProjectRunLedger> findUncompactedBeforeRunNumber(
      @Param("projectId") String projectId, @Param("beforeRunNumber") int beforeRunNumber);

  List<ProjectRunLedger> findByProjectIdOrderByRunNumberDesc(String projectId);

  Optional<ProjectRunLedger> findByProjectIdAndRunId(String projectId, String runId);

  boolean existsByRunId(String runId);
}
