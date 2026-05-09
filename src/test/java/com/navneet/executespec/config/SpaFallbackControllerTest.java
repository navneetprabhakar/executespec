package com.navneet.executespec.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pins the SPA fallback controller's depth coverage. RCA on the 2026-05-09 user-reported console
 * error ({@code GET /projects/<id>/runs/<id> 404 Not Found}): the controller was capped at 3 levels
 * but the v3 SPA's deepest route ({@code /projects/:projectId/runs/:runShort}) is 4 segments. Hard
 * reload on that URL fell through to Spring's default 404 handler instead of being rewritten to
 * {@code index.html} for React Router. This test pins each depth from 1 to 5 + the backend-prefix
 * guard so any future regression on the depth or the prefix list fails the build.
 */
class SpaFallbackControllerTest {

  private final SpaFallbackController controller = new SpaFallbackController();

  @Test
  void root_serves_index() {
    ResponseEntity<Resource> resp = controller.root();
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).isNotNull();
  }

  @Test
  void level1_serves_index() {
    ResponseEntity<Resource> resp = controller.spaLevel1(req("/dashboard"));
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void level2_serves_index() {
    ResponseEntity<Resource> resp = controller.spaLevel2(req("/runs/123"));
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void level3_serves_index() {
    ResponseEntity<Resource> resp = controller.spaLevel3(req("/org/projects/abc"));
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  /**
   * The actual regression case from 2026-05-09. /projects/&lt;projectId&gt;/runs/&lt;runId&gt; is
   * the canonical v3 nested-route shape and was 404'ing pre-fix.
   */
  @Test
  void level4_serves_index_for_projects_id_runs_id() {
    ResponseEntity<Resource> resp =
        controller.spaLevel4(
            req(
                "/projects/a092ffaa-221e-44f2-b035-77fe219bd6d7/runs/4421cc6e-f3b6-46c3-b496-819452911260"));
    assertThat(resp.getStatusCode())
        .as(
            "Hard-reload on /projects/<id>/runs/<id> must rewrite to index.html so React Router can"
                + " pick it up.")
        .isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).isNotNull();
  }

  @Test
  void level5_serves_index_for_future_deep_routes() {
    ResponseEntity<Resource> resp = controller.spaLevel5(req("/org/projects/abc/runs/def/tab"));
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  /**
   * Backend prefixes (api / actuator / webhooks) MUST NOT be shadowed by the SPA fallback. An
   * unmapped /api/admin/foo or /actuator/env (when disabled) should surface a real 404, not return
   * the SPA HTML with HTTP 200 (closes adversarial-test-report-2026-04-18 Finding 3).
   */
  @Test
  void backend_prefix_at_level4_throws_404_not_index() {
    org.junit.jupiter.api.Assertions.assertThrows(
        ResponseStatusException.class, () -> controller.spaLevel4(req("/api/admin/runs/halt-all")));
    org.junit.jupiter.api.Assertions.assertThrows(
        ResponseStatusException.class,
        () -> controller.spaLevel4(req("/actuator/env/spring.profiles.active")));
    org.junit.jupiter.api.Assertions.assertThrows(
        ResponseStatusException.class,
        () -> controller.spaLevel4(req("/webhooks/github/install/abc/token")));
  }

  @Test
  void backend_prefix_at_level5_throws_404_not_index() {
    org.junit.jupiter.api.Assertions.assertThrows(
        ResponseStatusException.class,
        () -> controller.spaLevel5(req("/api/admin/runs/123/abort/now")));
  }

  /**
   * 2026-05-09 — favicon.ico fallback. Browsers (esp. Chromium) issue a hard-coded GET to
   * /favicon.ico on every page load even when index.html declares {@code <link rel="icon"
   * href="/logo.png">}. Pre-fix: that 404'd in the user's dev console. Post-fix: 301 → /logo.png so
   * the same asset serves both.
   */
  @Test
  void favicon_redirects_to_logo_png() {
    ResponseEntity<Void> resp = controller.favicon();
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.MOVED_PERMANENTLY);
    assertThat(resp.getHeaders().getFirst("Location")).isEqualTo("/logo.png");
  }

  private static MockHttpServletRequest req(String uri) {
    MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
    r.setRequestURI(uri);
    return r;
  }
}
