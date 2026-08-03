package com.navneet.executespec.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.JwtUtil;
import com.navneet.executespec.auth.service.AuthService;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

class OAuthSuccessHandlerTest {

  @Test
  void redirectsToConfiguredAppReturnUrl_notMailOrBackendUrl() throws Exception {
    OAuthSuccessHandler handler = handler("access-token", "refresh-token");
    ReflectionTestUtils.setField(handler, "appReturnUrl", "http://localhost:5174/auth/callback");

    MockHttpServletResponse response = new MockHttpServletResponse();
    handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authToken());

    assertThat(response.getRedirectedUrl())
        .startsWith("http://localhost:5174/auth/callback?oauth=1");
    assertThat(response.getRedirectedUrl()).doesNotStartWith("http://localhost:8080");
    // RR-130 / RR-1334 — no credential may appear in the redirect URL.
    assertThat(response.getRedirectedUrl()).doesNotContain("access-token");
    assertThat(response.getRedirectedUrl()).doesNotContain("refresh-token");
    assertThat(response.getRedirectedUrl()).doesNotContain("token=");
    assertThat(response.getRedirectedUrl()).doesNotContain("refresh=");
    // ...and the refresh credential is delivered as an HttpOnly cookie instead, so OAuth sessions
    // can actually renew.
    assertThat(response.getHeaders("Set-Cookie"))
        .anySatisfy(
            cookie ->
                assertThat(cookie)
                    .contains("refresh_token=refresh-token")
                    .contains("Path=/api/auth")
                    .contains("HttpOnly")
                    .contains("SameSite=Lax"));
  }

  @Test
  void appendsSafeCliLoginRedirectFromCookieAndClearsCookie() throws Exception {
    OAuthSuccessHandler handler = handler("access-token", "refresh-token");
    ReflectionTestUtils.setField(handler, "appReturnUrl", "http://localhost:5174/auth/callback");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(
        new Cookie(
            OAuthRedirectCookieFilter.COOKIE,
            URLEncoder.encode("/cli-login?port=62601&state=abc", StandardCharsets.UTF_8)));
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, authToken());

    assertThat(response.getRedirectedUrl())
        .contains(
            "redirect="
                + URLEncoder.encode("/cli-login?port=62601&state=abc", StandardCharsets.UTF_8));
    assertThat(response.getHeaders("Set-Cookie"))
        .anySatisfy(
            cookie ->
                assertThat(cookie)
                    .contains(OAuthRedirectCookieFilter.COOKIE + "=")
                    .contains("Max-Age=0")
                    .contains("HttpOnly")
                    .contains("SameSite=Lax"));
  }

  @Test
  void filterAndSuccessHandlerRoundTripProtectedSettingsPathQueryAndHash() throws Exception {
    OAuthSuccessHandler handler = handler("access-token", "refresh-token");
    ReflectionTestUtils.setField(handler, "appReturnUrl", "http://localhost:5174/auth/callback");
    String target = "/settings?section=notifications#notification-preferences";
    MockHttpServletRequest authorizeRequest =
        new MockHttpServletRequest("GET", "/api/auth/oauth2/authorize/google");
    authorizeRequest.setParameter("redirect", target);
    MockHttpServletResponse authorizeResponse = new MockHttpServletResponse();
    new OAuthRedirectCookieFilter()
        .doFilter(authorizeRequest, authorizeResponse, new MockFilterChain());
    String setCookie = authorizeResponse.getHeader("Set-Cookie");
    assertThat(setCookie).isNotNull();
    String encodedCookieValue =
        setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(OAuthRedirectCookieFilter.COOKIE, encodedCookieValue));
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, authToken());

    assertThat(response.getRedirectedUrl())
        .isEqualTo(
            "http://localhost:5174/auth/callback?oauth=1&redirect="
                + URLEncoder.encode(target, StandardCharsets.UTF_8));
    assertThat(response.getHeaders("Set-Cookie"))
        .anySatisfy(cookie -> assertThat(cookie).contains("Max-Age=0"));
  }

  @Test
  void unsafeRedirectCookieIsClearedButNotForwarded() throws Exception {
    OAuthSuccessHandler handler = handler("access-token", "refresh-token");
    ReflectionTestUtils.setField(handler, "appReturnUrl", "http://localhost:5174/auth/callback");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(
        new Cookie(
            OAuthRedirectCookieFilter.COOKIE,
            URLEncoder.encode("https://evil.example/cli-login", StandardCharsets.UTF_8)));
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, authToken());

    assertThat(response.getRedirectedUrl()).doesNotContain("redirect=");
    assertThat(response.getHeaders("Set-Cookie"))
        .anySatisfy(cookie -> assertThat(cookie).contains("Max-Age=0"));
  }

  @Test
  void malformedRedirectCookieIsClearedWithoutFailingAuthentication() throws Exception {
    OAuthSuccessHandler handler = handler("access-token", "refresh-token");
    ReflectionTestUtils.setField(handler, "appReturnUrl", "http://localhost:5174/auth/callback");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(OAuthRedirectCookieFilter.COOKIE, "%not-hex"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, authToken());

    assertThat(response.getRedirectedUrl())
        .isEqualTo("http://localhost:5174/auth/callback?oauth=1");
    assertThat(response.getHeaders("Set-Cookie"))
        .anySatisfy(cookie -> assertThat(cookie).contains("Max-Age=0"));
  }

  @Test
  void noEmailFailurePreservesSafeProtectedRedirect() throws Exception {
    OAuthSuccessHandler handler =
        handler(
            "access-token",
            "refresh-token",
            new OAuthProfile("google", "user-sub", null, "OAuth User", null, false),
            null);
    ReflectionTestUtils.setField(handler, "appReturnUrl", "http://localhost:5174/auth/callback");
    MockHttpServletRequest request = requestWithRedirect("/projects/p-1/runs/r-1?tab=code#file");
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, authToken());

    assertFailureRedirect(response, "oauth_no_email", "/projects/p-1/runs/r-1?tab=code#file");
  }

  @Test
  void emailConflictFailurePreservesSafeProtectedRedirect() throws Exception {
    OAuthSuccessHandler handler =
        handler(
            "access-token",
            "refresh-token",
            defaultProfile(),
            new OAuthEmailConflictException("account already exists"));
    ReflectionTestUtils.setField(handler, "appReturnUrl", "http://localhost:5174/auth/callback");
    MockHttpServletRequest request = requestWithRedirect("/settings?section=notifications");
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, authToken());

    assertFailureRedirect(response, "oauth_email_conflict", "/settings?section=notifications");
  }

  @Test
  void genericFailurePreservesSafeProtectedRedirect() throws Exception {
    OAuthSuccessHandler handler =
        handler(
            "access-token",
            "refresh-token",
            defaultProfile(),
            new IllegalStateException("identity service unavailable"));
    ReflectionTestUtils.setField(handler, "appReturnUrl", "http://localhost:5174/auth/callback");
    MockHttpServletRequest request = requestWithRedirect("/runs/r-1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, authToken());

    assertFailureRedirect(response, "oauth_failed", "/runs/r-1");
  }

  @Test
  void failureClearsButNeverForwardsUnsafeRedirect() throws Exception {
    OAuthSuccessHandler handler =
        handler(
            "access-token",
            "refresh-token",
            new OAuthProfile("google", "user-sub", null, "OAuth User", null, false),
            null);
    ReflectionTestUtils.setField(handler, "appReturnUrl", "http://localhost:5174/auth/callback");
    MockHttpServletRequest request = requestWithRedirect("https://evil.example/runs/r-1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, authToken());

    assertThat(response.getRedirectedUrl())
        .isEqualTo("http://localhost:5174/login?error=oauth_no_email");
    assertThat(response.getHeaders("Set-Cookie"))
        .anySatisfy(cookie -> assertThat(cookie).contains("Max-Age=0"));
  }

  private OAuthSuccessHandler handler(String accessToken, String refreshToken) {
    return handler(accessToken, refreshToken, defaultProfile(), null);
  }

  private OAuthSuccessHandler handler(
      String accessToken,
      String refreshToken,
      OAuthProfile profile,
      RuntimeException accountResolutionFailure) {
    OAuthUserMapper mapper = mock(OAuthUserMapper.class);
    AuthService authService = mock(AuthService.class);
    JwtUtil jwtUtil = mock(JwtUtil.class);
    OAuth2AuthorizedClientService authorizedClientService =
        mock(OAuth2AuthorizedClientService.class);

    when(authorizedClientService.loadAuthorizedClient(eq("google"), eq("user-sub")))
        .thenReturn(null);
    when(mapper.toProfile(eq("google"), any(), any())).thenReturn(profile);
    var accountResolution =
        when(
            authService.findOrCreateFromOAuth(
                "google", "user-sub", "oauth-user@test.local", "OAuth User", null, true));
    if (accountResolutionFailure == null) {
      accountResolution.thenReturn(
          AppUser.builder()
              .id("user-1")
              .email("oauth-user@test.local")
              .firstName("OAuth")
              .lastName("User")
              .globalRole("USER")
              .tier("PRO")
              .status("ACTIVE")
              .build());
    } else {
      accountResolution.thenThrow(accountResolutionFailure);
    }
    when(jwtUtil.generateAccessToken(any(AppUser.class), any())).thenReturn(accessToken);
    when(jwtUtil.generateRefreshToken(any(AppUser.class))).thenReturn(refreshToken);

    return new OAuthSuccessHandler(mapper, authService, jwtUtil, authorizedClientService);
  }

  private OAuthProfile defaultProfile() {
    return new OAuthProfile(
        "google", "user-sub", "oauth-user@test.local", "OAuth User", null, true);
  }

  private MockHttpServletRequest requestWithRedirect(String redirect) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(
        new Cookie(
            OAuthRedirectCookieFilter.COOKIE, URLEncoder.encode(redirect, StandardCharsets.UTF_8)));
    return request;
  }

  private void assertFailureRedirect(
      MockHttpServletResponse response, String error, String redirect) {
    assertThat(response.getRedirectedUrl())
        .isEqualTo(
            "http://localhost:5174/login?error="
                + error
                + "&redirect="
                + URLEncoder.encode(redirect, StandardCharsets.UTF_8));
    assertThat(response.getHeaders("Set-Cookie"))
        .anySatisfy(cookie -> assertThat(cookie).contains("Max-Age=0"));
  }

  private OAuth2AuthenticationToken authToken() {
    DefaultOAuth2User principal =
        new DefaultOAuth2User(
            List.of(new SimpleGrantedAuthority("ROLE_USER")),
            Map.of("sub", "user-sub", "email", "oauth-user@test.local"),
            "sub");
    return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
  }
}
