package com.navneet.executespec.arch.invariants;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ArchInvariantViolationRepository
    extends JpaRepository<ArchInvariantViolation, UUID> {
  List<ArchInvariantViolation> findByInvariantIdOrderByDetectedAtDesc(UUID invariantId);

  /**
   * M-015 — recent violations across every invariant in a project, joined to {@link ArchInvariant}
   * via the FK on invariantId.
   */
  @Query(
      "SELECT v FROM ArchInvariantViolation v "
          + "WHERE v.invariantId IN ("
          + "  SELECT i.id FROM ArchInvariant i WHERE i.projectId = :projectId)"
          + " ORDER BY v.detectedAt DESC")
  List<ArchInvariantViolation> findRecentForProject(@Param("projectId") String projectId);
}
