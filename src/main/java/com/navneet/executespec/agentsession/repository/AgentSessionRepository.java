package com.navneet.executespec.agentsession.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import com.navneet.executespec.agentsession.entity.AgentSession;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentSessionRepository extends JpaRepository<AgentSession, String> {

  /** Serializes durable writer reservations for one parent session. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from AgentSession s where s.id = :id")
  Optional<AgentSession> findByIdForUpdate(@Param("id") String id);

  List<AgentSession> findByStatus(String status);

  /**
   * Returns only durable child-agent sessions for startup reconciliation. Parent/web sessions share
   * the same table and may legitimately use a RUNNING/READY status in future; recovery must never
   * cancel those rows merely because their status happens to match a child state.
   */
  List<AgentSession> findByStatusAndClientSurface(String status, String clientSurface);

  List<AgentSession> findByUserIdAndStatusOrderByUpdatedAtDesc(
      String userId, String status, Pageable pageable);

  List<AgentSession> findByRunIdAndStatusOrderByUpdatedAtDesc(String runId, String status);
}
