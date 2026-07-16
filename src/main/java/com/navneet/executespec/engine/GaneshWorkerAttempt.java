package com.navneet.executespec.engine;

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
@Table(name = "ganesh_worker_attempt")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GaneshWorkerAttempt {
  @Id private String id;
  private String runId;
  private String taskId;
  private Integer attempt;
  private String provider;
  private String model;
  private String verificationStatus;
  private String failureStage;

  @Column(columnDefinition = "TEXT")
  private String evidenceJson;

  private Instant createdAt;
}
