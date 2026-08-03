package com.navneet.executespec.previewrender;

import java.util.List;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * On-demand static UI preview.
 *
 * <p>Renders a run's screens as self-contained offline HTML without building or deploying anything.
 * The bundle endpoint serves model-authored markup, so it is locked down deliberately: a
 * restrictive CSP, {@code nosniff}, and no caching.
 *
 * <p>The client sandboxing is not an additional precaution — it is the containment boundary. The
 * client must mount the bundle in an iframe sandboxed <em>without</em> {@code allow-same-origin} so
 * it runs on an opaque origin. That matters because the UI fetches bundles with a bearer header and
 * mounts them via {@code srcdoc}, and on that path the response headers below do not govern the
 * document at all; only the embedded {@code <meta>} policy and the iframe attribute do. Of the meta
 * policy, {@code frame-ancestors} and {@code sandbox} are ignored per the CSP specification, so the
 * {@code sandbox} attribute is the only thing supplying the opaque origin.
 */
@Slf4j
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
public class PreviewRenderController {

  private final PreviewRenderService renderService;
  private final PreviewRenderGuard guard;
  private final RunAccessGuard accessGuard;

  /** Starts a render. Write-guarded: it spends managed AI credits. */
  @PostMapping("/{runId}/preview-render")
  public ResponseEntity<PreviewRenderDtos.RenderStartResponse> start(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {

    accessGuard.requireRunWriteAccess(runId, userDetails);
    PreviewRenderJob job =
        renderService.requestRender(runId, userDetails == null ? null : userDetails.getUsername());

    return ResponseEntity.accepted()
        .body(new PreviewRenderDtos.RenderStartResponse(job.getId().toString(), job.getStatus()));
  }

  /** Status and surface list for the run's latest render. */
  @GetMapping("/{runId}/preview-render")
  public ResponseEntity<PreviewRenderDtos.RenderManifest> manifest(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {

    accessGuard.requireRunAccess(runId, userDetails);

    boolean available = renderService.enabled();
    if (!available) {
      // The release flag is an availability boundary, not only a spend gate. Do not disclose stale
      // job or bundle metadata while the feature is disabled.
      return ResponseEntity.ok(PreviewRenderDtos.RenderManifest.absent(runId, false, false));
    }
    PreviewRenderJob job = renderService.latestJob(runId).orElse(null);
    // Feature-off wins and avoids a needless workspace/Git inspection. An active job remains
    // reloadable even if its workspace evidence disappears after it was admitted.
    boolean renderAllowed =
        (job != null && !job.isTerminal()) || renderService.canStartRender(runId);
    if (job == null) {
      return ResponseEntity.ok(
          PreviewRenderDtos.RenderManifest.absent(runId, available, renderAllowed));
    }
    return ResponseEntity.ok(
        toManifest(job, renderService.bundles(job.getId()), available, renderAllowed));
  }

  /**
   * Serves one admitted bundle as HTML for embedding in the sandboxed frame.
   *
   * <p>Returns 404 rather than an error page when the surface failed to render, so the client
   * cannot accidentally display rejected model output.
   */
  @GetMapping("/{runId}/preview-render/surfaces/{surfaceId}")
  public ResponseEntity<String> surface(
      @PathVariable String runId,
      @PathVariable String surfaceId,
      @AuthenticationPrincipal AppUserDetails userDetails) {

    accessGuard.requireRunAccess(runId, userDetails);
    if (!renderService.enabled()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "preview render is not enabled");
    }

    PreviewRenderJob job =
        renderService
            .latestJob(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no render"));

    PreviewRenderBundle bundle =
        renderService
            .bundle(job.getId(), surfaceId)
            .filter(b -> PreviewRenderBundle.Status.RENDERED.name().equals(b.getStatus()))
            .filter(b -> b.getHtml() != null)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "surface not rendered"));

    // Defence in depth: the bundle was admitted at render time, but it is re-checked before it is
    // ever handed to a browser, so a row written by an older or weaker guard cannot be served.
    PreviewRenderGuard.Verdict verdict = guard.admit(bundle.getHtml());
    if (!verdict.admitted()) {
      log.warn(
          "preview-render refused to serve stored bundle run={} surface={} reason={}",
          runId,
          surfaceId,
          verdict.reason());
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "surface not renderable");
    }

    HttpHeaders headers = new HttpHeaders();
    headers.set("Content-Security-Policy", guard.contentSecurityPolicy());
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("Referrer-Policy", "no-referrer");
    headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
    headers.setContentType(MediaType.TEXT_HTML);

    return new ResponseEntity<>(withEmbeddedCsp(bundle.getHtml()), headers, HttpStatus.OK);
  }

  /**
   * Embeds the policy in the document itself.
   *
   * <p>The client authenticates with a bearer header, which an iframe cannot send, so bundles are
   * fetched and mounted through {@code srcdoc}. A response header does not govern {@code srcdoc}
   * content, so the policy has to travel inside the markup to survive that path. The header is
   * still sent for anything that loads the URL directly.
   *
   * <p>{@code frame-ancestors} and {@code sandbox} do not survive this form — the CSP specification
   * ignores both in {@code <meta>} — so the embedding page's {@code sandbox} attribute has to carry
   * the opaque origin.
   */
  // Package-private so the hostile-markup cases can be pinned directly.
  String withEmbeddedCsp(String html) {
    String meta =
        "<meta http-equiv=\"Content-Security-Policy\" content=\""
            + guard.contentSecurityPolicy().replace("\"", "&quot;")
            + "\">";

    // RR-306: this used to splice the meta tag in after the first regex match for `<head...>`.
    // A regex cannot tell a real head tag from the same characters inside a comment, a script
    // string or an attribute, so model output containing an earlier decoy left the real document
    // head untouched and the policy inert inside a comment — exactly where the load-bearing
    // directives stop applying.
    //
    // Instead the server owns the front of the document and never edits the model's markup. A
    // meta element emitted before any other markup is placed into the document head by the HTML
    // parser's "before html" / "before head" insertion modes, so it is the FIRST head child no
    // matter what the model wrote. A later `<html>` or `<head>` start tag in the model's output
    // merges attributes rather than opening a second head, and a later doctype is ignored, so the
    // policy cannot be displaced or duplicated out of effect.
    return "<!doctype html>" + meta + html;
  }

  // ---------------------------------------------------------------- mapping

  private PreviewRenderDtos.RenderManifest toManifest(
      PreviewRenderJob job,
      List<PreviewRenderBundle> bundles,
      boolean available,
      boolean renderAllowed) {

    List<PreviewRenderDtos.RenderSurfaceDto> surfaces =
        bundles.stream()
            .map(
                b ->
                    new PreviewRenderDtos.RenderSurfaceDto(
                        b.getSurfaceId(),
                        b.getDisplayName(),
                        b.getSourcePath(),
                        b.getRenderTarget(),
                        b.getFidelity(),
                        b.getViewportWidth(),
                        b.getViewportHeight(),
                        PreviewRenderBundle.Status.RENDERED.name().equals(b.getStatus()),
                        b.getFailureReason()))
            .toList();

    return new PreviewRenderDtos.RenderManifest(
        job.getId().toString(),
        job.getRunId(),
        job.getStatus(),
        job.getFailureReason(),
        job.getSurfacesFound(),
        job.getSurfacesRendered(),
        surfaces.isEmpty() ? null : fidelityNote(surfaces),
        job.getRequestedAt(),
        job.getCompletedAt(),
        surfaces,
        available,
        renderAllowed);
  }

  /** One honest sentence about what the operator is looking at, driven by the weakest surface. */
  private String fidelityNote(List<PreviewRenderDtos.RenderSurfaceDto> surfaces) {
    boolean anyApproximate =
        surfaces.stream()
            .anyMatch(s -> PreviewRenderTarget.Fidelity.APPROXIMATE.name().equals(s.fidelity()));
    boolean anyStructural =
        surfaces.stream()
            .anyMatch(s -> PreviewRenderTarget.Fidelity.STRUCTURAL.name().equals(s.fidelity()));

    if (anyApproximate) {
      return "Approximation. Native layouts were interpreted from source, not executed, and the"
          + " data shown is invented. This is a design sketch, not a running app.";
    }
    if (anyStructural) {
      return "Structural preview. Layout is translated from the app's own source, but it renders in"
          + " a browser rather than on a device, and the data shown is invented.";
    }
    return "Rendered from your source with invented sample data. Not a running app — nothing here"
        + " was built or deployed.";
  }
}
