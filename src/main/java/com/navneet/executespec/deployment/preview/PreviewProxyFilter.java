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

  /** Headers NOT copied back from the upstream container. */
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
   * The ONLY request headers forwarded to the previewed container (RR-159).
   *
   * <p>This is an allowlist by design. The upstream is untrusted, customer-generated code with
   * outbound network access, and the previous behaviour copied every header the browser sent —
   * including {@code Cookie}, {@code Authorization} and the edge's identity assertions ({@code
   * CF-Connecting-IP}, {@code CF-Access-Jwt-Assertion}, {@code X-Forwarded-*}). Anything not named
   * here is dropped, so a header added later at the edge cannot silently start leaking.
   *
   * <p>Note that {@code Cookie} being dropped means a preview cannot hold a browser session on the
   * ExecuteSpec domain — which is the point; a preview needing its own session must be served from
   * a separate registrable domain, which remains open work.
   */
  private static final Set<String> FORWARDED_REQUEST_HEADERS =
      Set.of(
          "accept",
          "accept-encoding",
          "accept-language",
          "cache-control",
          "content-length",
          "content-type",
          "if-modified-since",
          "if-none-match",
          "pragma",
          "range",
          "user-agent");

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

  /**
   * RR-160 — hard byte ceilings so anonymous preview traffic cannot exhaust JVM heap / bandwidth.
   * The request body is buffered (the JDK HttpClient body publisher needs the full array), so it is
   * capped tighter; the response is streamed, so its cap is a defensive upper bound on a single
   * proxied download. Exceeding either fails cleanly rather than allocating without limit.
   */
  static final long MAX_REQUEST_BODY_BYTES = 25L * 1024 * 1024; // 25 MiB

  static final long MAX_RESPONSE_BODY_BYTES = 100L * 1024 * 1024; // 100 MiB

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

    // Extract the routing capability: "preview-<32 hex>" → "<32 hex>".
    // RR-158: this is matched EXACTLY against a minted 128-bit label. It is not a runId prefix,
    // so no amount of guessing short strings reaches another tenant's preview.
    String hostLabel = subdomain.substring("preview-".length()).toLowerCase(java.util.Locale.ROOT);
    int port = portPool.findPortByHostLabel(hostLabel);
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

  private void proxy(HttpServletRequest req, HttpServletResponse res, String upstream)
      throws IOException {

    try {
      // Build the upstream request
      HttpRequest.Builder builder =
          HttpRequest.newBuilder().uri(URI.create(upstream)).timeout(Duration.ofSeconds(30));

      // RR-159: forward ONLY the allowlisted headers. Browser credentials (Cookie,
      // Authorization), edge identity assertions and tracing metadata never reach the previewed
      // code.
      var headerNames = req.getHeaderNames();
      while (headerNames.hasMoreElements()) {
        String name = headerNames.nextElement();
        if (FORWARDED_REQUEST_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
          builder.header(name, req.getHeader(name));
        }
      }

      // Set Host to localhost so the container sees the right host.
      builder.header("Host", "localhost");

      // Deliberately NOT forwarding the real X-Forwarded-Host: the preview host label is the
      // routing capability and handing it to the previewed code lets it print or exfiltrate its
      // own reachable URL. Scheme is safe and apps commonly need it to build absolute links.
      builder.header("X-Forwarded-Proto", req.getScheme());

      // Set the method + body
      String method = req.getMethod().toUpperCase();
      switch (method) {
        case "GET" -> builder.GET();
        case "DELETE" -> builder.DELETE();
        case "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
        case "OPTIONS" -> builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
        default -> {
          // POST, PUT, PATCH — forward body. RR-160: bounded read so an oversized upload cannot
          // buffer unbounded bytes into heap; over-limit fails cleanly with 413 before dispatch.
          byte[] body;
          try {
            body = readBounded(req.getInputStream(), MAX_REQUEST_BODY_BYTES);
          } catch (BodyTooLargeException tooLarge) {
            res.sendError(413, "Preview request body too large");
            return;
          }
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
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (HOP_BY_HOP.contains(lower)
                    || STRIPPED_SECURITY_HEADERS.contains(lower)
                    || name.equalsIgnoreCase(":status")) {
                  return;
                }
                // RR-159 (response path): a preview lives on preview-<label>.executespec.ai, a
                // subdomain that shares the registrable domain with the real app. Copying an
                // upstream Set-Cookie verbatim lets untrusted customer code emit
                // "Set-Cookie: sid=x; Domain=.executespec.ai", which the browser would then also
                // send to dev/app.executespec.ai — a cookie injection / fixation vector against the
                // platform. Rewrite every Set-Cookie so any Domain attribute is dropped, making the
                // cookie host-only to the exact preview host (a host-only cookie is never sent to
                // another executespec.ai host). The preview keeps working cookies for its own
                // origin.
                if ("set-cookie".equals(lower) || "set-cookie2".equals(lower)) {
                  for (String v : values) {
                    res.addHeader(name, stripCookieDomain(v));
                  }
                  return;
                }
                for (String v : values) {
                  res.addHeader(name, v);
                }
              });

      // Stream response body. RR-160: copy under a finite byte ceiling so a slow/unbounded
      // generated response cannot pin threads and heap indefinitely. Status + headers are already
      // committed, so overflow can only truncate: stop copying and abort the connection.
      try (InputStream in = upstream_res.body();
          OutputStream out = res.getOutputStream()) {
        copyBounded(in, out, MAX_RESPONSE_BODY_BYTES, upstream);
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

  /** Thrown by {@link #readBounded} when an upload exceeds {@link #MAX_REQUEST_BODY_BYTES}. */
  static final class BodyTooLargeException extends IOException {
    BodyTooLargeException(long limit) {
      super("request body exceeds " + limit + " bytes");
    }
  }

  /**
   * Reads at most {@code limit} bytes from {@code in} into a fresh array (RR-160). If the stream
   * still has data after {@code limit} bytes have been consumed, throws {@link
   * BodyTooLargeException} instead of allocating without bound.
   */
  static byte[] readBounded(InputStream in, long limit) throws IOException {
    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    long total = 0;
    int n;
    while ((n = in.read(chunk)) != -1) {
      total += n;
      if (total > limit) {
        throw new BodyTooLargeException(limit);
      }
      buf.write(chunk, 0, n);
    }
    return buf.toByteArray();
  }

  /**
   * Streams {@code in} to {@code out}, stopping after {@code limit} bytes (RR-160). Because the
   * response status/headers are already committed when this runs, an over-limit body cannot be
   * turned into an error code — it is truncated and the overflow is logged so operators can see it.
   */
  static void copyBounded(InputStream in, OutputStream out, long limit, String upstream)
      throws IOException {
    byte[] chunk = new byte[8192];
    long total = 0;
    int n;
    while ((n = in.read(chunk)) != -1) {
      if (total + n > limit) {
        int allowed = (int) (limit - total);
        if (allowed > 0) {
          out.write(chunk, 0, allowed);
        }
        log.warn("Preview response from {} exceeded {} bytes; truncated", upstream, limit);
        return;
      }
      out.write(chunk, 0, n);
      total += n;
    }
  }

  /**
   * Removes any {@code Domain} attribute from a Set-Cookie value (RR-159). A cookie set without a
   * Domain is "host-only" — the browser returns it only to the exact host that set it
   * (preview-&lt;label&gt;.executespec.ai), never to a sibling like dev/app.executespec.ai. This
   * neutralizes an untrusted preview's attempt to scope a cookie to the parent registrable domain
   * while leaving the cookie functional for the preview's own origin. Every other attribute (Path,
   * Secure, HttpOnly, SameSite, Max-Age, Expires, the name=value pair) is preserved verbatim.
   */
  static String stripCookieDomain(String setCookie) {
    if (setCookie == null || setCookie.isEmpty()) {
      return setCookie;
    }
    String[] segments = setCookie.split(";");
    StringBuilder out = new StringBuilder(setCookie.length());
    boolean first = true;
    for (String segment : segments) {
      String trimmed = segment.trim();
      // Drop "Domain=..." (and a bare "Domain"); attribute names are case-insensitive per RFC 6265.
      String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
      if (lower.equals("domain") || lower.startsWith("domain=")) {
        continue;
      }
      if (!first) {
        out.append("; ");
      }
      out.append(trimmed);
      first = false;
    }
    return out.toString();
  }
}
