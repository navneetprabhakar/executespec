package com.navneet.executespec.agent;

import java.time.Instant;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "prompt_audit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptAudit {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "run_id", nullable = false, length = 255)
  private String runId;

  @Column(name = "agent_type", nullable = false, length = 30)
  private String agentType;

  @Column(name = "prompt_hash", nullable = false, length = 12)
  private String promptHash;

  @Column(name = "char_count", nullable = false)
  private int charCount;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) createdAt = Instant.now();
  }
}
