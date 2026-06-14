package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.auth.security.JwtAuthenticationFilter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RunControllerLocalExecutionAuthTest {

  @Test
  void localExecutionAcceptsOnlyPatAuthenticatedRequests() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    assertThat(RunController.isPatAuthenticated(request)).isFalse();

    request.setAttribute(
        JwtAuthenticationFilter.AUTH_SOURCE_ATTRIBUTE, JwtAuthenticationFilter.AUTH_SOURCE_JWT);
    assertThat(RunController.isPatAuthenticated(request)).isFalse();

    request.setAttribute(
        JwtAuthenticationFilter.AUTH_SOURCE_ATTRIBUTE, JwtAuthenticationFilter.AUTH_SOURCE_PAT);
    assertThat(RunController.isPatAuthenticated(request)).isTrue();
  }
}
