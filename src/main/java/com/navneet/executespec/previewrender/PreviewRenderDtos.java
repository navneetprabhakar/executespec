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
      List<RenderSurfaceDto> surfaces,
      /**
       * Whether this deployment can actually render at all, i.e. whether the {@code
       * preview.render-agent.enabled} flag is on.
       *
       * <p>Without this the client cannot tell a permanently-disabled feature from a transient
       * outage: the endpoint answers 503 in both cases, and the UI rendered an always-enabled
       * "Render" button that produced "temporarily unavailable" — which is false when the feature
       * is simply off. Same failure shape as the unconditional OAuth buttons on the login page.
       */
      boolean available,
      /** Whether a POST would be admitted (or reuse the active job) without failing the gate. */
      boolean renderAllowed) {

    public static RenderManifest absent(String runId, boolean available, boolean renderAllowed) {
      return new RenderManifest(
          null, runId, "NONE", null, 0, 0, null, null, null, List.of(), available, renderAllowed);
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
