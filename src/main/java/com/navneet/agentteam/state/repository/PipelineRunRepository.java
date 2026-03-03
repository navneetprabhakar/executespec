package com.navneet.agentteam.state.repository;

import com.navneet.agentteam.state.entity.PipelineRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, String> {

    List<PipelineRun> findByStatusIn(List<String> statuses);

    Optional<PipelineRun> findTopByStatusInOrderByStartedAtDesc(List<String> statuses);

    // ── Tier 3 additions ─────────────────────────────────────────────────────
    Page<PipelineRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    Page<PipelineRun> findByStatusOrderByStartedAtDesc(String status, Pageable pageable);

    long countByStatus(String status);

    @Query("SELECT COALESCE(SUM(r.totalInputTokens), 0) FROM PipelineRun r")
    Long sumInputTokens();

    @Query("SELECT COALESCE(SUM(r.totalOutputTokens), 0) FROM PipelineRun r")
    Long sumOutputTokens();
}
