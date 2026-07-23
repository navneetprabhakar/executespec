package com.navneet.executespec.testgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** RR-1588 — model-authored route literals must not steer an outbound request off the preview. */
class ApiProberPathAdmissionTest {

  private static final URI BASE = URI.create("http://preview-abc.run.app/");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "@evil.example/x",
        "//evil.example/x",
        "https://evil.example",
        "http://evil.example/x",
        "/\\evil.example/x",
        "api/tasks",
        "javascript:alert(1)",
        "/path with space",
        "/path\nX-Injected: 1"
      })
  void hostileRouteLiteralsAreRefused(String literal) {
    assertFalse(ApiProber.isProbablePath(literal), literal);
    assertNull(ApiProber.resolveOnHost(BASE, literal), literal);
  }

  @Test
  void blankAndNullAreRefused() {
    assertFalse(ApiProber.isProbablePath(null));
    assertFalse(ApiProber.isProbablePath(""));
    assertFalse(ApiProber.isProbablePath("   "));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/", "/api/health", "/api/tasks/{id}", "/api/v1/users?q=1"})
  void ordinaryRoutesStayProbable(String literal) {
    assertTrue(ApiProber.isProbablePath(literal), literal);
    assertNotNull(ApiProber.resolveOnHost(BASE, literal), literal);
  }

  @Test
  void resolvedProbeKeepsThePreviewHostAndPort() {
    URI resolved = ApiProber.resolveOnHost(URI.create("http://127.0.0.1:8080/"), "/api/tasks");
    assertNotNull(resolved);
    assertEquals("127.0.0.1", resolved.getHost());
    assertEquals(8080, resolved.getPort());
    assertEquals("/api/tasks", resolved.getPath());
  }

  @Test
  void traversalCannotEscapeTheHost() {
    URI resolved = ApiProber.resolveOnHost(BASE, "/../../etc/passwd");
    // Normalisation keeps it on-host or drops it; it must never point somewhere else.
    if (resolved != null) {
      assertEquals(BASE.getHost(), resolved.getHost());
    }
  }
}
