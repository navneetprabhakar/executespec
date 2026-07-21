package com.navneet.executespec.contracts.detection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * Shared helpers for the {@link FileBasedContractDetector} family. Stateless static methods only.
 */
@Slf4j
public final class DetectorUtils {

  private DetectorUtils() {}

  /** SHA-256 hex of UTF-8 bytes. Used for {@code contract_version.content_hash}. */
  public static String sha256(String content) {
    if (content == null) return null;
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  /**
   * Known contract file extensions, longest-first so compound extensions win. {@code .schema.json}
   * must precede {@code .json} because {@link
   * com.navneet.executespec.contracts.drift.DriftDetectionService#extensionFor} re-appends the
   * whole {@code .schema.json} suffix for JSON_SCHEMA contracts.
   */
  private static final List<String> KNOWN_EXTENSIONS =
      List.of(".schema.json", ".graphqls", ".graphql", ".proto", ".yaml", ".json", ".yml", ".gql");

  /**
   * Strip directory + the recognised extension to get a stable contract name. Falls back to the
   * full basename when the extension isn't recognised.
   *
   * <p>RR-1653 — this used to cut at the FIRST dot, so {@code api.v1.proto} and {@code
   * api.v2.proto} (or {@code payments.openapi.yaml} and {@code payments.internal.yaml}) both
   * collapsed onto the single {@code UNIQUE (repo_id, name)} registry row {@code api} / {@code
   * payments}. Every detection pass then bumped that row's version once per colliding file,
   * spamming {@code CONTRACT_VERSION_CHANGED} and leaving the breaking-change baseline pointing at
   * whichever file happened to be iterated last. Only the trailing known extension is removed now,
   * so intermediate qualifiers stay part of the identity.
   */
  public static String basenameWithoutExtension(String path) {
    if (path == null || path.isBlank()) return "unknown";
    int lastSlash = path.lastIndexOf('/');
    String basename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    for (String ext : KNOWN_EXTENSIONS) {
      if (basename.length() > ext.length()
          && basename.regionMatches(true, basename.length() - ext.length(), ext, 0, ext.length())) {
        return basename.substring(0, basename.length() - ext.length());
      }
    }
    return basename;
  }

  /**
   * Glob match with {@code **} (any depth) and {@code *} (any segment chars except {@code /}).
   * Cheaper than depending on a glob library for the small pattern set this project uses.
   */
  public static boolean matchesGlob(String path, String glob) {
    if (path == null || glob == null) return false;
    // Normalise leading "./" and "/"
    String p = path.startsWith("./") ? path.substring(2) : path;
    String g = glob.startsWith("./") ? glob.substring(2) : glob;
    StringBuilder rx = new StringBuilder("^");
    int i = 0;
    while (i < g.length()) {
      char c = g.charAt(i);
      if (c == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') {
        rx.append(".*");
        i += 2;
        if (i < g.length() && g.charAt(i) == '/') i++; // consume / after **
      } else if (c == '*') {
        rx.append("[^/]*");
        i++;
      } else if (c == '?') {
        rx.append("[^/]");
        i++;
      } else if (c == '.') {
        rx.append("\\.");
        i++;
      } else {
        rx.append(Pattern.quote(String.valueOf(c)));
        i++;
      }
    }
    rx.append("$");
    return Pattern.compile(rx.toString()).matcher(p).matches();
  }

  /**
   * Best-effort {@code info.version} pull from an OpenAPI/Swagger document. Returns null when not
   * present; never throws.
   */
  public static String pullOpenApiVersion(String content) {
    return firstGroup(
        content, Pattern.compile("(?m)^\\s*version\\s*:\\s*['\"]?([\\w.+-]+)['\"]?\\s*$"));
  }

  /**
   * Best-effort version pull from a proto file: {@code option java_package_version = "1.2.3";} or
   * {@code // version: 1.2.3}. Returns null when not present.
   */
  public static String pullProtoVersion(String content) {
    String fromOption =
        firstGroup(content, Pattern.compile("option\\s+\\w+_version\\s*=\\s*\"([\\w.+-]+)\""));
    if (fromOption != null) return fromOption;
    return firstGroup(content, Pattern.compile("(?im)^\\s*//\\s*version[:=]\\s*([\\w.+-]+)\\s*$"));
  }

  private static String firstGroup(String content, Pattern pattern) {
    if (content == null) return null;
    Matcher m = pattern.matcher(content);
    return m.find() ? m.group(1) : null;
  }
}
