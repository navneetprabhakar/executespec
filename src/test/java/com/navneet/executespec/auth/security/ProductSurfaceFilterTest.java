package com.navneet.executespec.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.Test;

class ProductSurfaceFilterTest {

  private final ProductSurfaceFilter filter =
      new ProductSurfaceFilter("https://app.executespec.ai", "https://build.executespec.ai");

  @Test
  void xProductHeaderWinsOverEverything() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("X-Product")).thenReturn("vibe");
    when(req.getHeader("Origin")).thenReturn("https://build.executespec.ai");
    assertThat(filter.resolveSurface(req)).isEqualTo("vibe");
  }

  @Test
  void originHeaderMapsToSurface() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("Origin")).thenReturn("https://app.executespec.ai/some/path");
    assertThat(filter.resolveSurface(req)).isEqualTo("vibe");

    HttpServletRequest req2 = mock(HttpServletRequest.class);
    when(req2.getHeader("Origin")).thenReturn("https://build.executespec.ai");
    assertThat(filter.resolveSurface(req2)).isEqualTo("executespec");
  }

  @Test
  void refererFallbackWhenOriginAbsent() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("Origin")).thenReturn(null);
    when(req.getHeader("Referer")).thenReturn("https://app.executespec.ai/projects/123");
    assertThat(filter.resolveSurface(req)).isEqualTo("vibe");
  }

  @Test
  void sessionAttributeFallsThroughWhenNoOrigin() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpSession sess = mock(HttpSession.class);
    when(req.getSession(false)).thenReturn(sess);
    when(sess.getAttribute("oauth.surface")).thenReturn("vibe");
    assertThat(filter.resolveSurface(req)).isEqualTo("vibe");
  }

  @Test
  void defaultsToExecutespecWhenAllSignalsAbsent() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    assertThat(filter.resolveSurface(req)).isEqualTo("executespec");
  }

  @Test
  void unknownXProductValueFallsThrough() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("X-Product")).thenReturn("garbage");
    when(req.getHeader("Origin")).thenReturn("https://app.executespec.ai");
    assertThat(filter.resolveSurface(req)).isEqualTo("vibe");
  }

  @Test
  void unrecognizedOriginFallsThrough() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("Origin")).thenReturn("https://random.example.com");
    assertThat(filter.resolveSurface(req)).isEqualTo("executespec");
  }
}
