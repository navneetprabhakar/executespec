package com.navneet.executespec.execution.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.*;

@Entity
@Table(name = "execution_context_handover")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionContextHandover {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "from_solution_id", nullable = false, length = 36)
  private String fromSolutionId;

  @Column(name = "to_solution_id", nullable = false, length = 36)
  private String toSolutionId;

  @Column(name = "contract_type", nullable = false, length = 30)
  private String contractType; // INTERFACE | DIFF_CODEMAP | TEST_ANCHOR

  @Column(name = "contract_json", nullable = false, columnDefinition = "TEXT")
  private String contractJson;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID().toString();
    if (createdAt == null) createdAt = Instant.now();
  }
}
