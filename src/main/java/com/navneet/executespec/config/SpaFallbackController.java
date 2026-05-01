package com.navneet.executespec.config;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves index.html for all non-API, non-asset routes so React Router can handle deep links (e.g.
 * navigating directly to /dashboard, /runs/123).
 *
 * <p>Uses {@code [^\\.]*} regex to only match paths WITHOUT a file extension — this lets requests
 * for static assets (.js, .css, .ico, etc.) fall through to Spring Boot's default static resource
 * handler.
 *
 * <p>Directly returns the ClassPathResource instead of forwarding to avoid re-dispatch issues with
 * catch-all controller mappings.
 *
 * <p><b>Backend prefixes (api, actuator) are explicitly excluded</b> — closes
 * adversarial-test-report-2026-04-18 Finding 3. Previously, unmapped routes like {@code
 * /api/admin/nonexistent} or {@code /actuator/env} (when disabled) fell through to these catch-all
 * view mappings and returned 200 with the SPA HTML. Now backend prefixes return 404 JSON via the
 * default error handler, matching the behaviour on 4+ segment paths.
 */
@Controller
public class SpaFallbackController {

  private static final Resource INDEX_HTML = new ClassPathResource("static/index.html");

  /** First-segment names that must never be shadowed by the SPA fallback. */
  private static final Set<String> BACKEND_PREFIXES = Set.of("api", "actuator", "webhooks");

  /** Root path / */
  @GetMapping("/")
  public ResponseEntity<Resource> root() {
    return serveIndex();
  }

  /** Single-level paths: /login, /dashboard, /settings, etc. */
  @GetMapping("/{path:[^\\.]*}")
  public ResponseEntity<Resource> spaLevel1(HttpServletRequest request) {
    rejectBackendPrefix(request);
    return serveIndex();
  }

  /** Multi-level paths: /runs/123, /org/settings, /projects/abc/runs, etc. */
  @GetMapping("/{a:[^\\.]*}/{path:[^\\.]*}")
  public ResponseEntity<Resource> spaLevel2(HttpServletRequest request) {
    rejectBackendPrefix(request);
    return serveIndex();
  }

  /** Three-level paths: /org/projects/123, etc. */
  @GetMapping("/{a:[^\\.]*}/{b:[^\\.]*}/{path:[^\\.]*}")
  public ResponseEntity<Resource> spaLevel3(HttpServletRequest request) {
    rejectBackendPrefix(request);
    return serveIndex();
  }

  private static void rejectBackendPrefix(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null || uri.length() < 2) return;
    int start = 1; // skip leading '/'
    int end = uri.indexOf('/', start);
    String firstSegment = (end < 0) ? uri.substring(start) : uri.substring(start, end);
    if (BACKEND_PREFIXES.contains(firstSegment)) {
      // Unmapped backend path — surface as 404, not the SPA HTML fallback.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No endpoint at " + uri);
    }
  }

  private ResponseEntity<Resource> serveIndex() {
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(INDEX_HTML);
  }
}
