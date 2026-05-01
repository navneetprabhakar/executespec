package com.navneet.executespec.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for the 2026-05-01 Google-login break.
 *
 * <p>Bug: {@link OAuthSurfaceResolver#captureFromRequest} unconditionally stashed {@code
 * SURFACE_EXECUTESPEC} in the session whenever neither the explicit {@code surface} param nor
 * Origin/Referer matched a configured surface. Local dev frontends running on {@code
 * http://localhost:5173} never match either surface origin, so every Google login bounced through
 * to {@code https://build.executespec.ai/auth/callback} after success instead of falling back to
 * {@code dashboardUrl}.
 *
 * <p>Fix: only stash on positive match; return {@code null} otherwise so {@link
 * OAuthSuccessHandler} can fall back to {@code dashboardUrl}.
 */
class OAuthSurfaceResolverTest {

  private final OAuthSurfaceResolver resolver =
      new OAuthSurfaceResolver(
          "https://app.executespec.ai",
          "https://build.executespec.ai",
          "https://app.executespec.ai/auth/callback",
          "https://build.executespec.ai/auth/callback");

  @Test
  void localhostOriginReturnsNullAndDoesNotStash() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(req.getHeader("Origin")).thenReturn("http://localhost:5173");
    when(req.getSession(false)).thenReturn(session);

    String surface = resolver.captureFromRequest(req);

    assertThat(surface).isNull();
    verify(session, never()).setAttribute(eq(OAuthSurfaceResolver.SESSION_ATTR), any());
  }

  @Test
  void localhostOriginClearsStaleStash() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(req.getHeader("Origin")).thenReturn("http://localhost:5173");
    when(req.getSession(false)).thenReturn(session);

    resolver.captureFromRequest(req);

    verify(session, times(1)).removeAttribute(OAuthSurfaceResolver.SESSION_ATTR);
  }

  @Test
  void explicitSurfaceParamStashes() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(req.getParameter("surface")).thenReturn("vibe");
    when(req.getSession()).thenReturn(session);

    String surface = resolver.captureFromRequest(req);

    assertThat(surface).isEqualTo("vibe");
    verify(session).setAttribute(OAuthSurfaceResolver.SESSION_ATTR, "vibe");
  }

  @Test
  void buildOriginStashesExecutespec() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(req.getHeader("Origin")).thenReturn("https://build.executespec.ai/login");
    when(req.getSession()).thenReturn(session);

    String surface = resolver.captureFromRequest(req);

    assertThat(surface).isEqualTo("executespec");
    verify(session).setAttribute(OAuthSurfaceResolver.SESSION_ATTR, "executespec");
  }

  @Test
  void appOriginStashesVibe() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(req.getHeader("Origin")).thenReturn("https://app.executespec.ai/");
    when(req.getSession()).thenReturn(session);

    String surface = resolver.captureFromRequest(req);

    assertThat(surface).isEqualTo("vibe");
    verify(session).setAttribute(OAuthSurfaceResolver.SESSION_ATTR, "vibe");
  }

  @Test
  void resolveReturnUrlReturnsNullWhenNoSession() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getSession(false)).thenReturn(null);

    assertThat(resolver.resolveReturnUrl(req)).isNull();
  }

  @Test
  void resolveReturnUrlReturnsNullWhenNoStash() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(req.getSession(false)).thenReturn(session);
    when(session.getAttribute(OAuthSurfaceResolver.SESSION_ATTR)).thenReturn(null);

    assertThat(resolver.resolveReturnUrl(req)).isNull();
  }

  @Test
  void resolveReturnUrlMapsExecutespecToBuild() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(req.getSession(false)).thenReturn(session);
    when(session.getAttribute(OAuthSurfaceResolver.SESSION_ATTR)).thenReturn("executespec");

    assertThat(resolver.resolveReturnUrl(req))
        .isEqualTo("https://build.executespec.ai/auth/callback");
  }

  @Test
  void resolveReturnUrlMapsVibeToApp() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(req.getSession(false)).thenReturn(session);
    when(session.getAttribute(OAuthSurfaceResolver.SESSION_ATTR)).thenReturn("vibe");

    assertThat(resolver.resolveReturnUrl(req))
        .isEqualTo("https://app.executespec.ai/auth/callback");
  }
}
