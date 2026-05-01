package com.navneet.executespec.integration.slack.model;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SlackNotificationLogRepository extends JpaRepository<SlackNotificationLog, Long> {

  List<SlackNotificationLog> findByRunIdOrderByCreatedAtDesc(String runId);

  List<SlackNotificationLog> findByOrgIdOrderByCreatedAtDesc(String orgId, Pageable pageable);

  @Modifying
  @Transactional
  @Query("DELETE FROM SlackNotificationLog l WHERE l.createdAt < :cutoff")
  long deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
