package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Servlet filter that reverse-proxies preview requests to Docker containers.
 *
 * <p>When traffic arrives with Host: {shortId}.preview.executespec.ai, this filter intercepts it
 * (before Spring Security / DispatcherServlet) and proxies to localhost:{port} where the Docker
 * container is running.
 *
 * <p>This eliminates the need for Caddy or an external IP on the VM — all preview traffic flows
 * through the existing GCP Load Balancer → port 8080.
 *
 * <p>Only active when preview.proxy.enabled=true AND CaddyProxyManager is NOT present. When Caddy
 * is available, it handles preview routing directly (faster, WebSocket support).
 */
@Component
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "preview.proxy.enabled", havingValue = "true")
@ConditionalOnMissingBean(CaddyProxyManager.class)
public class PreviewProxyFilter implements Filter {

  private final PreviewProperties props;
  private final PreviewPortPool portPool;

  /** Headers NOT forwarded to the upstream container. */
  private static final Set<String> HOP_BY_HOP =
      Set.of(
          "connection",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "te",
          "trailers",
          "transfer-encoding",
          "upgrade",
          "host");

  /**
   * Security headers from upstream preview containers that must be stripped. The container app
   * (e.g. a Spring Boot app inside the preview) may emit its own X-Frame-Options / CSP headers
   * which would override our permissive policy and block the preview iframe from rendering.
   */
  private static final Set<String> STRIPPED_SECURITY_HEADERS =
      Set.of(
          "x-frame-options",
          "content-security-policy",
          "content-security-policy-report-only",
          "strict-transport-security",
          "x-content-type-options",
          "x-xss-protection",
          "referrer-policy",
          "permissions-policy");

  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER) // let browser handle redirects
          .version(HttpClient.Version.HTTP_1_1)
          .build();

  public PreviewProxyFilter(PreviewProperties props, PreviewPortPool portPool) {
    this.props = props;
    this.portPool = portPool;
    log.info(
        "PreviewProxyFilter active — proxying preview-*.{} via Spring Boot",
        props.getPreviewDomain());
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse res = (HttpServletResponse) response;

    String host = req.getHeader("Host");
    if (host == null) {
      chain.doFilter(request, response);
      return;
    }

    // Strip port from Host header if present (e.g. "abc123.preview.executespec.ai:8080")
    String hostName = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;

    String domain = props.getPreviewDomain(); // "executespec.ai"
    if (!hostName.endsWith("." + domain)) {
      // Not a preview request — pass through to Spring
      chain.doFilter(request, response);
      return;
    }

    // Extract subdomain: "preview-17be5f33.executespec.ai" → "preview-17be5f33"
    String subdomain = hostName.substring(0, hostName.length() - domain.length() - 1);
    if (!subdomain.startsWith("preview-")) {
      // Not a preview request (e.g. "dev.executespec.ai") — pass through
      chain.doFilter(request, response);
      return;
    }

    // Extract shortId: "preview-17be5f33" → "17be5f33"
    String shortId = subdomain.substring("preview-".length());
    if (shortId.isBlank()) {
      res.sendError(404, "Preview not found");
      return;
    }

    // Look up the port from the pool by matching shortId prefix
    int port = findPortByShortId(shortId);
    if (port < 0) {
      res.sendError(404, "Preview not found or expired");
      return;
    }

    // Build upstream URL
    String path = req.getRequestURI();
    String query = req.getQueryString();
    String upstream = "http://localhost:" + port + path + (query != null ? "?" + query : "");

    proxy(req, res, upstream);
  }

  /**
   * Find the port allocated for a runId whose first 8 chars match shortId. The PortPool stores full
   * runId → port, so we scan for a prefix match.
   */
  private int findPortByShortId(String shortId) {
    // PreviewPortPool doesn't expose iteration, so we use PreviewService's active sessions.
    // However, PortPool.getPort(runId) requires the full runId.
    // Solution: iterate the port pool's allocations via the public API.
    // Since PortPool only has getPort(runId), we need to try matching.
    // For now, use the fact that PreviewService stores active sessions.
    //
    // Better approach: ask PortPool to find by prefix.
    return portPool.findPortByPrefix(shortId);
  }

  private void proxy(HttpServletRequest req, HttpServletResponse res, String upstream)
      throws IOException {

    try {
      // Build the upstream request
      HttpRequest.Builder builder =
          HttpRequest.newBuilder().uri(URI.create(upstream)).timeout(Duration.ofSeconds(30));

      // Copy request headers (skip hop-by-hop)
      var headerNames = req.getHeaderNames();
      while (headerNames.hasMoreElements()) {
        String name = headerNames.nextElement();
        if (!HOP_BY_HOP.contains(name.toLowerCase())) {
          builder.header(name, req.getHeader(name));
        }
      }

      // Set Host to localhost so the container sees the right host
      builder.header("Host", "localhost");

      // Forward X-Forwarded headers
      builder.header("X-Forwarded-Host", req.getHeader("Host"));
      builder.header("X-Forwarded-Proto", req.getScheme());

      // Set the method + body
      String method = req.getMethod().toUpperCase();
      switch (method) {
        case "GET" -> builder.GET();
        case "DELETE" -> builder.DELETE();
        case "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
        case "OPTIONS" -> builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
        default -> {
          // POST, PUT, PATCH — forward body
          byte[] body = req.getInputStream().readAllBytes();
          builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
      }

      HttpResponse<InputStream> upstream_res =
          http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

      // Copy status
      res.setStatus(upstream_res.statusCode());

      // Copy response headers — strip hop-by-hop AND upstream security headers.
      // Preview containers may emit their own X-Frame-Options/CSP that would block
      // iframe embedding in the ExecuteSpec dashboard.
      upstream_res
          .headers()
          .map()
          .forEach(
              (name, values) -> {
                String lower = name.toLowerCase();
                if (!HOP_BY_HOP.contains(lower)
                    && !STRIPPED_SECURITY_HEADERS.contains(lower)
                    && !name.equalsIgnoreCase(":status")) {
                  for (String v : values) {
                    res.addHeader(name, v);
                  }
                }
              });

      // Stream response body
      try (InputStream in = upstream_res.body();
          OutputStream out = res.getOutputStream()) {
        in.transferTo(out);
        out.flush();
      }

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      res.sendError(502, "Preview proxy interrupted");
    } catch (Exception e) {
      log.warn("Preview proxy error for {}: {}", upstream, e.getMessage());
      res.sendError(502, "Preview unavailable");
    }
  }
}
