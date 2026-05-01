package com.navneet.executespec.testgen.model;

import java.time.Instant;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "test_task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestTask {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "test_plan_id", nullable = false)
  private Long testPlanId;

  @Column(name = "task_name", nullable = false)
  private String taskName;

  @Column(name = "task_description", nullable = false, columnDefinition = "TEXT")
  private String taskDescription;

  @Column(name = "target_entity", length = 100)
  private String targetEntity;

  @Enumerated(EnumType.STRING)
  @Column(name = "test_category", nullable = false, length = 50)
  private TestCategory testCategory;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  @Builder.Default
  private TestTaskStatus status = TestTaskStatus.PENDING;

  @Column(name = "generated_code", columnDefinition = "TEXT")
  private String generatedCode;

  @Column(name = "compilation_error", columnDefinition = "TEXT")
  private String compilationError;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();

  @PreUpdate
  private void onUpdate() {
    this.updatedAt = Instant.now();
  }
}
