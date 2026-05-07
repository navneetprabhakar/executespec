package com.navneet.executespec.tool;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * Pre-build Maven dependency validator.
 *
 * <p>Catches the failure class observed during the 2026-05-04 eval where an LLM hallucinated a
 * plausible-but-fake dep version ({@code flyway-database-h2:10.10.0}) and the build failed with
 * "not found in repo.maven.apache.org/maven2". The reviewer + worker burned 79k tokens chasing
 * downstream symptoms because the actual error was outside the code itself.
 *
 * <p>Strategy: when the project has a {@code pom.xml}, parse every {@code <dependency>} block with
 * an explicit {@code <version>}, then fire a HEAD request against {@code
 * https://repo1.maven.org/maven2/{groupId}/{artifactId}/{version}/} for each one. Any 404 is a
 * hallucinated dep — the build is doomed without intervention, so failing fast with a precise "dep
 * X not found at version Y" message saves a lot of compute.
 *
 * <p>Caveats:
 *
 * <ul>
 *   <li>Only checks deps with EXPLICIT versions. Deps inheriting from a parent BOM or Spring Boot's
 *       dependency-management plugin don't have a {@code <version>} block — those are presumed
 *       valid (Maven will resolve them at build time and the BOM has its own versioning).
 *   <li>{@code ${property}} references are resolved against the same pom's {@code <properties>}
 *       block. Unresolvable properties (e.g. inherited from a parent pom outside the workspace) are
 *       skipped — we'd rather miss a check than block a legit build.
 *   <li>Network failures (DNS, timeout) are treated as "validator unavailable" — we skip the check
 *       rather than fail the build. The validator is best-effort.
 *   <li>The HEAD request is cheap (~50ms) and runs serially. For a typical Spring Boot pom with 3-5
 *       explicit-version deps, total overhead is ~250ms.
 * </ul>
 */
@Component
@Slf4j
public class MavenDependencyValidator {

  private static final String MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2";

  /** Group + artifact + version triple. The regex pulls these out of pom.xml. */
  private static final Pattern DEPENDENCY_BLOCK =
      Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

  private static final Pattern GROUP_ID = Pattern.compile("<groupId>([^<]+)</groupId>");
  private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
  private static final Pattern VERSION = Pattern.compile("<version>([^<]+)</version>");
  private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)\\}");
  private static final Pattern PROPERTIES_BLOCK =
      Pattern.compile("<properties>(.*?)</properties>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
  private static final Pattern PROPERTY_ENTRY = Pattern.compile("<([\\w.-]+)>([^<]+)</\\1>");

  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  /** Result of a validation pass. */
  @Value
  @AllArgsConstructor
  public static class ValidationResult {
    /** True when no missing deps were found (or no validation could run). */
    boolean passed;

    /** Empty when passed; one entry per missing dep otherwise. */
    List<DepIssue> missingDeps;

    /** Total number of explicit-version deps scanned. */
    int scannedCount;
  }

  /** A single hallucinated/missing dep. */
  @Value
  @AllArgsConstructor
  public static class DepIssue {
    String groupId;
    String artifactId;
    String version;
    String reason;

    public String shortRef() {
      return groupId + ":" + artifactId + ":" + version;
    }
  }

  /**
   * Validate the given workspace if it contains a pom.xml. Returns a passing result for non-Maven
   * projects (caller should ignore the validator on those).
   */
  public ValidationResult validate(Path workspaceRoot) {
    Path pom = workspaceRoot.resolve("pom.xml");
    if (!Files.isRegularFile(pom)) {
      return new ValidationResult(true, List.of(), 0);
    }
    String pomContent;
    try {
      pomContent = Files.readString(pom);
    } catch (IOException e) {
      log.warn("MavenDependencyValidator: failed to read pom.xml: {}", e.getMessage());
      return new ValidationResult(true, List.of(), 0);
    }

    Map<String, String> properties = parseProperties(pomContent);
    List<DepIssue> issues = new ArrayList<>();
    int scanned = 0;

    Matcher blockMatcher = DEPENDENCY_BLOCK.matcher(pomContent);
    while (blockMatcher.find()) {
      String block = blockMatcher.group(1);
      String groupId = extractFirst(block, GROUP_ID);
      String artifactId = extractFirst(block, ARTIFACT_ID);
      String version = extractFirst(block, VERSION);
      if (groupId == null || artifactId == null || version == null) {
        // Inherited (no explicit version) — skip; Maven will resolve from BOM.
        continue;
      }
      String resolvedVersion = resolveProperties(version, properties);
      if (resolvedVersion == null) {
        // Unresolvable property — skip rather than false-flag.
        continue;
      }
      scanned++;
      String reason = checkExists(groupId.trim(), artifactId.trim(), resolvedVersion.trim());
      if (reason != null) {
        issues.add(new DepIssue(groupId.trim(), artifactId.trim(), resolvedVersion.trim(), reason));
      }
    }

    return new ValidationResult(issues.isEmpty(), issues, scanned);
  }

  /**
   * Format a validation failure as a one-screen build-style error message that the worker /
   * reviewer can act on. Mirrors the tone of mvn's own "Could not resolve dependencies" wording so
   * LLMs trained on Maven output recognise it.
   */
  public String formatError(ValidationResult result) {
    if (result.isPassed()) return "";
    StringBuilder sb = new StringBuilder();
    int n = result.getMissingDeps().size();
    sb.append("[MavenDependencyValidator] Detected ");
    sb.append(n);
    sb.append(" hallucinated or missing dependenc");
    sb.append(n == 1 ? "y" : "ies");
    sb.append(" in pom.xml — these will fail any subsequent mvn invocation.\n\n");
    for (DepIssue d : result.getMissingDeps()) {
      sb.append("  ✗ ").append(d.shortRef()).append("\n");
      sb.append("    ").append(d.getReason()).append("\n");
      sb.append("    Hint: search https://search.maven.org/search?q=g:")
          .append(d.getGroupId())
          .append("+AND+a:")
          .append(d.getArtifactId())
          .append(" for actually-published versions.\n\n");
    }
    sb.append(
        "Fix the pom.xml (correct version OR remove the dep entirely if it's covered by a"
            + " starter / BOM) before retrying buildProject.\n");
    return sb.toString();
  }

  private static String extractFirst(String s, Pattern p) {
    Matcher m = p.matcher(s);
    return m.find() ? m.group(1) : null;
  }

  private static Map<String, String> parseProperties(String pomContent) {
    Map<String, String> out = new HashMap<>();
    Matcher block = PROPERTIES_BLOCK.matcher(pomContent);
    if (!block.find()) return out;
    Matcher entry = PROPERTY_ENTRY.matcher(block.group(1));
    while (entry.find()) {
      out.put(entry.group(1), entry.group(2));
    }
    return out;
  }

  private static String resolveProperties(String value, Map<String, String> properties) {
    Matcher m = PROPERTY_REF.matcher(value);
    if (!m.find()) return value;
    StringBuffer sb = new StringBuffer();
    do {
      String key = m.group(1);
      String resolved = properties.get(key);
      if (resolved == null) return null; // unresolvable — caller skips
      m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
    } while (m.find());
    m.appendTail(sb);
    return sb.toString();
  }

  /**
   * HEAD against Maven Central. Returns null when the artifact exists, a human-readable reason
   * string otherwise. Network failures count as "validator unavailable" → returns null so we don't
   * block legit builds.
   */
  private String checkExists(String groupId, String artifactId, String version) {
    String pathGroup = groupId.replace('.', '/');
    String url =
        MAVEN_CENTRAL_BASE
            + "/"
            + pathGroup
            + "/"
            + artifactId
            + "/"
            + version
            + "/"
            + artifactId
            + "-"
            + version
            + ".pom";
    try {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(Duration.ofSeconds(5))
              .method("HEAD", HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
      int sc = resp.statusCode();
      if (sc >= 200 && sc < 300) return null;
      if (sc == 404) {
        return "Maven Central returned 404 — this version does not exist (likely hallucinated).";
      }
      return "Maven Central returned HTTP " + sc + " for " + url;
    } catch (Exception e) {
      // Network glitch — don't block the build, just warn.
      log.warn(
          "MavenDependencyValidator: HEAD failed for {}:{}:{} — assuming valid. {}",
          groupId,
          artifactId,
          version,
          e.getMessage());
      return null;
    }
  }
}
