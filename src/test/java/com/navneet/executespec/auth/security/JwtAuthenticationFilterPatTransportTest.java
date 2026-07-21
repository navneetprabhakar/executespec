package com.navneet.executespec.auth.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navneet.executespec.auth.service.PersonalAccessTokenService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterPatTransportTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void patInQueryParameterIsNeverResolved() throws Exception {
    PersonalAccessTokenService pats = mock(PersonalAccessTokenService.class);
    JwtAuthenticationFilter filter =
        new JwtAuthenticationFilter(mock(JwtUtil.class), mock(UserDetailsServiceImpl.class), pats);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/runs");
    request.setParameter("token", "es_pat_query_secret_that_must_not_be_logged");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    verify(pats, never()).resolveUserId(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void patInAuthorizationHeaderStillUsesPatResolver() throws Exception {
    PersonalAccessTokenService pats = mock(PersonalAccessTokenService.class);
    String pat = "es_pat_header_only_secret";
    when(pats.resolveToken(pat)).thenReturn(null);
    JwtAuthenticationFilter filter =
        new JwtAuthenticationFilter(mock(JwtUtil.class), mock(UserDetailsServiceImpl.class), pats);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/runs");
    request.addHeader("Authorization", "Bearer " + pat);

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    verify(pats).resolveToken(pat);
  }

  @Test
  void patScopeDenialStopsTheFilterChainBeforeController() throws Exception {
    PersonalAccessTokenService pats = mock(PersonalAccessTokenService.class);
    String pat = "es_pat_read_only";
    when(pats.resolveToken(pat))
        .thenReturn(new PersonalAccessTokenService.ResolvedToken("t1", "u1", "api:read"));
    JwtAuthenticationFilter filter =
        new JwtAuthenticationFilter(mock(JwtUtil.class), mock(UserDetailsServiceImpl.class), pats);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/runs");
    request.addHeader("Authorization", "Bearer " + pat);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    org.assertj.core.api.Assertions.assertThat(response.getStatus()).isEqualTo(403);
    org.assertj.core.api.Assertions.assertThat(chain.getRequest()).isNull();
  }

  @Test
  void scopedJwtInQueryParameterIsNeverPromotedToGeneralAuthentication() throws Exception {
    PersonalAccessTokenService pats = mock(PersonalAccessTokenService.class);
    JwtUtil jwtUtil = mock(JwtUtil.class);
    UserDetailsServiceImpl users = mock(UserDetailsServiceImpl.class);
    JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil, users, pats);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/tiers");
    request.setParameter("token", "short-lived-scoped-jwt");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    verifyNoInteractions(jwtUtil, users, pats);
    org.assertj.core.api.Assertions.assertThat(
            SecurityContextHolder.getContext().getAuthentication())
        .isNull();
  }
}
