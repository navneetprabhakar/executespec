package com.navneet.executespec.testgen.model;

import java.time.Instant;

import jakarta.persistence.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "test_plan")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestPlan {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "run_id", nullable = false)
  private String runId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  @Builder.Default
  private TestPlanStatus status = TestPlanStatus.PENDING;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "plan_json", columnDefinition = "JSONB")
  private String planJson;

  @Column(name = "conventions_doc", columnDefinition = "TEXT")
  private String conventionsDoc;

  // ── LLM token tracking for cost breakdown ──────────────────────────────
  @Column(name = "planner_input_tokens")
  @Builder.Default
  private Integer plannerInputTokens = 0;

  @Column(name = "planner_output_tokens")
  @Builder.Default
  private Integer plannerOutputTokens = 0;

  @Column(name = "worker_input_tokens")
  @Builder.Default
  private Integer workerInputTokens = 0;

  @Column(name = "worker_output_tokens")
  @Builder.Default
  private Integer workerOutputTokens = 0;

  @Column(name = "report_input_tokens")
  @Builder.Default
  private Integer reportInputTokens = 0;

  @Column(name = "report_output_tokens")
  @Builder.Default
  private Integer reportOutputTokens = 0;

  @Column(name = "planner_provider")
  private String plannerProvider;

  @Column(name = "planner_model")
  private String plannerModel;

  @Column(name = "worker_provider")
  private String workerProvider;

  @Column(name = "worker_model")
  private String workerModel;

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
