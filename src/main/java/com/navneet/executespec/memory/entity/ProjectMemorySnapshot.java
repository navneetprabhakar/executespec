package com.navneet.executespec.memory.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.*;

@Entity
@Table(name = "project_memory_snapshot")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMemorySnapshot {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "project_id", nullable = false, length = 36)
  private String projectId;

  @Column(nullable = false)
  private int version;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  @Column(name = "covers_runs_from", nullable = false)
  private int coversRunsFrom;

  @Column(name = "covers_runs_to", nullable = false)
  private int coversRunsTo;

  @Column(name = "token_count")
  private Integer tokenCount;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "org_id", nullable = false, length = 36)
  private String orgId;

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID().toString();
    if (createdAt == null) createdAt = Instant.now();
  }
}
