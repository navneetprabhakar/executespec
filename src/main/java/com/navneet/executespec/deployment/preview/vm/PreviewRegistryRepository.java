package com.navneet.executespec.deployment.preview.vm;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface PreviewRegistryRepository extends JpaRepository<PreviewRegistry, String> {

  List<PreviewRegistry> findByStatusIn(List<String> statuses);

  long countByStatus(String status);

  List<PreviewRegistry> findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
      String status, Instant time, Pageable pageable);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query(
      "UPDATE PreviewRegistry p SET p.status = 'gc_processing', p.errorMessage = NULL "
          + "WHERE p.id = :id AND p.status = 'active' AND p.expiresAt < :now")
  int claimExpiredForGc(@Param("id") String id, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query(
      "UPDATE PreviewRegistry p SET p.status = 'tearing_down' "
          + "WHERE p.id = :id AND p.status IN ('active', 'unhealthy', 'deploying')")
  int claimForTeardown(@Param("id") String id);

  @Query(
      "SELECT AVG(p.deployDurationS) FROM PreviewRegistry p "
          + "WHERE p.deployDurationS IS NOT NULL "
          + "ORDER BY p.createdAt DESC LIMIT :n")
  Double avgDeployDurationLastN(@Param("n") int n);

  @Query(
      "SELECT p.vmId, COUNT(p) FROM PreviewRegistry p "
          + "WHERE p.status IN ('active', 'deploying', 'unhealthy') GROUP BY p.vmId")
  List<Object[]> countActiveByVm();
}
