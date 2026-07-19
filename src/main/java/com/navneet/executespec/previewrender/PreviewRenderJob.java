package com.navneet.executespec.previewrender;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One on-demand render request for a run. Seeded by V302. */
@Entity
@Table(name = "preview_render_job")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreviewRenderJob {

  /** Lifecycle of a render request. */
  public enum Status {
    QUEUED,
    RENDERING,
    READY,
    /** Discovery found nothing renderable. Not an error — the run may have no UI. */
    NO_SURFACES,
    FAILED
  }

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "run_id", nullable = false, length = 64)
  private String runId;

  @Column(name = "status", nullable = false, length = 24)
  @Builder.Default
  private String status = Status.QUEUED.name();

  @Column(name = "requested_by", length = 64)
  private String requestedBy;

  @Column(name = "provider", length = 32)
  private String provider;

  @Column(name = "model", length = 160)
  private String model;

  @Column(name = "surfaces_found", nullable = false)
  @Builder.Default
  private int surfacesFound = 0;

  @Column(name = "surfaces_rendered", nullable = false)
  @Builder.Default
  private int surfacesRendered = 0;

  @Column(name = "failure_reason", length = 160)
  private String failureReason;

  @Column(name = "lease_owner", length = 120)
  private String leaseOwner;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "requested_at", nullable = false)
  @Builder.Default
  private Instant requestedAt = Instant.now();

  @Column(name = "completed_at")
  private Instant completedAt;

  public boolean isTerminal() {
    Status s = Status.valueOf(status);
    return s == Status.READY || s == Status.FAILED || s == Status.NO_SURFACES;
  }
}
