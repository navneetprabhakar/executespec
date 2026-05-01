package com.navneet.executespec.state.repository;

import java.util.List;

import com.navneet.executespec.state.entity.MergeConflict;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MergeConflictRepository extends JpaRepository<MergeConflict, Long> {

  List<MergeConflict> findByRunId(String runId);

  List<MergeConflict> findByTaskId(String taskId);

  /** Top N most-conflicted file paths across all runs, ordered by conflict count descending. */
  @Query(
      value =
          """
          SELECT mc.file_path, COUNT(*) as cnt,
                 SUM(CASE WHEN mc.resolution != 'FAILED' THEN 1 ELSE 0 END) as resolved_cnt
          FROM merge_conflict mc
          GROUP BY mc.file_path
          ORDER BY cnt DESC
          LIMIT :limit
          """,
      nativeQuery = true)
  List<Object[]> findTopConflictedFiles(@Param("limit") int limit);

  /** Auto-resolution success rate across the last N runs. */
  @Query(
      value =
          """
SELECT COUNT(*) as total,
       SUM(CASE WHEN mc.resolution != 'FAILED' THEN 1 ELSE 0 END) as auto_resolved
FROM merge_conflict mc
WHERE mc.run_id IN (
    SELECT run_id FROM merge_conflict GROUP BY run_id ORDER BY MAX(created_at) DESC LIMIT :runLimit
)
""",
      nativeQuery = true)
  List<Object[]> getResolutionStats(@Param("runLimit") int runLimit);
}
