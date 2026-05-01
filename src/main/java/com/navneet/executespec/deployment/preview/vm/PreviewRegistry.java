package com.navneet.executespec.deployment.preview.vm;

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
@Table(name = "preview_registry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreviewRegistry {

  @Id private String id;

  @Column(name = "execution_id", nullable = false)
  private String executionId;

  private String projectName;

  @Column(nullable = false)
  private String url;

  @Column(name = "vm_id", nullable = false)
  private String vmId;

  @Column(name = "vm_ip", nullable = false)
  private String vmIp;

  private int port;

  @Column(nullable = false)
  private String status;

  private Boolean healthy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "torn_down_at")
  private Instant tornDownAt;

  @Column(name = "ttl_hours", nullable = false)
  private int ttlHours;

  @Column(name = "deploy_duration_s")
  private Integer deployDurationS;

  @Column(columnDefinition = "TEXT")
  private String errorMessage;
}
