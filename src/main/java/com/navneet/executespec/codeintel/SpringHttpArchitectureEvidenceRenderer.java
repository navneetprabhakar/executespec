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
    if (inventoryTruncated) return unavailable(evidence, "SOURCE_INVENTORY_TRUNCATED");
    List<String> candidates = files.stream().filter(this::isSource).sorted().toList();
    if (candidates.size() > MAX_SOURCE_FILES) {
      return unavailable(evidence, "SOURCE_FILE_LIMIT_EXCEEDED");
    }
    Map<String, String> snapshot = new LinkedHashMap<>();
    for (String path : candidates) {
      GitHubRepoService.RepoFileResult read;
      try {
        read = github.readRepoFile("https://github.com/" + repoFullName, path, sourceRevision, pat);
      } catch (Exception failure) {
        return unavailable(evidence, "SOURCE_READ_FAILED");
      }
      if (read == null || !read.successful()) {
        return unavailable(
            evidence,
            read == null || read.errorCode() == null ? "SOURCE_READ_FAILED" : read.errorCode());
      }
      snapshot.put(path, read.content());
    }
    try {
      renderAvailable(evidence, extractor.extract(snapshot));
    } catch (SpringHttpSurfaceExtractor.SurfaceExtractionException failure) {
      return unavailable(evidence, "SURFACE_EXTRACTION_UNAVAILABLE");
    }
    return finish(evidence);
  }

  private static StringBuilder header(String revision) {
    return new StringBuilder()
        .append(DeterministicArchitectureEvidence.HTTP_BEGIN)
        .append("\n## Deterministic public HTTP surface\n\n- Framework: `SPRING_MVC`\n")
        .append("- Source revision: `")
        .append(revision)
        .append("`\n");
  }

  private static void renderAvailable(StringBuilder evidence, SpringHttpSurfaceSnapshot surface) {
    evidence
        .append("- Status: `AVAILABLE`\n- Schema version: `")
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

  private boolean isSource(String path) {
    String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
    if (CodeMapGenerator.isSkippedPath(normalized)) return false;
    if (normalized.endsWith(".java")) return true;
    String name = normalized.substring(normalized.lastIndexOf('/') + 1);
    return name.startsWith("application")
        && (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml"));
  }

  private static String unavailable(StringBuilder evidence, String reason) {
    evidence.append("- Status: `UNAVAILABLE`\n- Reason: `").append(reason).append("`\n");
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
