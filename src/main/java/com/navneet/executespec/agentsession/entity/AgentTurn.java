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

/** One user-visible conversational turn inside an {@link AgentSession}. */
@Entity
@Table(name = "agent_turn")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTurn {

  @Id private String id;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "org_id", nullable = false)
  private String orgId;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(nullable = false)
  private int sequence;

  @Column(name = "user_message", columnDefinition = "TEXT")
  private String userMessage;

  @Column(name = "assistant_summary", columnDefinition = "TEXT")
  private String assistantSummary;

  @Column(name = "intent_type", nullable = false, length = 48)
  private String intentType;

  @Column(name = "intent_confidence", nullable = false)
  private double intentConfidence;

  @Column(name = "action_type", length = 64)
  private String actionType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "proposed_action_json", columnDefinition = "jsonb")
  private String proposedActionJson;

  @Column(name = "linked_run_id")
  private String linkedRunId;

  @Column(name = "input_tokens", nullable = false)
  @Builder.Default
  private long inputTokens = 0;

  @Column(name = "output_tokens", nullable = false)
  @Builder.Default
  private long outputTokens = 0;

  @Column(name = "reasoning_tokens", nullable = false)
  @Builder.Default
  private long reasoningTokens = 0;

  @Column(name = "cost_usd", nullable = false)
  @Builder.Default
  private double costUsd = 0.0;

  @Column(nullable = false, length = 32)
  @Builder.Default
  private String status = "OK";

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
