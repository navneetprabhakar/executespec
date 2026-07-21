package com.navneet.executespec.auth.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Single validation seam for every credential-validation probe that leaves the server (personal and
 * org GitHub / JIRA / Confluence / AI-provider key checks). RR-001 / RR-002 / RR-003.
 *
 * <p>Both the personal ({@code /api/auth/settings/**}) and org ({@code /api/org/me/settings/**})
 * validators build the target URL from a user-supplied base URL, so an authenticated caller could
 * otherwise point the server at {@code http://169.254.169.254/…} (cloud metadata) or an internal
 * host. The org endpoints previously performed the fetch with no target policy at all and echoed
 * the raw exception message back to the caller.
 *
 * <p>Guarantees enforced here, in order:
 *
 * <ul>
 *   <li><b>Scheme/host policy</b> — HTTPS only, no embedded credentials, valid port.
 *   <li><b>Target policy</b> — <em>every</em> address the host resolves to must be a public unicast
 *       address; loopback / any-local / link-local / site-local / multicast / CGNAT / IPv6
 *       unique-local are refused.
 *   <li><b>DNS-rebinding safety (RR-003)</b> — the connection is opened to the exact {@link
 *       InetAddress} that was validated, not to the hostname, so a second attacker-controlled
 *       resolution cannot be substituted between check and connect. TLS still performs full
 *       certificate hostname verification against the original hostname via SNI.
 *   <li><b>Bounded response handling (RR-002)</b> — only the HTTP status line is read
 *       (hard-capped); the body is never buffered, and the socket is closed immediately afterwards.
 *   <li><b>Redirect denial</b> — 3xx is reported as a plain non-200 status; nothing is followed.
 *   <li><b>Safe errors</b> — callers only ever receive a category, never an exception message,
 *       resolved address, or other internal transport detail.
 * </ul>
 */
@Slf4j
@Service
public class ExternalConnectionValidator {

  /** Outcome category of a probe. Deliberately coarse — no transport detail escapes. */
  public enum Status {
    /** The endpoint answered HTTP 200. */
    OK,
    /** The target URL is not an allowed external address; nothing was contacted. */
    TARGET_NOT_ALLOWED,
    /** The endpoint answered, but with a non-200 status (including any redirect). */
    HTTP_ERROR,
    /** The endpoint could not be reached, or the response was unusable. */
    UNREACHABLE
  }

  /** Probe result. {@code statusCode} is meaningful only for {@link Status#HTTP_ERROR}. */
  public record ProbeResult(Status status, int statusCode) {
    public boolean ok() {
      return status == Status.OK;
    }
  }

  private static final int CONNECT_TIMEOUT_MS = 5_000;
  private static final int READ_TIMEOUT_MS = 10_000;

  /** Hard cap on the bytes read while looking for the end of the status line. */
  private static final int MAX_STATUS_LINE_BYTES = 1024;

  /**
   * Performs a bounded, SSRF-guarded HTTPS GET against {@code url} and reports only the outcome
   * category. No response body is read.
   */
  public ProbeResult probe(String url, Map<String, String> headers) {
    URI uri;
    try {
      uri = URI.create(url.strip());
    } catch (RuntimeException e) {
      return new ProbeResult(Status.TARGET_NOT_ALLOWED, 0);
    }
    if (!isAllowedUri(uri) || !areHeadersSafe(headers)) {
      return new ProbeResult(Status.TARGET_NOT_ALLOWED, 0);
    }
    String host = uri.getHost();
    int port = uri.getPort() == -1 ? 443 : uri.getPort();

    InetAddress pinned;
    try {
      pinned = resolveAndValidate(host);
    } catch (IOException e) {
      // Unresolvable, or at least one resolved address is not a permitted target.
      return new ProbeResult(Status.TARGET_NOT_ALLOWED, 0);
    }
    if (pinned == null) {
      return new ProbeResult(Status.TARGET_NOT_ALLOWED, 0);
    }

    try {
      int status = requestStatus(pinned, host, port, uri, headers);
      return status == 200
          ? new ProbeResult(Status.OK, 200)
          : new ProbeResult(Status.HTTP_ERROR, status);
    } catch (Exception e) {
      // Never surface e.getMessage() to the caller — it leaks internal transport detail.
      log.debug("External validation probe failed for host {}: {}", host, e.toString());
      return new ProbeResult(Status.UNREACHABLE, 0);
    }
  }

  // ---------------------------------------------------------------- policy

  private static boolean isAllowedUri(URI uri) {
    String scheme = uri.getScheme();
    if (scheme == null || !scheme.equalsIgnoreCase("https")) {
      return false;
    }
    if (uri.getUserInfo() != null) {
      return false; // credentials embedded in the URL
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      return false;
    }
    int port = uri.getPort();
    return port == -1 || (port > 0 && port <= 65535);
  }

  /** Rejects header injection through user-controlled values (CR/LF or other control chars). */
  private static boolean areHeadersSafe(Map<String, String> headers) {
    if (headers == null) {
      return true;
    }
    for (Map.Entry<String, String> e : headers.entrySet()) {
      if (e.getKey() == null || e.getValue() == null) {
        return false;
      }
      if (hasControlChars(e.getKey()) || hasControlChars(e.getValue())) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasControlChars(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x20 || c == 0x7f) {
        return true;
      }
    }
    return false;
  }

  /**
   * Resolves {@code host} once and requires <em>every</em> returned address to be a permitted
   * public target, then returns the address the connection will be pinned to. Requiring all of them
   * defeats a multi-record rebinding answer where only one record is private.
   */
  private static InetAddress resolveAndValidate(String host) throws IOException {
    InetAddress[] all = InetAddress.getAllByName(host);
    if (all.length == 0) {
      return null;
    }
    for (InetAddress addr : all) {
      if (isDisallowedAddress(addr)) {
        return null;
      }
    }
    return all[0];
  }

  static boolean isDisallowedAddress(InetAddress addr) {
    if (addr.isAnyLocalAddress()
        || addr.isLoopbackAddress()
        || addr.isLinkLocalAddress()
        || addr.isSiteLocalAddress()
        || addr.isMulticastAddress()) {
      return true;
    }
    byte[] b = addr.getAddress();
    if (b.length == 4) {
      int b0 = b[0] & 0xff;
      int b1 = b[1] & 0xff;
      if (b0 == 100 && b1 >= 64 && b1 <= 127) {
        return true; // 100.64.0.0/10 carrier-grade NAT
      }
      if (b0 == 0 || b0 == 127) {
        return true; // "this network" / loopback range
      }
    } else if (b.length == 16) {
      if ((b[0] & 0xfe) == 0xfc) {
        return true; // fc00::/7 IPv6 unique-local
      }
      // IPv4-mapped / IPv4-compatible forms must be judged on their IPv4 payload.
      boolean firstTenZero = true;
      for (int i = 0; i < 10; i++) {
        if (b[i] != 0) {
          firstTenZero = false;
          break;
        }
      }
      if (firstTenZero
          && ((b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff || (b[10] == 0 && b[11] == 0))) {
        byte[] v4 = new byte[] {b[12], b[13], b[14], b[15]};
        try {
          return isDisallowedAddress(InetAddress.getByAddress(v4));
        } catch (IOException ignored) {
          return true;
        }
      }
    }
    return false;
  }

  // --------------------------------------------------------------- transport

  /**
   * Opens a TLS connection to the pinned address (never re-resolving the hostname), issues a single
   * GET, reads only the status line and closes. Certificate hostname verification still runs
   * against {@code host}.
   */
  private static int requestStatus(
      InetAddress pinned, String host, int port, URI uri, Map<String, String> headers)
      throws IOException {
    try (Socket plain = new Socket()) {
      plain.connect(new InetSocketAddress(pinned, port), CONNECT_TIMEOUT_MS);
      plain.setSoTimeout(READ_TIMEOUT_MS);
      SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
      // The (socket, host, port) overload keeps the original hostname for SNI and certificate
      // verification while the TCP connection stays pinned to the validated address.
      try (SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true)) {
        SSLParameters params = ssl.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        params.setServerNames(java.util.List.of(new SNIHostName(host)));
        ssl.setSSLParameters(params);
        ssl.setSoTimeout(READ_TIMEOUT_MS);
        ssl.startHandshake();

        OutputStream out = ssl.getOutputStream();
        out.write(buildRequest(uri, host, port, headers));
        out.flush();
        return readStatusCode(ssl.getInputStream());
      }
    }
  }

  private static byte[] buildRequest(URI uri, String host, int port, Map<String, String> headers) {
    String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
    if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
      path = path + "?" + uri.getRawQuery();
    }
    Map<String, String> merged = new LinkedHashMap<>();
    merged.put("Host", port == 443 ? host : host + ":" + port);
    merged.put("Accept-Encoding", "identity");
    merged.put("User-Agent", "ExecuteSpec-credential-validator");
    if (headers != null) {
      merged.putAll(headers);
    }
    merged.put("Connection", "close");

    StringBuilder sb = new StringBuilder();
    sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
    merged.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
    sb.append("\r\n");
    return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  /**
   * Reads at most {@link #MAX_STATUS_LINE_BYTES} bytes looking for the end of the status line. The
   * response body is never read into memory (RR-002).
   */
  private static int readStatusCode(InputStream in) throws IOException {
    StringBuilder line = new StringBuilder();
    for (int read = 0; read < MAX_STATUS_LINE_BYTES; read++) {
      int c = in.read();
      if (c < 0) {
        break;
      }
      if (c == '\n') {
        break;
      }
      if (c != '\r') {
        line.append((char) c);
      }
    }
    // "HTTP/1.1 200 OK"
    String[] parts = line.toString().split(" ");
    if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
      throw new IOException("unparseable status line");
    }
    try {
      return Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      throw new IOException("unparseable status code");
    }
  }
}
