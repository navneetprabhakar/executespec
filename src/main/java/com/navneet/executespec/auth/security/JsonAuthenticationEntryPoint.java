package com.navneet.executespec.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 401 JSON entry point for unauthenticated REST API callers — closes
 * adversarial-test-report-2026-04-18 Finding 2.
 *
 * <p>Before: unauthenticated requests to {@code /api/**} and {@code /actuator/**}
 * landed on Spring's default {@code LoginUrlAuthenticationEntryPoint}, which
 * returned {@code 302 Found} redirecting to a login page. API clients (SDKs,
 * CI scripts, the SPA after token expiry) silently followed to HTML and
 * reported {@code res.ok === true}. Monitoring never alerted.
 *
 * <p>After: API / actuator paths get a clean {@code 401 Unauthorized} with a
 * stable JSON body that matches the shape of other failure advices (timestamp,
 * status, error, message, path). Non-API browser routes keep the default
 * redirect behaviour so OAuth2 login flows still work.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(0)
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
        if (isBackendPath(uri)) {
            writeJson(response, uri);
        } else {
            // Preserve the pre-existing redirect for SPA/OAuth2 flows.
            response.sendRedirect("/login");
        }
    }

    private static boolean isBackendPath(String uri) {
        return uri.startsWith("/api/")
                || uri.equals("/api")
                || uri.startsWith("/actuator/")
                || uri.equals("/actuator")
                || uri.startsWith("/webhooks/")
                || uri.startsWith("/oauth2/")
                || uri.startsWith("/login/oauth2/");
    }

    private void writeJson(HttpServletResponse response, String path) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.UNAUTHORIZED.value());
        body.put("error", "unauthorized");
        body.put("message", "Authentication required");
        body.put("path", path);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
