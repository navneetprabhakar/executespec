package com.navneet.executespec.codeintel;

import java.util.List;

import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;

/** Keeps provider-free architecture evidence byte-exact across model-generated artifacts. */
final class DeterministicArchitectureEvidence {

  static final String HTTP_BEGIN = "<!-- EXECUTESPEC_DETERMINISTIC_HTTP_SURFACE_V2_BEGIN -->";
  static final String HTTP_END = "<!-- EXECUTESPEC_DETERMINISTIC_HTTP_SURFACE_V2_END -->";

  private DeterministicArchitectureEvidence() {}

  static String appendFromCodemap(String generatedMarkdown, String codemapMarkdown) {
    String evidence = exactHttpBlock(codemapMarkdown);
    if (evidence == null) return generatedMarkdown;
    return stripHttpBlocks(generatedMarkdown).stripTrailing() + "\n\n" + evidence + "\n";
  }

  static String appendFromRepoDocs(
      String generatedMarkdown, List<RepoArchitectureDoc> docs, List<String> repoFullNames) {
    StringBuilder appendix = new StringBuilder();
    for (int i = 0; i < docs.size(); i++) {
      String evidence = exactHttpBlock(docs.get(i).getContent());
      if (evidence == null) continue;
      String name =
          i < repoFullNames.size() ? repoFullNames.get(i) : "repo-" + docs.get(i).getRepoId();
      appendix.append("### `").append(name).append("`\n\n").append(evidence).append("\n\n");
    }
    if (appendix.isEmpty()) return generatedMarkdown;
    return stripHttpBlocks(generatedMarkdown).stripTrailing()
        + "\n\n## Deterministic public HTTP surfaces by repository\n\n"
        + appendix.toString().stripTrailing()
        + "\n";
  }

  static boolean hasCurrentHttpEvidence(String markdown) {
    return exactHttpBlock(markdown) != null;
  }

  private static String exactHttpBlock(String markdown) {
    if (markdown == null) return null;
    int begin = markdown.lastIndexOf(HTTP_BEGIN);
    if (begin < 0) return null;
    int end = markdown.indexOf(HTTP_END, begin + HTTP_BEGIN.length());
    if (end < 0) return null;
    return markdown.substring(begin, end + HTTP_END.length()).strip();
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
}
