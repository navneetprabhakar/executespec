package com.navneet.executespec.testgen.model;

import java.time.Instant;

import jakarta.persistence.*;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "test_result")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestResult {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @JsonProperty("testResultId")
  private Long id;

  @Column(name = "test_plan_id", nullable = false)
  private Long testPlanId;

  @Column(name = "test_task_id", nullable = false)
  private Long testTaskId;

  @Column(name = "test_method", nullable = false)
  private String testMethod;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private TestResultStatus status;

  @Column(name = "failure_message", columnDefinition = "TEXT")
  private String failureMessage;

  @Column(name = "expected_value", columnDefinition = "TEXT")
  private String expectedValue;

  @Column(name = "actual_value", columnDefinition = "TEXT")
  private String actualValue;

  @Column(name = "suggestion", columnDefinition = "TEXT")
  private String suggestion;

  @Column(name = "duration_ms")
  private Integer durationMs;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
