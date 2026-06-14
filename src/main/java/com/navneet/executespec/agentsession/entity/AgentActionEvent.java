package com.navneet.executespec.agentsession.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Append-only event stream for agent-session actions and intent decisions. */
@Entity
@Table(name = "agent_action_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentActionEvent {

  @Id private String id;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "turn_id")
  private String turnId;

  @Column(nullable = false, length = 48)
  private String type;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload_json", columnDefinition = "jsonb")
  private String payloadJson;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
