package com.navneet.executespec.execution.entity;

import java.util.UUID;

import jakarta.persistence.*;

import lombok.*;

@Entity
@Table(name = "execution_solution_repo")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
@ToString(exclude = "solution")
public class ExecutionSolutionRepo {

  @Id
  @Column(length = 36)
  private String id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "solution_id", nullable = false)
  private ExecutionSolution solution;

  @Column(name = "repo_id", nullable = false, columnDefinition = "uuid")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.UUID)
  private UUID repoId;

  @Column(name = "execution_branch", length = 255)
  private String executionBranch;

  @Column(name = "target_files", columnDefinition = "TEXT")
  private String targetFiles;

  @Column(name = "actual_files", columnDefinition = "TEXT")
  private String actualFiles;

  @Column(name = "codemap_version", nullable = false)
  @Builder.Default
  private int codemapVersion = 0;

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID().toString();
  }
}
