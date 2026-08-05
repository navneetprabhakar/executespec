package com.navneet.executespec.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

/**
 * Pins the 403 error contract: filter-level access denials on backend paths must return the SAME
 * stable JSON field shape as the 401 entry point ({@link JsonAuthenticationEntryPoint}) and the 400
 * advices — never Spring's default error body.
 */
class JsonAccessDeniedHandlerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final JsonAccessDeniedHandler handler = new JsonAccessDeniedHandler(objectMapper);

  @Test
  void backendPathGetsStableJson403MatchingThe401ContractShape() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/tiers");
    request.setRequestURI("/api/admin/tiers");
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.handle(request, response, new AccessDeniedException("Access Denied"));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);

    @SuppressWarnings("unchecked")
    Map<String, Object> body = objectMapper.readValue(response.getContentAsString(), Map.class);
    // Exact field set + order of the 401 producer: timestamp, status, error, message, path.
    assertThat(body.keySet()).containsExactly("timestamp", "status", "error", "message", "path");
    assertThat(body)
        .containsEntry("status", 403)
        .containsEntry("error", "forbidden")
        .containsEntry("message", "Access denied")
        .containsEntry("path", "/api/admin/tiers");
  }

  @Test
  void field403ShapeEqualsThe401EntryPointShape() throws Exception {
    // 401 body from the existing entry point.
    MockHttpServletRequest unauthRequest = new MockHttpServletRequest("GET", "/api/runs");
    unauthRequest.setRequestURI("/api/runs");
    MockHttpServletResponse unauthResponse = new MockHttpServletResponse();
    new JsonAuthenticationEntryPoint(objectMapper)
        .commence(
            unauthRequest,
            unauthResponse,
            new InsufficientAuthenticationException("Full authentication is required"));

    // 403 body from the new handler.
    MockHttpServletRequest deniedRequest = new MockHttpServletRequest("GET", "/api/runs");
    deniedRequest.setRequestURI("/api/runs");
    MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
    handler.handle(deniedRequest, deniedResponse, new AccessDeniedException("Access Denied"));

    @SuppressWarnings("unchecked")
    Map<String, Object> unauthorized =
        objectMapper.readValue(unauthResponse.getContentAsString(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> forbidden =
        objectMapper.readValue(deniedResponse.getContentAsString(), Map.class);

    assertThat(forbidden.keySet()).isEqualTo(unauthorized.keySet());
    assertThat(unauthorized).containsEntry("error", "unauthorized").containsEntry("status", 401);
    assertThat(forbidden).containsEntry("error", "forbidden").containsEntry("status", 403);
  }

  @Test
  void bodyNeverLeaksExceptionDetailOrStackTraces() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/tiers");
    request.setRequestURI("/api/admin/tiers");
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.handle(
        request,
        response,
        new AccessDeniedException(
            "internal detail: role SUPER_ADMIN required at SecurityConfig.java:262"));

    String content = response.getContentAsString();
    assertThat(content).doesNotContain("SUPER_ADMIN");
    assertThat(content).doesNotContain("SecurityConfig");
    assertThat(content).doesNotContain("Exception");
  }

  @Test
  void backendPrefixesAllGetJsonWhileSpaPathsKeepDefaultBehaviour() throws Exception {
    for (String uri : List.of("/api", "/actuator/env", "/webhooks/github", "/oauth2/authorize")) {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
      request.setRequestURI(uri);
      MockHttpServletResponse response = new MockHttpServletResponse();
      handler.handle(request, response, new AccessDeniedException("denied"));
      assertThat(response.getStatus()).as(uri).isEqualTo(403);
      assertThat(response.getContentType()).as(uri).startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    MockHttpServletRequest spaRequest = new MockHttpServletRequest("GET", "/home");
    spaRequest.setRequestURI("/home");
    MockHttpServletResponse spaResponse = new MockHttpServletResponse();
    handler.handle(spaRequest, spaResponse, new AccessDeniedException("denied"));
    assertThat(spaResponse.getStatus()).isEqualTo(403);
    // sendError path — no JSON envelope was written by the handler itself.
    assertThat(spaResponse.getContentAsString()).isEmpty();
  }
}
