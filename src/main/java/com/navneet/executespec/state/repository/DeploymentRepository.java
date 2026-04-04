package com.navneet.executespec.state.repository;

import com.navneet.executespec.state.entity.Deployment;
import com.navneet.executespec.state.entity.Deployment.DeployStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeploymentRepository extends JpaRepository<Deployment, String> {

    /** Count active (non-deleted, non-expired) deployments for a user across all runs. */
    @Query("SELECT COUNT(d) FROM Deployment d JOIN PipelineRun r ON d.runId = r.runId " +
           "WHERE r.createdByUserId = :userId AND d.status IN ('LIVE', 'PENDING', 'BUILDING', 'PUSHING', 'DEPLOYING')")
    long countActiveByUserId(@Param("userId") String userId);

    List<Deployment> findByRunIdOrderByCreatedAtDesc(String runId);

    List<Deployment> findByStatus(DeployStatus status);

    List<Deployment> findByStatusAndTtlExpiresAtBefore(DeployStatus status, String isoTimestamp);

    Optional<Deployment> findTopByRunIdOrderByCreatedAtDesc(String runId);

    @Query("SELECT COUNT(d) FROM Deployment d WHERE d.runId = :runId")
    int countByRunId(String runId);

    @Query("SELECT COUNT(d) FROM Deployment d WHERE d.runId = :runId AND d.status IN ('PENDING','BUILDING','PUSHING','DEPLOYING')")
    int countActiveByRunId(String runId);
}
