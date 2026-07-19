package com.navneet.executespec.previewrender;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.security.GeneratedSecretScanner;

import org.junit.jupiter.api.Test;

/**
 * The guard is the second containment boundary for model-authored HTML that executes in an
 * operator's browser. These tests pin the fail-closed behaviour: anything that could reach the
 * network, the embedding page, or persistent storage must be rejected.
 */
class PreviewRenderGuardTest {

  private final PreviewRenderGuard guard = new PreviewRenderGuard(new GeneratedSecretScanner());

  private static final String VALID =
      "<html><head><style>body{margin:0;font-family:sans-serif}</style></head><body><div"
          + " class='card'><h1>Orders</h1><p>Aisha Raman — $42.10</p></div></body></html>";

  @Test
  void admitsSelfContainedOfflineDocument() {
    PreviewRenderGuard.Verdict verdict = guard.admit(VALID);
    assertThat(verdict.admitted()).isTrue();
    assertThat(verdict.reason()).isNull();
  }

  @Test
  void admitsInlineSvgWithNamespaceDeclaration() {
    // The SVG xmlns is an inert identifier, not a fetch. Rejecting it would make every icon
    // impossible to render.
    String svg =
        "<html><body><svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\">"
            + "<circle cx=\"12\" cy=\"12\" r=\"10\"/></svg></body></html>";
    assertThat(guard.admit(svg).admitted()).isTrue();
  }

  @Test
  void rejectsExternalStylesheet() {
    String html =
        "<html><head><link rel='stylesheet' href='https://cdn.tailwindcss.com/x.css'>"
            + "</head><body><div>hi</div></body></html>";
    PreviewRenderGuard.Verdict verdict = guard.admit(html);
    assertThat(verdict.admitted()).isFalse();
    assertThat(verdict.reason()).isEqualTo("EXTERNAL_NETWORK_REFERENCE");
  }

  @Test
  void rejectsProtocolRelativeUrl() {
    String html = "<html><body><img src='//evil.example.com/pixel.png'><div>x</div></body></html>";
    assertThat(guard.admit(html).reason()).isEqualTo("EXTERNAL_NETWORK_REFERENCE");
  }

  @Test
  void rejectsFetchCall() {
    String html =
        "<html><body><div>x</div><script>fetch('/api/data').then(r=>r.json())</script></body></html>";
    assertThat(guard.admit(html).reason()).isEqualTo("FORBIDDEN_API:FETCH");
  }

  @Test
  void rejectsFrameEscapeAttempt() {
    String html =
        "<html><body><div>x</div><script>window.parent.postMessage('x','*')</script></body></html>";
    assertThat(guard.admit(html).reason()).isEqualTo("FORBIDDEN_API:FRAME_ESCAPE");
  }

  @Test
  void rejectsStorageAccess() {
    String html =
        "<html><body><div>x</div><script>var"
            + " t=localStorage.getItem('token')</script></body></html>";
    assertThat(guard.admit(html).reason()).isEqualTo("FORBIDDEN_API:STORAGE_ACCESS");
  }

  @Test
  void rejectsWebSocket() {
    String html = "<html><body><div>x</div><script>new WebSocket('x')</script></body></html>";
    assertThat(guard.admit(html).reason()).isEqualTo("FORBIDDEN_API:WEBSOCKET");
  }

  @Test
  void rejectsEmbeddedCredentialWithoutDisclosingIt() {
    String secret = "sk-ant-api03-" + "A".repeat(80);
    String html = "<html><body><div>key " + secret + "</div></body></html>";

    PreviewRenderGuard.Verdict verdict = guard.admit(html);

    assertThat(verdict.admitted()).isFalse();
    assertThat(verdict.reason()).startsWith("CREDENTIAL_IN_BUNDLE:");
    // The reason travels into logs and API responses, so it must never carry the matched value.
    assertThat(verdict.reason()).doesNotContain(secret);
  }

  @Test
  void rejectsOversizedBundle() {
    String html =
        "<html><body><div>"
            + "x".repeat(PreviewRenderGuard.MAX_BUNDLE_BYTES)
            + "</div></body></html>";
    assertThat(guard.admit(html).reason()).isEqualTo("BUNDLE_TOO_LARGE");
  }

  @Test
  void rejectsEmptyAndNonHtml() {
    assertThat(guard.admit(null).reason()).isEqualTo("EMPTY_BUNDLE");
    assertThat(guard.admit("   ").reason()).isEqualTo("EMPTY_BUNDLE");
    assertThat(guard.admit("I could not render this screen.").reason()).isEqualTo("NOT_HTML");
  }

  @Test
  void cspDeniesEveryFetchDestinationAndRestrictsEmbedding() {
    String csp = guard.contentSecurityPolicy();
    assertThat(csp).contains("default-src 'none'");
    assertThat(csp).contains("connect-src 'none'");
    assertThat(csp).contains("form-action 'none'");
    assertThat(csp).contains("frame-ancestors 'self'");
    // Inline style and script are the point of a self-contained bundle and must stay allowed.
    assertThat(csp).contains("style-src 'unsafe-inline'");
    assertThat(csp).contains("script-src 'unsafe-inline'");
  }
}
