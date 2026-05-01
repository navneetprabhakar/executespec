package com.navneet.executespec.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Regression test for the 2026-04-27 Google-login break.
 *
 * <p>The bug: {@link OAuthInitController#init} redirected to {@code
 * /oauth2/authorization/{provider}} (Spring Security's default authorization endpoint base URI).
 * But {@code SecurityConfig} remaps that base URI to {@code /api/auth/oauth2/authorize}, so
 * requests to the default path fell through to the SPA index.html and the OAuth dance never started
 * — Google sign-in was visibly broken on dev.executespec.ai.
 *
 * <p>This test pins the redirect target to the path that {@code
 * SecurityConfig.authorizationEndpoint().baseUri(...)} actually registers, so future drift between
 * the two surfaces fails the build.
 */
class OAuthInitControllerTest {

  private final OAuthSurfaceResolver resolver =
      new OAuthSurfaceResolver(
          "https://app.executespec.ai",
          "https://build.executespec.ai",
          "https://app.executespec.ai/auth/callback",
          "https://build.executespec.ai/auth/callback");
  private final OAuthInitController controller = new OAuthInitController(resolver);

  @Test
  void googleProviderRedirectsToRemappedSpringOAuthPath() {
    HttpServletRequest req = mockRequest();
    RedirectView view = controller.init("google", "executespec", req);
    assertThat(view.getUrl())
        .as("Must match SecurityConfig.authorizationEndpoint().baseUri override")
        .isEqualTo("/api/auth/oauth2/authorize/google");
  }

  @Test
  void githubProviderRedirectsToRemappedSpringOAuthPath() {
    HttpServletRequest req = mockRequest();
    RedirectView view = controller.init("github", "vibe", req);
    assertThat(view.getUrl()).isEqualTo("/api/auth/oauth2/authorize/github");
  }

  @Test
  void unknownProviderFallsBackToGoogle() {
    HttpServletRequest req = mockRequest();
    RedirectView view = controller.init("malicious", "executespec", req);
    assertThat(view.getUrl()).isEqualTo("/api/auth/oauth2/authorize/google");
  }

  @Test
  void nullProviderFallsBackToGoogle() {
    HttpServletRequest req = mockRequest();
    RedirectView view = controller.init(null, null, req);
    assertThat(view.getUrl()).isEqualTo("/api/auth/oauth2/authorize/google");
  }

  @Test
  void redirectIsContextRelativeSoSchemeIsPreservedBehindLoadBalancer() {
    // setContextRelative(true) causes RedirectView to issue a path-only
    // Location header (no scheme/host). This is critical when the GCE LB
    // terminates TLS — without it the JVM sees the request as http and
    // builds an absolute http://… URL that the browser then either
    // downgrades or breaks.
    HttpServletRequest req = mockRequest();
    RedirectView view = controller.init("google", null, req);
    Boolean ctxRelative = (Boolean) ReflectionTestUtils.getField(view, "contextRelative");
    assertThat(ctxRelative).as("contextRelative private field").isTrue();
  }

  private HttpServletRequest mockRequest() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(req.getSession()).thenReturn(session);
    return req;
  }
}
