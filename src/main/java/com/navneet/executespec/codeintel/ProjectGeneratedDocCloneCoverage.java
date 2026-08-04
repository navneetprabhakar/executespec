package com.navneet.executespec.codeintel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact repository-identity coverage for SETUP clone commands. */
final class ProjectGeneratedDocCloneCoverage {

  private static final Pattern FENCED_BLOCK =
      Pattern.compile("(?ms)^(`{3,}|~{3,})([^\\r\\n]*)\\R(.*?)^\\1\\s*$");
  private static final Pattern CLONE =
      Pattern.compile(
          "^git\\s+clone\\s+((?:https://(?:github\\.com|bitbucket\\.org)/|"
              + "git@(?:github\\.com|bitbucket\\.org):)[^\\s]+)(?:\\s+([A-Za-z0-9][A-Za-z0-9._-]*))?$");

  private ProjectGeneratedDocCloneCoverage() {}

  /**
   * A canonical clone URL is an exact repository identity for SETUP coverage. Parsing the
   * constrained clone grammar avoids accepting an attacker-prefixed repository path.
   */
  static boolean containsExactCloneTarget(String content, String expectedRepository) {
    Matcher blocks = FENCED_BLOCK.matcher(content);
    while (blocks.find()) {
      for (String raw : blocks.group(3).split("\\R")) {
        String command = normalizePrompt(raw.strip());
        Matcher clone = CLONE.matcher(command);
        if (!clone.matches()) continue;
        String repository =
            clone
                .group(1)
                .replaceFirst(
                    "(?i)^(?:https://(?:github\\.com|bitbucket\\.org)/|"
                        + "git@(?:github\\.com|bitbucket\\.org):)",
                    "")
                .replaceFirst("(?i)\\.git$", "");
        if (repository.equalsIgnoreCase(expectedRepository.strip())) return true;
      }
    }
    return false;
  }

  private static String normalizePrompt(String candidate) {
    String command = candidate.strip();
    return command.startsWith("$ ") || command.startsWith("> ")
        ? command.substring(2).strip()
        : command;
  }
}
