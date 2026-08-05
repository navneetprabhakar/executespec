package com.navneet.executespec.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit test for {@link OAuthRedirectCookieFilter}. No Spring context: pure servlet mocks, so it
 * runs in milliseconds.
 */
class OAuthRedirectCookieFilterTest {

  private final OAuthRedirectCookieFilter filter = new OAuthRedirectCookieFilter();

  @Test
  void isSafe_acceptsStrictSameOriginPathsWithQueryAndHash() {
    assertThat(OAuthRedirectCookieFilter.isSafe("/cli-login?port=1&state=x")).isTrue();
    assertThat(
            OAuthRedirectCookieFilter.isSafe(
                "/settings?section=notifications#notification-preferences"))
        .isTrue();
    assertThat(OAuthRedirectCookieFilter.isSafe("/projects/p-1/runs/r-1")).isTrue();
    assertThat(OAuthRedirectCookieFilter.isSafe(null)).isFalse();
    assertThat(OAuthRedirectCookieFilter.isSafe("home")).isFalse();
    assertThat(OAuthRedirectCookieFilter.isSafe("https://evil.com")).isFalse();
    assertThat(OAuthRedirectCookieFilter.isSafe("//evil.com/cli-login")).isFalse();
    assertThat(OAuthRedirectCookieFilter.isSafe("/%2f%2fevil.com/path")).isFalse();
    assertThat(OAuthRedirectCookieFilter.isSafe("/\\evil.com/path")).isFalse();
    assertThat(OAuthRedirectCookieFilter.isSafe("/settings\r\nSet-Cookie: attacked=1")).isFalse();
  }

  @Test
  void setsCookie_onAuthorizeWithSafeRedirect() throws Exception {
    MockHttpServletRequest req =
        new MockHttpServletRequest("GET", "/api/auth/oauth2/authorize/google");
    req.setParameter("redirect", "/cli-login?port=62601&state=abc");
    MockHttpServletResponse res = new MockHttpServletResponse();

    filter.doFilter(req, res, new MockFilterChain());

    String setCookie = res.getHeader("Set-Cookie");
    assertThat(setCookie).isNotNull();
    assertThat(setCookie).contains("es_oauth_redirect=");
    assertThat(setCookie).contains("HttpOnly");
    assertThat(setCookie).contains("SameSite=Lax");
    assertThat(setCookie).contains("Max-Age=300");
    assertThat(setCookie).contains("%2Fcli-login"); // URL-encoded path
    assertThat(setCookie).doesNotContain("Secure"); // http request → no Secure flag
  }

  @Test
  void clearsStaleCookie_onUnsafeRedirect() throws Exception {
    MockHttpServletRequest req =
        new MockHttpServletRequest("GET", "/api/auth/oauth2/authorize/google");
    req.setParameter("redirect", "https://evil.com");
    MockHttpServletResponse res = new MockHttpServletResponse();

    filter.doFilter(req, res, new MockFilterChain());

    assertThat(res.getHeader("Set-Cookie"))
        .contains(OAuthRedirectCookieFilter.COOKIE + "=")
        .contains("Max-Age=0")
        .doesNotContain("evil.com");
  }

  @Test
  void preservesProtectedSettingsPathQueryAndHash() throws Exception {
    MockHttpServletRequest req =
        new MockHttpServletRequest("GET", "/api/auth/oauth2/authorize/google");
    req.setParameter("redirect", "/settings?section=notifications#notification-preferences");
    MockHttpServletResponse res = new MockHttpServletResponse();

    filter.doFilter(req, res, new MockFilterChain());

    assertThat(res.getHeader("Set-Cookie"))
        .contains("%2Fsettings%3Fsection%3Dnotifications%23notification-preferences")
        .contains("HttpOnly")
        .contains("SameSite=Lax");
  }

  @Test
  void noCookie_onNonAuthorizePath() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/runs");
    req.setParameter("redirect", "/cli-login?port=1");
    MockHttpServletResponse res = new MockHttpServletResponse();

    filter.doFilter(req, res, new MockFilterChain());

    assertThat(res.getHeader("Set-Cookie")).isNull();
  }

  @Test
  void secureFlag_addedOverHttps() throws Exception {
    MockHttpServletRequest req =
        new MockHttpServletRequest("GET", "/api/auth/oauth2/authorize/github");
    req.setParameter("redirect", "/cli-login?port=1&state=x");
    req.setSecure(true);
    MockHttpServletResponse res = new MockHttpServletResponse();

    filter.doFilter(req, res, new MockFilterChain());

    assertThat(res.getHeader("Set-Cookie")).contains("Secure");
  }
}
