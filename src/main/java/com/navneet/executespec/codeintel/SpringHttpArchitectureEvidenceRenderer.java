package com.navneet.executespec.codeintel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceSnapshot;
import com.navneet.executespec.repo.GitHubRepoService;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Builds the exact-SHA Spring HTTP evidence appendix used by generated architecture artifacts. */
@Component
@RequiredArgsConstructor
class SpringHttpArchitectureEvidenceRenderer {

  static final int MAX_SOURCE_FILES = 120;

  private final GitHubRepoService github;
  private final SpringHttpSurfaceExtractor extractor;

  String render(
      String repoFullName,
      String sourceRevision,
      List<String> files,
      String pat,
      boolean inventoryTruncated) {
    StringBuilder evidence = header(sourceRevision);
    Map<String, String> snapshot = new LinkedHashMap<>();
    SpringHttpSurfaceSnapshot surface = null;
    String http;
    if (inventoryTruncated) {
      http = unavailable(evidence, "SOURCE_INVENTORY_TRUNCATED");
      return http + renderFacts(repoFullName, sourceRevision, files, pat, true, snapshot, null);
    }
    List<String> candidates = files.stream().filter(this::isSource).sorted().toList();
    if (candidates.size() > MAX_SOURCE_FILES) {
      http = unavailable(evidence, "SOURCE_FILE_LIMIT_EXCEEDED");
      return http + renderFacts(repoFullName, sourceRevision, files, pat, false, snapshot, null);
    }
    for (String path : candidates) {
      GitHubRepoService.RepoFileResult read;
      try {
        read = github.readRepoFile("https://github.com/" + repoFullName, path, sourceRevision, pat);
      } catch (Exception failure) {
        http = unavailable(evidence, "SOURCE_READ_FAILED");
        return http + renderFacts(repoFullName, sourceRevision, files, pat, false, snapshot, null);
      }
      if (read == null || !read.successful()) {
        http =
            unavailable(
                evidence,
                read == null || read.errorCode() == null ? "SOURCE_READ_FAILED" : read.errorCode());
        return http + renderFacts(repoFullName, sourceRevision, files, pat, false, snapshot, null);
      }
      snapshot.put(path, read.content());
    }
    try {
      surface = extractor.extract(snapshot);
      if (surface.hasDeterministicSpringMvcEvidence()) {
        renderAvailable(evidence, surface);
      } else {
        renderNotApplicable(evidence, surface);
      }
    } catch (SpringHttpSurfaceExtractor.SurfaceExtractionException failure) {
      http = unavailable(evidence, "SURFACE_EXTRACTION_UNAVAILABLE");
      return http + renderFacts(repoFullName, sourceRevision, files, pat, false, snapshot, null);
    }
    return finish(evidence)
        + renderFacts(repoFullName, sourceRevision, files, pat, false, snapshot, surface);
  }

  private String renderFacts(
      String repoFullName,
      String sourceRevision,
      List<String> files,
      String pat,
      boolean inventoryTruncated,
      Map<String, String> snapshot,
      SpringHttpSurfaceSnapshot surface) {
    return DeterministicProjectFactEvidence.render(
        repoFullName, sourceRevision, files, pat, inventoryTruncated, github, snapshot, surface);
  }

  private static StringBuilder header(String revision) {
    return new StringBuilder()
        .append(DeterministicArchitectureEvidence.HTTP_BEGIN)
        .append("\n## Deterministic public HTTP surface\n\n- Source revision: `")
        .append(revision)
        .append("`\n");
  }

  private static void renderAvailable(StringBuilder evidence, SpringHttpSurfaceSnapshot surface) {
    evidence
        .append("- Framework: `SPRING_MVC`\n- Status: `AVAILABLE`\n- Schema version: `")
        .append(surface.schemaVersion())
        .append("`\n- Evidence digest: `")
        .append(surface.digest())
        .append("`\n\n### Configuration provenance\n\n");
    if (surface.configurationSources().isEmpty()) {
      evidence.append("- No explicit `server.servlet.context-path`; canonical default is `/`.\n");
    } else {
      surface
          .configurationSources()
          .forEach(
              source ->
                  evidence
                      .append("- `")
                      .append(source.property())
                      .append('=')
                      .append(source.value())
                      .append("` — `")
                      .append(source.source())
                      .append("`\n"));
    }
    evidence.append("\n### Exact routes\n\n");
    if (surface.routes().isEmpty()) {
      evidence.append("- No literal Spring MVC routes were discovered.\n");
    } else {
      surface
          .routes()
          .forEach(
              route ->
                  evidence
                      .append("- `")
                      .append(route.method())
                      .append(' ')
                      .append(route.path())
                      .append("` — `")
                      .append(route.source())
                      .append("`\n"));
    }
  }

  private static void renderNotApplicable(
      StringBuilder evidence, SpringHttpSurfaceSnapshot surface) {
    evidence
        .append("- Framework: `NOT_ESTABLISHED`\n")
        .append("- Status: `NOT_APPLICABLE`\n")
        .append("- Reason: `NO_DETERMINISTIC_SPRING_MVC_EVIDENCE`\n")
        .append("- Schema version: `")
        .append(surface.schemaVersion())
        .append("`\n- Evidence digest: `")
        .append(surface.digest())
        .append("`\n\nNo Spring MVC framework, context path, or route claim is established.\n");
  }

  private boolean isSource(String path) {
    String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
    if (CodeMapGenerator.isSkippedPath(normalized)) return false;
    if (normalized.endsWith(".java")) return true;
    String name = normalized.substring(normalized.lastIndexOf('/') + 1);
    return name.startsWith("application")
        && (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml"));
  }

  private static String unavailable(StringBuilder evidence, String reason) {
    evidence
        .append("- Framework: `NOT_ESTABLISHED`\n")
        .append("- Status: `UNAVAILABLE`\n- Reason: `")
        .append(reason)
        .append("`\n");
    evidence.append(
        "\nExact Spring method/path claims must not be inferred from this unavailable evidence.\n");
    return finish(evidence);
  }

  private static String finish(StringBuilder evidence) {
    return evidence
        .append('\n')
        .append(DeterministicArchitectureEvidence.HTTP_END)
        .append("\n\n")
        .toString();
  }
}
