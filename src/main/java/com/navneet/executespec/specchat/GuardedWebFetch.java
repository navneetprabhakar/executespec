package com.navneet.executespec.specchat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The single server-side outbound-HTTP seam for model-reachable web tools (RR-548). Every fetch a
 * model can cause — the chat surface's {@code web_search} / {@code web_fetch} ({@link
 * WebSearchChatTools}) and the local-hands compatibility {@code web_fetch} / {@code web_search} in
 * {@code tool/CompatibilityTools} — must route through here rather than opening its own {@code
 * HttpClient} against a model-supplied URL.
 *
 * <p>This is the body-reading sibling of {@code auth/service/ExternalConnectionValidator}, which
 * closed the same SSRF class for credential-validation probes (RR-001/002/003). It deliberately
 * reuses that class's target policy — HTTPS/HTTP only, no URL-embedded credentials, every resolved
 * address must be public unicast, and the TCP connection is pinned to the exact validated {@link
 * InetAddress} while TLS SNI and hostname verification still run against the original hostname, so
 * a second DNS answer cannot be substituted between check and connect. It adds the two things a
 * status-line-only probe never needed: a hard response-body byte cap enforced while streaming, and
 * an explicit redirect policy (3xx is reported, never followed, because a public URL could
 * otherwise bounce to an internal address).
 *
 * <p>Guarantees, in order:
 *
 * <ol>
 *   <li>scheme is {@code http}/{@code https}; no {@code user:pass@} credentials; URL length bound;
 *   <li>default ports only — an arbitrary port is how internal Redis/admin/metadata services are
 *       reached;
 *   <li>hostname policy — {@code localhost}, {@code *.localhost}, {@code metadata}, {@code
 *       metadata.google.internal}, {@code *.internal} refused before any resolution;
 *   <li>address policy — loopback / any-local / link-local (169.254.0.0/16, incl. cloud metadata) /
 *       site-local / multicast / CGNAT / 0.0.0.0/8 / 127.0.0.0/8 / IPv6 unique-local and the
 *       IPv4-mapped and IPv4-compatible forms of all of those are refused; <em>every</em> address
 *       the host resolves to must pass, so a multi-record rebinding answer with one private record
 *       is refused;
 *   <li>connection pinned to the validated address (DNS-rebinding safety);
 *   <li>redirects are never followed;
 *   <li>content type must be textual; {@code Content-Encoding} responses are refused (an
 *       unbounded-inflation vector);
 *   <li>declared {@code Content-Length} above the cap is refused before reading, and the body is
 *       byte-capped while streaming for both identity and chunked framing, so a lying or absent
 *       length still cannot exhaust heap;
 *   <li>errors are coarse categories — no exception message, resolved address, or other transport
 *       detail escapes to the model.
 * </ol>
 *
 * <p>This class is not a general HTTP client and must not grow request bodies, custom methods, or
 * caller-supplied headers: each of those reopens a distinct SSRF/request-smuggling surface.
 */
public final class GuardedWebFetch {

  /** Default response-body byte ceiling. Callers may request less, never more. */
  public static final int MAX_FETCH_BYTES = 64_000;

  private static final int MAX_URL = 2_048;
  private static final int MAX_HEADER_BYTES = 16_384;
  private static final int LINE_LIMIT = 4_096;
  private static final int CONNECT_TIMEOUT_MILLIS = 8_000;
  private static final int READ_TIMEOUT_MILLIS = 15_000;

  private GuardedWebFetch() {}

  /** Result of a guarded fetch. {@code error} carries a category only — never transport detail. */
  public record Outcome(boolean ok, String body, String error) {
    public static Outcome ok(String body) {
      return new Outcome(true, body, null);
    }

    public static Outcome err(String error) {
      return new Outcome(false, null, error);
    }
  }

  /** Seam so tests can assert the validated URI/host/pinned address without real network I/O. */
  @FunctionalInterface
  public interface Transport {
    Outcome fetch(URI uri, String host, InetAddress address, int maxBytes);
  }

  /** DNS seam used to keep policy tests hermetic while production resolves every host address. */
  @FunctionalInterface
  interface Resolver {
    List<InetAddress> resolve(String host) throws UnknownHostException;
  }

  /** The production transport: pinned socket, bounded headers, bounded body. */
  public static final Transport PINNED = GuardedWebFetch::pinnedFetch;

  /** Validate {@code rawUrl} against the full target policy and fetch with the default cap. */
  public static Outcome fetch(String rawUrl) {
    return fetch(rawUrl, MAX_FETCH_BYTES, PINNED);
  }

  /** Validate {@code rawUrl} and fetch through {@code transport}, capping the body at maxBytes. */
  public static Outcome fetch(String rawUrl, int maxBytes, Transport transport) {
    return fetch(
        rawUrl, maxBytes, transport, host -> Arrays.asList(InetAddress.getAllByName(host)));
  }

  static Outcome fetch(String rawUrl, int maxBytes, Transport transport, Resolver resolver) {
    Outcome policy = validateTarget(rawUrl);
    if (!policy.ok()) return policy;
    URI uri = URI.create(rawUrl.trim());
    String host = uri.getHost();
    List<InetAddress> addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException e) {
      return Outcome.err("host could not be resolved");
    }
    if (addresses.isEmpty()) return Outcome.err("host could not be resolved");
    for (InetAddress addr : addresses) {
      if (isDisallowedAddress(addr)) return Outcome.err("host resolves to a blocked address");
    }
    int cap = Math.min(Math.max(maxBytes, 1), MAX_FETCH_BYTES);
    return transport.fetch(uri, host, addresses.get(0), cap);
  }

  // ------------------------------------------------------------------ target policy

  /**
   * Scheme / credential / port / hostname policy. Returns an ok outcome with a null body when the
   * target is permissible; the DNS + address checks happen in {@link #fetch}.
   */
  public static Outcome validateTarget(String rawUrl) {
    if (rawUrl == null || rawUrl.isBlank()) return Outcome.err("url is required");
    if (rawUrl.length() > MAX_URL) return Outcome.err("URL is too long");
    URI uri;
    try {
      uri = URI.create(rawUrl.trim());
    } catch (IllegalArgumentException e) {
      return Outcome.err("invalid URL");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) {
      return Outcome.err("only http/https URLs are allowed");
    }
    if (uri.getUserInfo() != null) return Outcome.err("URL credentials are not allowed");
    if (!isAllowedFetchPort(uri)) return Outcome.err("only default http/https ports are allowed");
    String host = uri.getHost();
    if (host == null || host.isBlank()) return Outcome.err("URL has no host");
    if (isBlockedHost(host)) return Outcome.err("host is not allowed");
    return Outcome.ok(null);
  }

  /** Default ports only — an explicit port is how internal services are normally addressed. */
  public static boolean isAllowedFetchPort(URI uri) {
    int port = effectivePort(uri);
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    return (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
  }

  public static int effectivePort(URI uri) {
    if (uri.getPort() > 0) return uri.getPort();
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    return scheme.equals("https") ? 443 : 80;
  }

  /** Hostnames refused before resolution, so an internal name never reaches the resolver. */
  public static boolean isBlockedHost(String host) {
    if (host == null) return true;
    String h = host.trim().toLowerCase(Locale.ROOT);
    if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
    return h.isEmpty()
        || h.equals("localhost")
        || h.endsWith(".localhost")
        || h.equals("metadata")
        || h.equals("metadata.google.internal")
        || h.endsWith(".internal");
  }

  /**
   * Mirrors {@code ExternalConnectionValidator.isDisallowedAddress} — including the IPv4-mapped /
   * IPv4-compatible IPv6 unwrapping, which a naive check misses (e.g. {@code
   * ::ffff:169.254.169.254} is neither {@code isLinkLocalAddress()} nor {@code
   * isSiteLocalAddress()} on some stacks).
   */
  public static boolean isDisallowedAddress(InetAddress addr) {
    if (addr == null) return true;
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
      if (b0 == 100 && b1 >= 64 && b1 <= 127) return true; // 100.64.0.0/10 CGNAT
      return b0 == 0 || b0 == 127; // "this network" / loopback range
    }
    if (b.length == 16) {
      if ((b[0] & 0xfe) == 0xfc) return true; // fc00::/7 unique-local
      boolean firstTenZero = true;
      for (int i = 0; i < 10; i++) {
        if (b[i] != 0) {
          firstTenZero = false;
          break;
        }
      }
      boolean mapped = (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
      boolean compat = b[10] == 0 && b[11] == 0;
      if (firstTenZero && (mapped || compat)) {
        try {
          return isDisallowedAddress(
              InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
        } catch (UnknownHostException ignored) {
          return true;
        }
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- response policy

  /** Only textual media types are returned to a model; binaries are refused outright. */
  public static boolean isAllowedContentType(String value) {
    if (value == null || value.isBlank()) return true;
    String contentType = value.toLowerCase(Locale.ROOT);
    int semi = contentType.indexOf(';');
    String mediaType = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim();
    return mediaType.startsWith("text/")
        || mediaType.equals("application/json")
        || mediaType.endsWith("+json")
        || mediaType.equals("application/xml")
        || mediaType.endsWith("+xml")
        || mediaType.equals("application/javascript")
        || mediaType.equals("application/x-javascript")
        || mediaType.equals("application/x-www-form-urlencoded");
  }

  /** An unparseable Content-Length is treated as too large — fail closed, never optimistic. */
  public static boolean isContentLengthTooLarge(String value, int maxBytes) {
    if (value == null || value.isBlank()) return false;
    try {
      return Long.parseLong(value.trim()) > maxBytes;
    } catch (NumberFormatException e) {
      return true;
    }
  }

  /** Removes markup so fetched HTML enters the model as text, not as renderable structure. */
  public static String stripHtml(String html) {
    if (html == null) return "";
    return html.replaceAll("(?is)<script.*?</script>", " ")
        .replaceAll("(?is)<style.*?</style>", " ")
        .replaceAll("<[^>]+>", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  // -------------------------------------------------------------------- transport

  private static Outcome pinnedFetch(URI uri, String host, InetAddress address, int maxBytes) {
    int port = effectivePort(uri);
    try (Socket socket = openSocket(uri, host, address, port)) {
      writeRequest(socket.getOutputStream(), uri, host, port);
      InputStream in = socket.getInputStream();
      HeaderBlock headerBlock = readHeaders(in);
      int code = headerBlock.statusCode();
      if (code >= 300 && code < 400) {
        return Outcome.err("redirect (status " + code + ") not followed for safety");
      }
      if (code < 200 || code >= 300) {
        return Outcome.err("fetch failed with status " + code);
      }
      String contentType = headerBlock.firstHeader("content-type");
      if (!isAllowedContentType(contentType)) {
        return Outcome.err("content type is not allowed");
      }
      String contentEncoding = headerBlock.firstHeader("content-encoding");
      if (contentEncoding != null && !contentEncoding.isBlank()) {
        return Outcome.err("compressed responses are not accepted");
      }
      if (isContentLengthTooLarge(headerBlock.firstHeader("content-length"), maxBytes)) {
        return Outcome.err("response body is too large");
      }
      BodyBytes body = readBody(in, headerBlock, maxBytes);
      String text = new String(body.bytes(), charsetFor(contentType));
      if (body.truncated()) text += "\n...[truncated]";
      return Outcome.ok(text);
    } catch (IOException e) {
      return Outcome.err("network error");
    }
  }

  private static Socket openSocket(URI uri, String host, InetAddress address, int port)
      throws IOException {
    Socket raw = new Socket();
    // Connect to the exact address that passed policy; never re-resolve the hostname here.
    raw.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS);
    raw.setSoTimeout(READ_TIMEOUT_MILLIS);
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      return raw;
    }
    SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
    // The (socket, host, port) overload keeps the original hostname for SNI + certificate
    // verification while the TCP connection stays pinned to the validated address.
    SSLSocket ssl = (SSLSocket) sslFactory.createSocket(raw, host, port, true);
    SSLParameters params = ssl.getSSLParameters();
    params.setServerNames(List.of(new SNIHostName(host)));
    params.setEndpointIdentificationAlgorithm("HTTPS");
    ssl.setSSLParameters(params);
    ssl.startHandshake();
    ssl.setSoTimeout(READ_TIMEOUT_MILLIS);
    return ssl;
  }

  private static void writeRequest(OutputStream out, URI uri, String host, int port)
      throws IOException {
    String target = uri.getRawPath();
    if (target == null || target.isBlank()) target = "/";
    if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
      target += "?" + uri.getRawQuery();
    }
    String hostHeader = (port == 80 || port == 443) ? host : host + ":" + port;
    String request =
        "GET "
            + target
            + " HTTP/1.1\r\n"
            + "Host: "
            + hostHeader
            + "\r\n"
            + "User-Agent: ExecuteSpec-Agent/1.0\r\n"
            + "Accept: text/*, application/json, application/*+json, application/xml,"
            + " application/*+xml\r\n"
            + "Accept-Encoding: identity\r\n"
            + "Connection: close\r\n\r\n";
    out.write(request.getBytes(StandardCharsets.US_ASCII));
    out.flush();
  }

  private static HeaderBlock readHeaders(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int matched = 0;
    int b;
    byte[] marker = new byte[] {'\r', '\n', '\r', '\n'};
    while ((b = in.read()) != -1) {
      out.write(b);
      if (out.size() > MAX_HEADER_BYTES) throw new IOException("headers too large");
      matched = b == marker[matched] ? matched + 1 : (b == marker[0] ? 1 : 0);
      if (matched == marker.length) break;
    }
    if (matched != marker.length) throw new IOException("headers incomplete");
    String raw = out.toString(StandardCharsets.ISO_8859_1);
    String[] lines = raw.split("\\r?\\n");
    if (lines.length == 0 || !lines[0].startsWith("HTTP/")) {
      throw new IOException("invalid status line");
    }
    String[] status = lines[0].split(" ", 3);
    if (status.length < 2) throw new IOException("invalid status line");
    int code;
    try {
      code = Integer.parseInt(status[1]);
    } catch (NumberFormatException e) {
      throw new IOException("invalid status code", e);
    }
    Map<String, String> headers = new LinkedHashMap<>();
    for (int i = 1; i < lines.length; i++) {
      String line = lines[i];
      int colon = line.indexOf(':');
      if (colon <= 0) continue;
      String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      headers.merge(name, value, (a, b2) -> a + "," + b2);
    }
    return new HeaderBlock(code, headers);
  }

  private static BodyBytes readBody(InputStream in, HeaderBlock headers, int maxBytes)
      throws IOException {
    String transferEncoding = headers.firstHeader("transfer-encoding");
    if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
      return readChunkedBody(in, maxBytes);
    }
    return readUntilEof(in, maxBytes);
  }

  private static BodyBytes readUntilEof(InputStream in, int maxBytes) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int read;
    while ((read = in.read(buffer)) != -1) {
      int remaining = maxBytes - out.size();
      if (remaining <= 0) return new BodyBytes(out.toByteArray(), true);
      if (read > remaining) {
        out.write(buffer, 0, remaining);
        return new BodyBytes(out.toByteArray(), true);
      }
      out.write(buffer, 0, read);
    }
    return new BodyBytes(out.toByteArray(), false);
  }

  private static BodyBytes readChunkedBody(InputStream in, int maxBytes) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    while (true) {
      String sizeLine = readAsciiLine(in);
      int semi = sizeLine.indexOf(';');
      String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
      int chunkSize;
      try {
        chunkSize = Integer.parseInt(hex, 16);
      } catch (NumberFormatException e) {
        throw new IOException("invalid chunk size", e);
      }
      if (chunkSize < 0) throw new IOException("invalid chunk size");
      if (chunkSize == 0) return new BodyBytes(out.toByteArray(), false);
      int remaining = maxBytes - out.size();
      if (remaining <= 0) return new BodyBytes(out.toByteArray(), true);
      if (chunkSize > remaining) {
        out.write(readExact(in, remaining));
        return new BodyBytes(out.toByteArray(), true);
      }
      out.write(readExact(in, chunkSize));
      String crlf = readAsciiLine(in);
      if (!crlf.isEmpty()) throw new IOException("invalid chunk terminator");
    }
  }

  private static byte[] readExact(InputStream in, int count) throws IOException {
    byte[] data = in.readNBytes(count);
    if (data.length != count) throw new IOException("body ended early");
    return data;
  }

  private static String readAsciiLine(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\n') break;
      if (b != '\r') out.write(b);
      if (out.size() > LINE_LIMIT) throw new IOException("line too long");
    }
    if (b == -1) throw new IOException("line ended early");
    return out.toString(StandardCharsets.US_ASCII);
  }

  private static Charset charsetFor(String contentType) {
    if (contentType == null) return StandardCharsets.UTF_8;
    for (String part : contentType.split(";")) {
      String trimmed = part.trim();
      if (!trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) continue;
      String name = trimmed.substring("charset=".length()).trim().replace("\"", "");
      try {
        return Charset.forName(name);
      } catch (Exception ignored) {
        return StandardCharsets.UTF_8;
      }
    }
    return StandardCharsets.UTF_8;
  }

  private record HeaderBlock(int statusCode, Map<String, String> headers) {
    String firstHeader(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }
  }

  private record BodyBytes(byte[] bytes, boolean truncated) {}
}
