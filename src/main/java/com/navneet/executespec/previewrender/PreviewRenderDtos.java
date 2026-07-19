package com.navneet.executespec.previewrender;

import java.time.Instant;
import java.util.List;

/** Wire contracts for the preview render surface. */
public final class PreviewRenderDtos {

  private PreviewRenderDtos() {}

  /**
   * Status plus the surface list for a run's most recent render.
   *
   * @param status QUEUED | RENDERING | READY | NO_SURFACES | FAILED
   * @param fidelityNote operator-facing honesty label; always present when surfaces exist
   */
  public record RenderManifest(
      String jobId,
      String runId,
      String status,
      String failureReason,
      int surfacesFound,
      int surfacesRendered,
      String fidelityNote,
      Instant requestedAt,
      Instant completedAt,
      List<RenderSurfaceDto> surfaces) {

    public static RenderManifest absent(String runId) {
      return new RenderManifest(null, runId, "NONE", null, 0, 0, null, null, null, List.of());
    }
  }

  /** One screen in the surface switcher. */
  public record RenderSurfaceDto(
      String surfaceId,
      String displayName,
      String sourcePath,
      String target,
      String fidelity,
      int viewportWidth,
      int viewportHeight,
      boolean rendered,
      String failureReason) {}

  /** Response to a render request. */
  public record RenderStartResponse(String jobId, String status) {}
}
