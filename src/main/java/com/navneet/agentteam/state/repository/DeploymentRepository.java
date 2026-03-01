package com.navneet.agentteam.state.repository;

import com.navneet.agentteam.state.entity.Deployment;
import com.navneet.agentteam.state.entity.Deployment.DeployStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DeploymentRepository extends JpaRepository<Deployment, String> {

    List<Deployment> findByRunIdOrderByCreatedAtDesc(String runId);

    List<Deployment> findByStatus(DeployStatus status);

    List<Deployment> findByStatusAndTtlExpiresAtBefore(DeployStatus status, String isoTimestamp);

    Optional<Deployment> findTopByRunIdOrderByCreatedAtDesc(String runId);

    @Query("SELECT COUNT(d) FROM Deployment d WHERE d.runId = :runId")
    int countByRunId(String runId);

    @Query("SELECT COUNT(d) FROM Deployment d WHERE d.runId = :runId AND d.status IN ('PENDING','BUILDING','PUSHING','DEPLOYING')")
    int countActiveByRunId(String runId);
}
