package com.navneet.executespec.testgen;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Probes a running preview's common API endpoints to determine which routes are actually live.
 * Results are injected into the test worker prompt so that generated tests target only reachable
 * endpoints and use correct status codes.
 *
 * <p><strong>RR-1588.</strong> The probe paths are route literals regex-scraped out of
 * LLM-generated workspace source, i.e. attacker-influenced strings. They used to be concatenated
 * straight onto the base URL with redirects followed, so a literal such as {@code
 * @attacker.example/x} produced {@code http://<preview-host>@attacker.example/x} — the preview host
 * parsed as userinfo and the server issuing a GET to an attacker-chosen host from inside the
 * deployment network, with the status code fed back into the test-worker prompt as an oracle. Every
 * probe URL is now resolved against the base URI and its host re-asserted before the request, and
 * redirects are not followed.
 */
@Component
@Slf4j
public class ApiProber {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final int MAX_PROBES = 12;

  /** Common probe paths checked against every preview. */
  private static final List<String> COMMON_PATHS =
      List.of("/api/health", "/health", "/actuator/health", "/api", "/api/v1", "/");

  /**
   * Probe a list of known API endpoint paths against the preview base URL. Returns a markdown
   * summary of which endpoints responded and with what status.
   *
   * @param baseUrl Preview base URL (e.g. http://preview-abc.run.app)
   * @param knownPaths API paths extracted from the workspace (may be empty)
   * @return markdown bullet list describing probe results; empty string if probe fails
   */
  public String probe(String baseUrl, List<String> knownPaths) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return "";
    }
    URI base;
    try {
      base = URI.create(baseUrl.replaceAll("/+$", "") + "/");
    } catch (IllegalArgumentException e) {
      log.debug("[ApiProber] Unusable preview base URL");
      return "";
    }
    if (base.getHost() == null) {
      log.debug("[ApiProber] Preview base URL has no host");
      return "";
    }
    try {
      HttpClient client =
          HttpClient.newBuilder()
              .connectTimeout(TIMEOUT)
              // RR-1588 — a probe must never be redirected off the preview host.
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();

      List<String> pathsToProbe = new ArrayList<>(COMMON_PATHS);
      if (knownPaths != null) {
        for (String p : knownPaths) {
          if (!isProbablePath(p)) {
            log.debug("[ApiProber] Dropping non-path route literal");
            continue;
          }
          if (!pathsToProbe.contains(p)) {
            pathsToProbe.add(p);
          }
          if (pathsToProbe.size() >= MAX_PROBES) break;
        }
      }

      StringBuilder sb = new StringBuilder();
      int liveCount = 0;
      for (String path : pathsToProbe) {
        URI target = resolveOnHost(base, path);
        if (target == null) {
          sb.append("- ").append(path).append(" → SKIPPED_UNSAFE_PATH\n");
          continue;
        }
        try {
          HttpRequest req = HttpRequest.newBuilder().uri(target).timeout(TIMEOUT).GET().build();
          HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
          int status = resp.statusCode();
          String label = status < 400 ? "LIVE" : (status == 404 ? "NOT_FOUND" : "ERROR");
          sb.append("- ")
              .append(path)
              .append(" → ")
              .append(status)
              .append(" (")
              .append(label)
              .append(")\n");
          if (status < 400) liveCount++;
        } catch (Exception e) {
          sb.append("- ").append(path).append(" → UNREACHABLE\n");
        }
      }

      if (sb.isEmpty()) return "";
      return "Live endpoint probe at "
          + baseUrl
          + " ("
          + liveCount
          + "/"
          + pathsToProbe.size()
          + " reachable):\n"
          + sb;
    } catch (Exception e) {
      log.debug("[ApiProber] Probe failed for {}: {}", baseUrl, e.getMessage());
      return "";
    }
  }

  /**
   * Structural admission for a model-authored route literal (RR-1588). Must be a rooted, relative
   * path: a leading slash, no protocol-relative {@code //} prefix, no userinfo {@code @}, no
   * scheme-like prefix, no control characters or whitespace.
   */
  static boolean isProbablePath(String path) {
    if (path == null || path.isBlank()) return false;
    if (!path.startsWith("/") || path.startsWith("//")) return false;
    if (path.indexOf('@') >= 0 || path.indexOf('\\') >= 0) return false;
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c <= ' ' || c == 0x7f) return false;
    }
    return !SCHEME_PREFIX.matcher(path).find();
  }

  private static final java.util.regex.Pattern SCHEME_PREFIX =
      java.util.regex.Pattern.compile("(?i)^/*[a-z][a-z0-9+.-]*:");

  /**
   * Resolve a probe path against the preview base and re-assert that the result still points at the
   * preview host, port and scheme. Returns null when the path is unusable or would leave the host.
   */
  static URI resolveOnHost(URI base, String path) {
    if (!isProbablePath(path)) return null;
    URI resolved;
    try {
      // Build the relative reference through the quoting constructor: route literals legitimately
      // contain characters that are illegal in a URI (Spring's `/api/tasks/{id}`), and quoting them
      // here also means no part of the literal can be reinterpreted as an authority.
      int q = path.indexOf('?');
      String rawPath = q >= 0 ? path.substring(0, q) : path;
      String rawQuery = q >= 0 ? path.substring(q + 1) : null;
      URI relative = new URI(null, null, rawPath, rawQuery, null);
      resolved = base.resolve(relative).normalize();
    } catch (IllegalArgumentException | java.net.URISyntaxException e) {
      return null;
    }
    if (!resolved.isAbsolute() || resolved.getHost() == null) return null;
    if (resolved.getUserInfo() != null) return null;
    if (!resolved.getHost().equalsIgnoreCase(base.getHost())) return null;
    if (resolved.getPort() != base.getPort()) return null;
    if (base.getScheme() == null || !base.getScheme().equalsIgnoreCase(resolved.getScheme())) {
      return null;
    }
    return resolved;
  }
}
