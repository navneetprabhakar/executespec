package com.navneet.executespec.memory.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.*;

@Entity
@Table(name = "project_chat_message")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectChatMessage {

  @Id
  @Column(length = 64)
  private String id;

  @Column(name = "project_id", nullable = false, length = 64)
  private String projectId;

  @Column(name = "org_id", nullable = false, length = 64)
  private String orgId;

  @Column(name = "user_id", nullable = false, length = 64)
  private String userId;

  /** USER | ASSISTANT */
  @Column(nullable = false, length = 20)
  private String role;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  @Column(name = "tokens_in")
  private Integer tokensIn;

  @Column(name = "tokens_out")
  private Integer tokensOut;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID().toString();
    if (createdAt == null) createdAt = Instant.now();
  }
}
