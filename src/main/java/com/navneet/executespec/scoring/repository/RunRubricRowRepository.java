package com.navneet.executespec.scoring.repository;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.scoring.entity.RunRubricRow;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Scoring Revamp Phase A (V197, 2026-05-14) — JPA repository for the per-run rubric.
 *
 * <p>{@link #findByRunIdOrderByRubricRowIdAsc} is the only hot-path method right now. The verifier
 * walks it once after task execution; the (eventual) UI walks it once per page load.
 */
public interface RunRubricRowRepository extends JpaRepository<RunRubricRow, UUID> {

  List<RunRubricRow> findByRunIdOrderByRubricRowIdAsc(String runId);

  /** Existence check used by the verifier to skip the whole pass when no rubric was emitted. */
  boolean existsByRunId(String runId);
}
