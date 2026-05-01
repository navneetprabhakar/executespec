package com.navneet.executespec.state.entity;

import java.time.Instant;

import jakarta.persistence.*;

import com.navneet.executespec.tenant.TenantInterceptor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Links a pipeline run to the external integration source that triggered it. */
@Entity
@EntityListeners(TenantInterceptor.class)
@Table(
    name = "integration_run",
    indexes = {@Index(name = "idx_integration_run_run_id", columnList = "run_id")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "run_id", nullable = false)
  private String runId; // FK to pipeline_run.run_id

  @Column(name = "source_type", nullable = false)
  private String sourceType; // JIRA | CONFLUENCE | SERVICENOW | PAGERDUTY

  @Column(name = "source_id", nullable = false)
  private String sourceId; // issue key, page ID, incident sysId, alert id

  @Column(name = "source_url")
  private String sourceUrl; // direct URL for outbound references

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "tenant_id", nullable = false)
  @Builder.Default
  private String tenantId = "default";
}
