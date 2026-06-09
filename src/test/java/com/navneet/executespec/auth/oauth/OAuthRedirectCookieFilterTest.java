package com.navneet.executespec.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit test for {@link OAuthRedirectCookieFilter} — the filter that preserves the CLI / VS Code
 * `/cli-login` device-auth return URL across the OAuth round-trip (RL: CLI OAuth onboarding fix).
 * No Spring context: pure servlet mocks, so it runs in milliseconds.
 */
class OAuthRedirectCookieFilterTest {

  private final OAuthRedirectCookieFilter filter = new OAuthRedirectCookieFilter();

  @Test
  void isSafe_acceptsOnlyCliLoginPaths() {
    assertThat(OAuthRedirectCookieFilter.isSafe("/cli-login?port=1&state=x")).isTrue();
    assertThat(OAuthRedirectCookieFilter.isSafe("/cli-login")).isTrue();
    assertThat(OAuthRedirectCookieFilter.isSafe(null)).isFalse();
    assertThat(OAuthRedirectCookieFilter.isSafe("/home")).isFalse();
    assertThat(OAuthRedirectCookieFilter.isSafe("https://evil.com")).isFalse();
    assertThat(OAuthRedirectCookieFilter.isSafe("//evil.com/cli-login")).isFalse();
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
  void noCookie_onUnsafeRedirect() throws Exception {
    MockHttpServletRequest req =
        new MockHttpServletRequest("GET", "/api/auth/oauth2/authorize/google");
    req.setParameter("redirect", "https://evil.com");
    MockHttpServletResponse res = new MockHttpServletResponse();

    filter.doFilter(req, res, new MockFilterChain());

    assertThat(res.getHeader("Set-Cookie")).isNull();
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
