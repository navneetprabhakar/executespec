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

  static String appendFromRepoDocs(
      String generatedMarkdown, List<RepoArchitectureDoc> docs, List<String> repoFullNames) {
    StringBuilder appendix = new StringBuilder();
    for (int i = 0; i < docs.size(); i++) {
      String http = exactHttpBlock(docs.get(i).getContent());
      String facts = exactProjectFactBlock(docs.get(i).getContent());
      if (http == null && facts == null) continue;
      String name =
          i < repoFullNames.size() ? repoFullNames.get(i) : "repo-" + docs.get(i).getRepoId();
      appendix.append("### `").append(name).append("`\n\n");
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
    return exactProjectFactBlock(markdown) != null;
  }

  static boolean hasCurrentEvidence(String markdown) {
    return hasCurrentHttpEvidence(markdown) && hasCurrentProjectFactEvidence(markdown);
  }

  private static String exactHttpBlock(String markdown) {
    if (markdown == null) return null;
    int begin = markdown.lastIndexOf(HTTP_BEGIN);
    if (begin < 0) return null;
    int end = markdown.indexOf(HTTP_END, begin + HTTP_BEGIN.length());
    if (end < 0) return null;
    return markdown.substring(begin, end + HTTP_END.length()).strip();
  }

  static String exactProjectFactBlock(String markdown) {
    if (markdown == null) return null;
    int begin = markdown.lastIndexOf(DeterministicProjectFactEvidence.BEGIN);
    if (begin < 0) return null;
    int end =
        markdown.indexOf(
            DeterministicProjectFactEvidence.END,
            begin + DeterministicProjectFactEvidence.BEGIN.length());
    if (end < 0) return null;
    return markdown.substring(begin, end + DeterministicProjectFactEvidence.END.length()).strip();
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
    return stripProjectFactBlocks(stripHttpBlocks(markdown));
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
}
