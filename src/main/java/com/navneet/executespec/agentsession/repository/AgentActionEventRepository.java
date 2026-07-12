package com.navneet.executespec.agentsession.repository;

import java.util.List;

import com.navneet.executespec.agentsession.entity.AgentActionEvent;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentActionEventRepository extends JpaRepository<AgentActionEvent, String> {

  List<AgentActionEvent> findBySessionIdOrderByStreamSequenceAsc(String sessionId);

  List<AgentActionEvent> findBySessionIdOrderByStreamSequenceAsc(
      String sessionId, Pageable pageable);

  long countBySessionId(String sessionId);

  void deleteBySessionId(String sessionId);

  /** Legacy chronological view retained for recovery heuristics. */
  List<AgentActionEvent> findBySessionIdOrderByCreatedAtAsc(String sessionId);

  /** Bounded compatibility history page for clients that do not use cursor replay yet. */
  List<AgentActionEvent> findBySessionIdOrderByCreatedAtAsc(String sessionId, Pageable pageable);

  /**
   * Loads only the local-hands lifecycle events needed to reconstruct pending tool calls.
   *
   * <p>Recovery may run after a long-lived session has accumulated thousands of unrelated
   * transcript/action events. Keeping this query type-scoped prevents reconnect recovery from
   * materialising the entire session history just to find a handful of pending tool calls.
   */
  List<AgentActionEvent> findBySessionIdAndTypeInOrderByCreatedAtAsc(
      String sessionId, List<String> types);

  List<AgentActionEvent> findBySessionIdAndStreamSequenceGreaterThanOrderByStreamSequenceAsc(
      String sessionId, long after);

  List<AgentActionEvent> findByTurnIdOrderByCreatedAtAsc(String turnId);

  List<AgentActionEvent> findBySessionIdAndTypeOrderByCreatedAtAsc(String sessionId, String type);

  long countBySessionIdAndType(String sessionId, String type);

  /** Legacy rows pre-dating V262 have no durable cursor and cannot be replayed after a cursor. */
  long countBySessionIdAndStreamSequenceIsNull(String sessionId);
}
