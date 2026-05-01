package com.navneet.executespec.state.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "agent_inbox")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentInbox {

  @Id private String messageId; // UUID

  private String runId;
  private String toAgent; // "worker-{taskId}" | "planner" | "reviewer"
  private String fromAgent;
  private String messageType; // COORDINATION | QUESTION | BLOCKER | FYI

  @Column(columnDefinition = "TEXT")
  private String content; // signal only — not payload data

  private boolean delivered; // false until injected into agent's next turn
  private Instant createdAt;
  private Instant deliveredAt;

  // Provider fallback tracking - null unless messageType == PROVIDER_FALLBACK
  private String fromProvider;
  private String toProvider;
}
