package com.navneet.executespec.reviewer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.tool.FileSystemTool;

/**
 * Deterministically validates model-authored source citations before validation can publish them.
 */
final class AccuracyEvidenceCitationPolicy {

  private static final Pattern LOCATION =
      Pattern.compile("^([^\\r\\n\\[\\]]+):(\\d+)(?:-(\\d+))?$");
  private static final Pattern INLINE_LOCATION =
      Pattern.compile("\\[([^\\r\\n\\[\\]]+):(\\d+)(?:-(\\d+))?]$");
  private static final Pattern RANGE_HEADER = Pattern.compile("^\\[Lines (\\d+)-(\\d+) of (\\d+)]");
  private static final int MAX_CITATIONS = 64;
  private static final int MAX_CITATION_LINES = 200;
  private static final java.util.Set<String> EXCLUDED_SEGMENTS =
      java.util.Set.of(
          ".agent-context",
          ".executespec",
          ".git",
          "build",
          "coverage",
          "dist",
          "doc",
          "docs",
          "documentation",
          "log",
          "logs",
          "node_modules",
          "out",
          "target",
          "vendor");
  private static final java.util.Set<String> SOURCE_EXTENSIONS =
      java.util.Set.of(
          "c",
          "cc",
          "conf",
          "cpp",
          "cs",
          "css",
          "go",
          "gradle",
          "groovy",
          "h",
          "hpp",
          "html",
          "java",
          "js",
          "json",
          "jsx",
          "kt",
          "kts",
          "php",
          "properties",
          "py",
          "rb",
          "rs",
          "sass",
          "scala",
          "scss",
          "sh",
          "sql",
          "svelte",
          "swift",
          "toml",
          "ts",
          "tsx",
          "vue",
          "xml",
          "yaml",
          "yml");

  private AccuracyEvidenceCitationPolicy() {}

  static void validate(FileSystemTool files, String runId, AccuracyReport report) {
    validate(files, runId, report, java.util.Set.of());
  }

  static void validate(
      FileSystemTool files,
      String runId,
      AccuracyReport report,
      java.util.Set<String> requestedArtifacts) {
    if (report == null || files == null || runId == null || runId.isBlank()) {
      if (report != null) {
        report.setEvidenceLocations(List.of());
        report.setEvidenceStatus("UNAVAILABLE");
        report.setSpecEvidenceLocations(List.of());
        report.setEndpointEvidenceLocations(List.of());
        report.setTestEvidenceLocations(List.of());
        report.setSpecEvidenceStatus("UNAVAILABLE");
        report.setEndpointEvidenceStatus("UNAVAILABLE");
        report.setTestEvidenceStatus("UNAVAILABLE");
      }
      return;
    }
    LinkedHashSet<String> spec = candidates(report.getSpecEvidenceLocations());
    collectInline(spec, report.getImplementedRequirements());
    collectInline(spec, report.getMissingRequirements());
    collectInline(spec, report.getPartialRequirements());
    LinkedHashSet<String> endpoint = candidates(report.getEndpointEvidenceLocations());
    if (report.getEndpointCoverage() != null) {
      collectInline(endpoint, report.getEndpointCoverage().getImplemented());
      collectInline(endpoint, report.getEndpointCoverage().getMissing());
    }
    List<String> validSpec = validateCandidates(files, runId, spec, requestedArtifacts);
    List<String> validEndpoint = validateCandidates(files, runId, endpoint);
    List<String> validTest =
        validateCandidates(files, runId, candidates(report.getTestEvidenceLocations()));
    report.setSpecEvidenceLocations(validSpec);
    report.setEndpointEvidenceLocations(validEndpoint);
    report.setTestEvidenceLocations(validTest);
    report.setSpecEvidenceStatus(status(validSpec));
    report.setEndpointEvidenceStatus(status(validEndpoint));
    report.setTestEvidenceStatus(status(validTest));
    LinkedHashSet<String> all = new LinkedHashSet<>();
    all.addAll(validSpec);
    all.addAll(validEndpoint);
    all.addAll(validTest);
    report.setEvidenceLocations(List.copyOf(all));
    report.setEvidenceStatus(all.isEmpty() ? "UNCITED" : "CITED");
  }

  private static LinkedHashSet<String> candidates(List<String> supplied) {
    LinkedHashSet<String> candidates = new LinkedHashSet<>();
    if (supplied != null) candidates.addAll(supplied);
    return candidates;
  }

  private static List<String> validateCandidates(
      FileSystemTool files, String runId, LinkedHashSet<String> candidates) {
    return validateCandidates(files, runId, candidates, java.util.Set.of());
  }

  private static List<String> validateCandidates(
      FileSystemTool files,
      String runId,
      LinkedHashSet<String> candidates,
      java.util.Set<String> requestedArtifacts) {
    List<String> valid = new ArrayList<>();
    for (String candidate : candidates) {
      if (valid.size() >= MAX_CITATIONS) break;
      String canonical = validateOne(files, runId, candidate, requestedArtifacts);
      if (canonical != null) valid.add(canonical);
    }
    return List.copyOf(valid);
  }

  private static String status(List<String> valid) {
    return valid.isEmpty() ? "UNCITED" : "CITED";
  }

  private static void collectInline(LinkedHashSet<String> out, List<String> claims) {
    if (claims == null) return;
    for (String claim : claims) {
      if (claim == null) continue;
      Matcher matcher = INLINE_LOCATION.matcher(claim.trim());
      if (matcher.find()) {
        out.add(
            matcher.group(1)
                + ":"
                + matcher.group(2)
                + (matcher.group(3) == null ? "" : "-" + matcher.group(3)));
      }
    }
  }

  private static String validateOne(
      FileSystemTool files, String runId, String raw, java.util.Set<String> requestedArtifacts) {
    if (raw == null) return null;
    String candidate = raw.trim().replace('\\', '/');
    Matcher matcher = LOCATION.matcher(candidate);
    if (!matcher.matches()) return null;
    String path = matcher.group(1).trim();
    if (path.isBlank()
        || path.startsWith("/")
        || path.contains("../")
        || path.equals("..")
        || !(eligibleSourcePath(path) || eligibleRequestedArtifact(path, requestedArtifacts))) {
      return null;
    }
    int start;
    int end;
    try {
      start = Integer.parseInt(matcher.group(2));
      end = matcher.group(3) == null ? start : Integer.parseInt(matcher.group(3));
    } catch (NumberFormatException invalid) {
      return null;
    }
    if (start < 1 || end < start || end - start + 1 > MAX_CITATION_LINES) return null;
    String content = files.readFileRange(runId, path, start, end);
    if (content == null) return null;
    Matcher header = RANGE_HEADER.matcher(content);
    if (!header.find()
        || Integer.parseInt(header.group(1)) != start
        || Integer.parseInt(header.group(2)) != end
        || end > Integer.parseInt(header.group(3))) return null;
    return path + ":" + start + (end == start ? "" : "-" + end);
  }

  private static boolean eligibleRequestedArtifact(String path, java.util.Set<String> requested) {
    if (!requested.contains(path)) return false;
    for (String segment : path.split("/")) {
      if (EXCLUDED_SEGMENTS.contains(segment.toLowerCase(java.util.Locale.ROOT))) return false;
    }
    String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
    return !name.startsWith(".env") && name.matches(".*\\.(txt|csv|tsv)");
  }

  private static boolean eligibleSourcePath(String path) {
    String normalized = path.replace('\\', '/');
    String[] segments = normalized.split("/");
    for (String segment : segments) {
      if (EXCLUDED_SEGMENTS.contains(segment.toLowerCase(java.util.Locale.ROOT))) return false;
    }
    String file = segments.length == 0 ? "" : segments[segments.length - 1];
    String lower = file.toLowerCase(java.util.Locale.ROOT);
    if (lower.startsWith(".env") || lower.endsWith(".log") || lower.endsWith(".md")) return false;
    int dot = lower.lastIndexOf('.');
    if (dot >= 0 && SOURCE_EXTENSIONS.contains(lower.substring(dot + 1))) return true;
    return lower.equals("dockerfile")
        || lower.equals("makefile")
        || lower.equals("gemfile")
        || lower.equals("rakefile")
        || lower.equals("go.mod")
        || lower.startsWith("requirements") && lower.endsWith(".txt");
  }
}
