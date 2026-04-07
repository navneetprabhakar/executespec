package com.navneet.executespec.deployment.preview.vm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface PreviewRegistryRepository extends JpaRepository<PreviewRegistry, String> {

    List<PreviewRegistry> findByStatusIn(List<String> statuses);

    long countByStatus(String status);

    List<PreviewRegistry> findByStatusAndExpiresAtBefore(String status, Instant time);

    @Query("SELECT AVG(p.deployDurationS) FROM PreviewRegistry p " +
           "WHERE p.deployDurationS IS NOT NULL " +
           "ORDER BY p.createdAt DESC LIMIT :n")
    Double avgDeployDurationLastN(@Param("n") int n);

    @Query("SELECT p.vmId, COUNT(p) FROM PreviewRegistry p " +
           "WHERE p.status IN ('active', 'deploying', 'unhealthy') GROUP BY p.vmId")
    List<Object[]> countActiveByVm();
}
