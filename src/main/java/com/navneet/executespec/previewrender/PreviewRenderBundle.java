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

/**
 * One rendered screen.
 *
 * <p>{@code html} is populated only when the guard admitted the bundle. A rejected render persists
 * as a row with {@code status=FAILED} and a category in {@code failureReason}, deliberately without
 * its content, so model output that failed admission is never stored in a servable column.
 */
@Entity
@Table(name = "preview_render_bundle")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreviewRenderBundle {

  public enum Status {
    RENDERED,
    FAILED
  }

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "job_id", nullable = false)
  private UUID jobId;

  @Column(name = "run_id", nullable = false, length = 64)
  private String runId;

  @Column(name = "surface_id", nullable = false, length = 120)
  private String surfaceId;

  @Column(name = "source_path", nullable = false, columnDefinition = "TEXT")
  private String sourcePath;

  @Column(name = "display_name", nullable = false, columnDefinition = "TEXT")
  private String displayName;

  @Column(name = "render_target", nullable = false, length = 32)
  private String renderTarget;

  @Column(name = "fidelity", nullable = false, length = 24)
  private String fidelity;

  @Column(name = "viewport_width", nullable = false)
  private int viewportWidth;

  @Column(name = "viewport_height", nullable = false)
  private int viewportHeight;

  @Column(name = "status", nullable = false, length = 24)
  private String status;

  @Column(name = "html", columnDefinition = "TEXT")
  private String html;

  @Column(name = "failure_reason", length = 160)
  private String failureReason;

  @Column(name = "display_order", nullable = false)
  @Builder.Default
  private int displayOrder = 0;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
