package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;

import com.navneet.executespec.auth.security.JwtAuthenticationFilter;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class AgentSessionControllerWriterAuthTest {

  @Test
  void browserJwtCannotForgeCliWriterAuthority() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getAttribute(JwtAuthenticationFilter.AUTH_SOURCE_ATTRIBUTE))
        .thenReturn(JwtAuthenticationFilter.AUTH_SOURCE_JWT);

    assertThatThrownBy(() -> AgentSessionController.requirePatLocalHands(request))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("PAT local-hands");
  }

  @Test
  void missingAuthenticationSourceFailsClosed() {
    assertThatThrownBy(() -> AgentSessionController.requirePatLocalHands(null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void authenticatedPatMayReachTheStillFeatureGatedService() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getAttribute(JwtAuthenticationFilter.AUTH_SOURCE_ATTRIBUTE))
        .thenReturn(JwtAuthenticationFilter.AUTH_SOURCE_PAT);

    assertThatCode(() -> AgentSessionController.requirePatLocalHands(request))
        .doesNotThrowAnyException();
  }
}
