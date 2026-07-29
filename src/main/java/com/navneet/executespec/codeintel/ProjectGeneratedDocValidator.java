package com.navneet.executespec.codeintel;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;

/** Deterministic minimum-quality gate for provider-generated project documents. */
final class ProjectGeneratedDocValidator {

  private static final int MIN_NON_WHITESPACE_CHARACTERS = 400;

  private ProjectGeneratedDocValidator() {}

  static Optional<String> validate(DocType type, String content, Set<String> expectedRepositories) {
    return validate(type, content, expectedRepositories, "");
  }

  static Optional<String> validate(
      DocType type, String content, Set<String> expectedRepositories, String codemapEvidence) {
    if (content == null || content.isBlank()) return Optional.of("empty output");
    long meaningful = content.chars().filter(c -> !Character.isWhitespace(c)).count();
    if (meaningful < MIN_NON_WHITESPACE_CHARACTERS) return Optional.of("output is too short");
    if ((count(content, "```") & 1) != 0) return Optional.of("unbalanced fenced code block");

    String normalized = content.toLowerCase(Locale.ROOT);
    List<String> required =
        switch (type) {
          case EXPLANATION ->
              List.of(
                  "# ",
                  "## repositories",
                  "## high-level architecture",
                  "## key data flows",
                  "## where to contribute");
          case SETUP ->
              List.of(
                  "# setting up",
                  "## prerequisites",
                  "## clone",
                  "## environment variables",
                  "## first-run per repo",
                  "## running the full stack locally",
                  "## troubleshooting");
        };
    Optional<String> missingHeading =
        required.stream()
            .filter(requiredHeading -> !hasHeading(normalized, requiredHeading))
            .findFirst()
            .map(missing -> "missing required heading " + missing.trim());
    if (missingHeading.isPresent()) return missingHeading;

    for (String heading : required) {
      if (!heading.startsWith("## ")) continue;
      if (sectionBodyMeaningful(normalized, heading) < 20) {
        return Optional.of("required section has no meaningful content " + heading.trim());
      }
    }

    if (expectedRepositories != null) {
      for (String repo : expectedRepositories) {
        if (repo != null
            && !repo.isBlank()
            && !normalized.contains(repo.toLowerCase(Locale.ROOT))) {
          return Optional.of("missing linked repository " + repo);
        }
      }
    }

    if (type == DocType.SETUP) {
      String clone = section(normalized, "## clone");
      if (!clone.contains("git clone") || count(clone, "```") < 2) {
        return Optional.of("clone commands must be present in a fenced block");
      }
    }
    if (type == DocType.EXPLANATION) {
      Optional<String> unsupported =
          GeneratedSignatureGrounding.unsupportedBehaviorClaim(content, codemapEvidence);
      if (unsupported.isPresent()) return unsupported;
    }
    return Optional.empty();
  }

  private static boolean hasHeading(String normalized, String heading) {
    if ("# ".equals(heading)) return Pattern.compile("(?m)^#\\s+\\S").matcher(normalized).find();
    if (heading.startsWith("# ")) {
      return Pattern.compile("(?m)^" + Pattern.quote(heading) + "(?:\\s+.+)?$")
          .matcher(normalized)
          .find();
    }
    return Pattern.compile("(?m)^" + Pattern.quote(heading) + "\\s*$").matcher(normalized).find();
  }

  private static int sectionBodyMeaningful(String normalized, String heading) {
    return (int)
        section(normalized, heading).chars().filter(c -> !Character.isWhitespace(c)).count();
  }

  private static String section(String normalized, String heading) {
    Matcher start = Pattern.compile("(?m)^" + Pattern.quote(heading) + "\\s*$").matcher(normalized);
    if (!start.find()) return "";
    int bodyStart = start.end();
    Matcher next = Pattern.compile("(?m)^#{1,2}\\s+").matcher(normalized);
    next.region(bodyStart, normalized.length());
    int bodyEnd = next.find() ? next.start() : normalized.length();
    return normalized.substring(bodyStart, bodyEnd);
  }

  private static int count(String source, String needle) {
    int count = 0;
    int cursor = 0;
    while ((cursor = source.indexOf(needle, cursor)) >= 0) {
      count++;
      cursor += needle.length();
    }
    return count;
  }
}
