package com.navneet.agentteam.state.repository;

import com.navneet.agentteam.state.entity.IntegrationEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface IntegrationEventLogRepository extends JpaRepository<IntegrationEventLog, Long> {

    boolean existsBySourceTypeAndEventId(String sourceType, String eventId);

    @Transactional
    @Modifying
    @Query("DELETE FROM IntegrationEventLog e WHERE e.createdAt < :cutoff")
    int deleteByCreatedAtBefore(Instant cutoff);
}
