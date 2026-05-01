package com.navneet.executespec.memory.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.*;

@Entity
@Table(name = "project_run_ledger")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectRunLedger {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "project_id", nullable = false, length = 36)
  private String projectId;

  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @Column(name = "run_number", nullable = false)
  private int runNumber;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String entry;

  @Column(name = "token_count")
  private Integer tokenCount;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "compacted_into", length = 36)
  private String compactedInto;

  @Column(name = "org_id", nullable = false, length = 36)
  private String orgId;

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID().toString();
    if (createdAt == null) createdAt = Instant.now();
  }
}
