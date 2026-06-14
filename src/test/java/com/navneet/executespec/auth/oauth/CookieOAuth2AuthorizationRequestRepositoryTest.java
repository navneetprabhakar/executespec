package com.navneet.executespec.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class CookieOAuth2AuthorizationRequestRepositoryTest {

  private final CookieOAuth2AuthorizationRequestRepository repository =
      new CookieOAuth2AuthorizationRequestRepository("test-secret-with-enough-entropy");

  @Test
  void saveAndLoad_roundTripsAuthorizationRequestByStateCookie() {
    OAuth2AuthorizationRequest authorizationRequest = authorizationRequest("state-123");
    MockHttpServletRequest saveRequest = new MockHttpServletRequest();
    saveRequest.setSecure(true);
    MockHttpServletResponse saveResponse = new MockHttpServletResponse();

    repository.saveAuthorizationRequest(authorizationRequest, saveRequest, saveResponse);

    String setCookie = saveResponse.getHeader("Set-Cookie");
    assertThat(setCookie).contains("HttpOnly");
    assertThat(setCookie).contains("Secure");
    assertThat(setCookie).contains("SameSite=Lax");
    assertThat(setCookie).contains("Path=/api/auth/oauth2/callback");
    assertThat(setCookie).contains("Max-Age=300");

    MockHttpServletRequest callbackRequest = new MockHttpServletRequest();
    callbackRequest.setParameter("state", "state-123");
    callbackRequest.setCookies(cookieFrom(setCookie));

    OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(callbackRequest);

    assertThat(loaded).isNotNull();
    assertThat(loaded.getState()).isEqualTo("state-123");
    assertThat(loaded.getClientId()).isEqualTo("client-id");
    assertThat(loaded.getRedirectUri())
        .isEqualTo("https://dev.executespec.ai/api/auth/oauth2/callback/google");
  }

  @Test
  void load_rejectsTamperedCookie() {
    OAuth2AuthorizationRequest authorizationRequest = authorizationRequest("state-123");
    MockHttpServletResponse saveResponse = new MockHttpServletResponse();
    repository.saveAuthorizationRequest(
        authorizationRequest, new MockHttpServletRequest(), saveResponse);
    Cookie cookie = cookieFrom(saveResponse.getHeader("Set-Cookie"));
    cookie.setValue(cookie.getValue().replaceFirst(".$", "x"));

    MockHttpServletRequest callbackRequest = new MockHttpServletRequest();
    callbackRequest.setParameter("state", "state-123");
    callbackRequest.setCookies(cookie);

    assertThat(repository.loadAuthorizationRequest(callbackRequest)).isNull();
  }

  @Test
  void load_returnsNullWhenCallbackStateDoesNotMatchCookieName() {
    OAuth2AuthorizationRequest authorizationRequest = authorizationRequest("state-123");
    MockHttpServletResponse saveResponse = new MockHttpServletResponse();
    repository.saveAuthorizationRequest(
        authorizationRequest, new MockHttpServletRequest(), saveResponse);

    MockHttpServletRequest callbackRequest = new MockHttpServletRequest();
    callbackRequest.setParameter("state", "different-state");
    callbackRequest.setCookies(cookieFrom(saveResponse.getHeader("Set-Cookie")));

    assertThat(repository.loadAuthorizationRequest(callbackRequest)).isNull();
  }

  @Test
  void remove_loadsThenClearsStateCookie() {
    OAuth2AuthorizationRequest authorizationRequest = authorizationRequest("state-123");
    MockHttpServletResponse saveResponse = new MockHttpServletResponse();
    repository.saveAuthorizationRequest(
        authorizationRequest, new MockHttpServletRequest(), saveResponse);

    MockHttpServletRequest callbackRequest = new MockHttpServletRequest();
    callbackRequest.setParameter("state", "state-123");
    callbackRequest.setCookies(cookieFrom(saveResponse.getHeader("Set-Cookie")));
    MockHttpServletResponse callbackResponse = new MockHttpServletResponse();

    OAuth2AuthorizationRequest removed =
        repository.removeAuthorizationRequest(callbackRequest, callbackResponse);

    assertThat(removed).isNotNull();
    assertThat(removed.getState()).isEqualTo("state-123");
    assertThat(callbackResponse.getHeader("Set-Cookie")).contains("Max-Age=0");
  }

  private static OAuth2AuthorizationRequest authorizationRequest(String state) {
    return OAuth2AuthorizationRequest.authorizationCode()
        .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
        .authorizationRequestUri("https://accounts.google.com/o/oauth2/v2/auth?state=" + state)
        .clientId("client-id")
        .redirectUri("https://dev.executespec.ai/api/auth/oauth2/callback/google")
        .scope("openid", "email", "profile")
        .state(state)
        .build();
  }

  private static Cookie cookieFrom(String setCookie) {
    String pair = setCookie.substring(0, setCookie.indexOf(';'));
    int equals = pair.indexOf('=');
    return new Cookie(pair.substring(0, equals), pair.substring(equals + 1));
  }
}
