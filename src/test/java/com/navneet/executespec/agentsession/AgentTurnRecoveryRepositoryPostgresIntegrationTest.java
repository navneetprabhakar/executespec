package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;

import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.integration.PostgresIntegrationBase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

class AgentTurnRecoveryRepositoryPostgresIntegrationTest extends PostgresIntegrationBase {

  private static final String SESSION_ID = "stale-turn-recovery-it";

  @Autowired AgentTurnRepository turns;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM agent_session WHERE id=?", SESSION_ID);
    jdbc.update(
        """
        INSERT INTO agent_session
          (id, org_id, user_id, client_surface, title, status, created_at, updated_at)
        VALUES (?, 'org-it', 'user-it', 'WEB', 'recovery', 'ACTIVE',
                clock_timestamp(), clock_timestamp())
        """,
        SESSION_ID);
  }

  @AfterEach
  void cleanUp() {
    jdbc.update("DELETE FROM agent_session WHERE id=?", SESSION_ID);
  }

  @Test
  void staleStreamingScanIsOldestFirstBoundedAndUsesDatabaseTime() {
    Instant databaseNow = turns.databaseNow();
    insertTurn("turn-oldest", "STREAMING", databaseNow.minusSeconds(10_000), 1);
    insertTurn("turn-second", "STREAMING", databaseNow.minusSeconds(9_000), 2);
    insertTurn("turn-third", "STREAMING", databaseNow.minusSeconds(8_000), 3);
    insertTurn("turn-fresh", "STREAMING", databaseNow.minusSeconds(10), 4);
    insertTurn("turn-error", "ERROR", databaseNow.minusSeconds(20_000), 5);

    var found = turns.findStaleStreaming(databaseNow.minusSeconds(100), PageRequest.of(0, 2));

    assertThat(found)
        .extracting(turn -> turn.getId())
        .containsExactly("turn-oldest", "turn-second");
  }

  @Test
  void v355PartialIndexMatchesRecoveryPredicateAndOrdering() {
    String definition =
        jdbc.queryForObject(
            """
            SELECT indexdef
            FROM pg_indexes
            WHERE schemaname = current_schema()
              AND indexname = 'idx_agent_turn_stale_streaming'
            """,
            String.class);

    assertThat(definition)
        .contains("(created_at, id)")
        .containsIgnoringCase("WHERE")
        .contains("status")
        .contains("STREAMING");
  }

  private void insertTurn(String id, String status, Instant createdAt, int sequence) {
    jdbc.update(
        """
        INSERT INTO agent_turn
          (id, session_id, org_id, user_id, sequence, intent_type,
           intent_confidence, status, created_at)
        VALUES (?, ?, 'org-it', 'user-it', ?, 'ANSWER', 1.0, ?, ?)
        """,
        id,
        SESSION_ID,
        sequence,
        status,
        Timestamp.from(createdAt));
  }
}
