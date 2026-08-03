package com.navneet.executespec.codeintel;

import java.util.List;

import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;

/** Keeps provider-free architecture evidence byte-exact across model-generated artifacts. */
final class DeterministicArchitectureEvidence {

  static final String HTTP_BEGIN = "<!-- EXECUTESPEC_DETERMINISTIC_HTTP_SURFACE_V2_BEGIN -->";
  static final String HTTP_END = "<!-- EXECUTESPEC_DETERMINISTIC_HTTP_SURFACE_V2_END -->";

  private DeterministicArchitectureEvidence() {}

  static String appendFromCodemap(String generatedMarkdown, String codemapMarkdown) {
    String http = exactHttpBlock(codemapMarkdown);
    String facts = exactProjectFactBlock(codemapMarkdown);
    if (http == null && facts == null) return generatedMarkdown;
    StringBuilder appendix =
        new StringBuilder(stripEvidenceBlocks(generatedMarkdown).stripTrailing());
    if (http != null) appendix.append("\n\n").append(http);
    if (facts != null) appendix.append("\n\n").append(facts);
    return appendix.append('\n').toString();
  }

  static String appendFromCodemap(
      String generatedMarkdown,
      String codemapMarkdown,
      String expectedRepository,
      String expectedRevision) {
    String http = exactHttpBlock(codemapMarkdown);
    String facts = exactProjectFactBlock(codemapMarkdown, expectedRepository, expectedRevision);
    if (http != null && !http.contains("- Source revision: `" + expectedRevision + "`"))
      http = null;
    if (facts == null) {
      throw new IllegalStateException(
          "Codemap deterministic evidence is not uniquely bound to its repository revision");
    }
    StringBuilder appendix =
        new StringBuilder(stripEvidenceBlocks(generatedMarkdown).stripTrailing());
    if (http != null) appendix.append("\n\n").append(http);
    appendix.append("\n\n").append(facts);
    return appendix.append('\n').toString();
  }

  static String appendFromRepoDocs(
      String generatedMarkdown, List<RepoArchitectureDoc> docs, List<String> repoFullNames) {
    StringBuilder appendix = new StringBuilder();
    for (int i = 0; i < docs.size(); i++) {
      String http = exactHttpBlock(docs.get(i).getContent());
      String facts = exactProjectFactBlock(docs.get(i).getContent());
      if (http == null && facts == null) continue;
      String name =
          i < repoFullNames.size() ? repoFullNames.get(i) : "repo-" + docs.get(i).getRepoId();
      appendix.append("### `").append(safeMarkdown(name)).append("`\n\n");
      if (http != null) appendix.append(http).append("\n\n");
      if (facts != null) appendix.append(facts).append("\n\n");
    }
    if (appendix.isEmpty()) return generatedMarkdown;
    return stripEvidenceBlocks(generatedMarkdown).stripTrailing()
        + "\n\n## Deterministic repository evidence\n\n"
        + appendix.toString().stripTrailing()
        + "\n";
  }

  static boolean hasCurrentHttpEvidence(String markdown) {
    return exactHttpBlock(markdown) != null;
  }

  static boolean hasCurrentProjectFactEvidence(String markdown) {
    String block = exactProjectFactBlock(markdown);
    return block != null
        && block.contains("- Evidence-Format: `EXECUTESPEC_PROJECT_FACTS_V2`")
        && uniqueBlock(
                block,
                DeterministicProjectFactEvidence.ROUTES_BEGIN,
                DeterministicProjectFactEvidence.ROUTES_END)
            != null;
  }

  static boolean hasCurrentEvidence(String markdown) {
    String setup = exactSetupSourceBlock(markdown);
    return hasCurrentHttpEvidence(markdown)
        && hasCurrentProjectFactEvidence(markdown)
        && setup != null
        && setup.contains("- Evidence-Format: EXECUTESPEC_SETUP_SOURCES_V2");
  }

  private static String exactHttpBlock(String markdown) {
    return uniqueBlock(markdown, HTTP_BEGIN, HTTP_END);
  }

  static String exactProjectFactBlock(String markdown) {
    return uniqueBlock(
        markdown, DeterministicProjectFactEvidence.BEGIN, DeterministicProjectFactEvidence.END);
  }

  static String exactProjectFactBlock(
      String markdown, String expectedRepository, String expectedRevision) {
    String block = exactProjectFactBlock(markdown);
    if (block == null
        || !block.contains("- Evidence-Format: `EXECUTESPEC_PROJECT_FACTS_V2`")
        || uniqueBlock(
                block,
                DeterministicProjectFactEvidence.ROUTES_BEGIN,
                DeterministicProjectFactEvidence.ROUTES_END)
            == null
        || !block.contains("- Repository: `" + expectedRepository + "`")
        || !block.contains("- Source revision: `" + expectedRevision + "`")) {
      return null;
    }
    return block;
  }

  static String exactSetupSourceBlock(
      String markdown, String expectedRepository, String expectedRevision) {
    String block = exactSetupSourceBlock(markdown);
    if (block == null) return null;
    String repository =
        java.util.Base64.getEncoder()
            .encodeToString(expectedRepository.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if (!block.contains("- Evidence-Format: EXECUTESPEC_SETUP_SOURCES_V2")
        || !block.contains("- Repository-Base64: " + repository + "\n")
        || !block.contains("- Source-Revision: " + expectedRevision + "\n")) {
      return null;
    }
    return block;
  }

  private static String exactSetupSourceBlock(String markdown) {
    return uniqueBlock(
        markdown,
        DeterministicProjectFactEvidence.SETUP_BEGIN,
        DeterministicProjectFactEvidence.SETUP_END);
  }

  private static String stripHttpBlocks(String markdown) {
    if (markdown == null || markdown.isBlank()) return "";
    String remaining = markdown;
    while (true) {
      int begin = remaining.indexOf(HTTP_BEGIN);
      if (begin < 0) return remaining;
      int end = remaining.indexOf(HTTP_END, begin + HTTP_BEGIN.length());
      if (end < 0) return remaining.substring(0, begin);
      remaining = remaining.substring(0, begin) + remaining.substring(end + HTTP_END.length());
    }
  }

  private static String stripEvidenceBlocks(String markdown) {
    return stripSetupSourceBlocks(stripProjectFactBlocks(stripHttpBlocks(markdown)));
  }

  static String stripProjectFactBlocks(String markdown) {
    if (markdown == null || markdown.isBlank()) return "";
    String remaining = markdown;
    while (true) {
      int begin = remaining.indexOf(DeterministicProjectFactEvidence.BEGIN);
      if (begin < 0) return remaining;
      int end =
          remaining.indexOf(
              DeterministicProjectFactEvidence.END,
              begin + DeterministicProjectFactEvidence.BEGIN.length());
      if (end < 0) return remaining.substring(0, begin);
      remaining =
          remaining.substring(0, begin)
              + remaining.substring(end + DeterministicProjectFactEvidence.END.length());
    }
  }

  static String stripSetupSourceBlocks(String markdown) {
    return stripBlocks(
        markdown,
        DeterministicProjectFactEvidence.SETUP_BEGIN,
        DeterministicProjectFactEvidence.SETUP_END);
  }

  private static String uniqueBlock(String markdown, String beginMarker, String endMarker) {
    if (markdown == null) return null;
    int begin = markdown.indexOf(beginMarker);
    if (begin < 0 || markdown.indexOf(beginMarker, begin + beginMarker.length()) >= 0) return null;
    int end = markdown.indexOf(endMarker, begin + beginMarker.length());
    if (end < 0 || markdown.indexOf(endMarker, end + endMarker.length()) >= 0) return null;
    if (markdown.indexOf(endMarker) < begin) return null;
    return markdown.substring(begin, end + endMarker.length()).strip();
  }

  private static String stripBlocks(String markdown, String beginMarker, String endMarker) {
    if (markdown == null || markdown.isBlank()) return "";
    String remaining = markdown;
    while (true) {
      int begin = remaining.indexOf(beginMarker);
      if (begin < 0) return remaining;
      int end = remaining.indexOf(endMarker, begin + beginMarker.length());
      if (end < 0) return remaining.substring(0, begin);
      remaining = remaining.substring(0, begin) + remaining.substring(end + endMarker.length());
    }
  }

  private static String safeMarkdown(String value) {
    return (value == null ? "" : value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("`", "&#96;")
        .replace("\r", "&#13;")
        .replace("\n", "&#10;");
  }
}
