package com.navneet.executespec.previewrender;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.security.GeneratedSecretScanner;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Best-effort admission screen for model-generated preview HTML.
 *
 * <p>The rendered bundle is model output that will execute in an operator's browser, so it is
 * treated as untrusted regardless of which provider produced it.
 *
 * <p><b>Containment does not rest on this class.</b> The boundary is the iframe {@code sandbox}
 * without {@code allow-same-origin} — which gives the document an opaque origin with no access to
 * the app's DOM, cookies or storage — plus the CSP's {@code default-src 'none'} / {@code
 * connect-src 'none'} / {@code form-action 'none'}. Those hold regardless of what the markup
 * contains.
 *
 * <p>What this class actually is: a fixed deny-list of regexes over the lowercased document. It
 * rejects the recognisable shapes of outbound network calls, frame escapes and storage access, and
 * it admits anything it does not recognise. Obfuscation defeats it trivially — {@code
 * window["fe"+"tch"]} matches no pattern here — so treat it as a filter that catches accidents and
 * obvious cases, not as proof a bundle is inert.
 *
 * <p>Two checks it does enforce unconditionally: a hard size cap, and {@link
 * GeneratedSecretScanner} over the whole bundle. A rejected bundle produces a category only — never
 * the matched text — so a scanner finding cannot itself leak a secret.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreviewRenderGuard {

  /** Beyond this the bundle is not a screen render; it is a payload. */
  public static final int MAX_BUNDLE_BYTES = 2 * 1024 * 1024;

  /**
   * XML namespace declarations that are inert identifiers, not fetches. SVG and MathML markup is
   * invalid without them, so they are exempted from the external-URL rule.
   */
  private static final List<String> INERT_NAMESPACE_URLS =
      List.of(
          "http://www.w3.org/2000/svg",
          "http://www.w3.org/1999/xhtml",
          "http://www.w3.org/1999/xlink",
          "http://www.w3.org/1998/math/mathml",
          "http://www.w3.org/xml/1998/namespace");

  /** Any absolute or protocol-relative URL. Namespace URLs are stripped before this runs. */
  private static final Pattern EXTERNAL_URL =
      Pattern.compile("(?i)(https?:)?//[a-z0-9.-]+\\.[a-z]{2,}", Pattern.CASE_INSENSITIVE);

  /** A forbidden browser capability and the pattern that detects it. */
  private record ForbiddenApi(String label, Pattern pattern) {
    static ForbiddenApi of(String label, String regex) {
      return new ForbiddenApi(label, Pattern.compile(regex));
    }
  }

  /** Browser APIs capable of reaching the network or the embedding page. */
  private static final List<ForbiddenApi> FORBIDDEN_APIS =
      List.of(
          ForbiddenApi.of("FETCH", "\\bfetch\\s*\\("),
          ForbiddenApi.of("XHR", "\\bxmlhttprequest\\b"),
          ForbiddenApi.of("WEBSOCKET", "\\bwebsocket\\b"),
          ForbiddenApi.of("EVENTSOURCE", "\\beventsource\\b"),
          ForbiddenApi.of("SEND_BEACON", "\\bsendbeacon\\b"),
          ForbiddenApi.of("IMPORT_SCRIPTS", "\\bimportscripts\\b"),
          ForbiddenApi.of("SERVICE_WORKER", "\\bserviceworker\\b"),
          ForbiddenApi.of("DYNAMIC_IMPORT", "\\bimport\\s*\\("),
          ForbiddenApi.of("FRAME_ESCAPE", "\\b(?:window\\.)?(?:top|parent)\\s*\\."),
          ForbiddenApi.of(
              "STORAGE_ACCESS",
              "\\b(?:localstorage|sessionstorage|indexeddb|document\\.cookie)\\b"));

  private final GeneratedSecretScanner secretScanner;

  /**
   * The outcome of admission. A rejected bundle is never stored, so {@code reason} is the only
   * artifact that survives and must stay free of both secrets and raw model text.
   */
  public record Verdict(boolean admitted, String reason) {
    public static Verdict ok() {
      return new Verdict(true, null);
    }

    public static Verdict reject(String reason) {
      return new Verdict(false, reason);
    }
  }

  /** Validates a generated bundle. Returns the rejection category, or admitted. */
  public Verdict admit(String html) {
    if (html == null || html.isBlank()) {
      return Verdict.reject("EMPTY_BUNDLE");
    }
    if (html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BUNDLE_BYTES) {
      return Verdict.reject("BUNDLE_TOO_LARGE");
    }

    String lower = html.toLowerCase(Locale.ROOT);
    if (!lower.contains("<html") && !lower.contains("<body") && !lower.contains("<div")) {
      return Verdict.reject("NOT_HTML");
    }

    String scannable = stripInertNamespaces(lower);

    Matcher external = EXTERNAL_URL.matcher(scannable);
    if (external.find()) {
      // Deliberately does not log the URL: it is model-authored and may embed exfiltrated data.
      return Verdict.reject("EXTERNAL_NETWORK_REFERENCE");
    }

    for (ForbiddenApi api : FORBIDDEN_APIS) {
      if (api.pattern().matcher(scannable).find()) {
        return Verdict.reject("FORBIDDEN_API:" + api.label());
      }
    }

    Optional<GeneratedSecretScanner.Finding> secret = secretScanner.scan(html);
    if (secret.isPresent()) {
      log.warn("preview-render bundle blocked, credential category={}", secret.get().category());
      return Verdict.reject("CREDENTIAL_IN_BUNDLE:" + secret.get().category());
    }

    return Verdict.ok();
  }

  /**
   * The Content-Security-Policy served with an admitted bundle.
   *
   * <p>Deliberately allows inline style and script — the whole point of the bundle is that it is
   * self-contained — while denying every fetch destination. Combined with the iframe's missing
   * {@code allow-same-origin}, a bundle that slipped a network call past the deny-list would still
   * have nowhere to send it and no origin to read from.
   *
   * <p>Note which directives survive delivery. The UI mounts bundles through {@code srcdoc}, so the
   * policy travels as a {@code <meta http-equiv>} tag, and the CSP specification ignores {@code
   * frame-ancestors} and {@code sandbox} in meta form. Both are still emitted because the same
   * policy is sent as a response header for direct URL loads, but on the {@code srcdoc} path they
   * are inert — the iframe's own {@code sandbox} attribute is what supplies the sandboxing and the
   * opaque origin there. The load-bearing directives ({@code default-src}, {@code connect-src},
   * {@code img-src}, {@code font-src}, {@code form-action}, {@code base-uri}) do apply in meta
   * form.
   */
  public String contentSecurityPolicy() {
    return "default-src 'none'; "
        + "style-src 'unsafe-inline'; "
        + "script-src 'unsafe-inline'; "
        + "img-src data:; "
        + "font-src data:; "
        + "connect-src 'none'; "
        + "form-action 'none'; "
        + "frame-ancestors 'self'; "
        + "base-uri 'none'; "
        + "sandbox allow-scripts";
  }

  private String stripInertNamespaces(String lowerHtml) {
    String out = lowerHtml;
    for (String ns : INERT_NAMESPACE_URLS) {
      out = out.replace(ns, "");
    }
    return out;
  }
}
