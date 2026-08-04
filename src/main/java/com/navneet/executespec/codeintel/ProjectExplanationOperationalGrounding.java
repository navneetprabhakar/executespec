package com.navneet.executespec.codeintel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adds byte-stable local-development guidance derived only from typed setup evidence. */
final class ProjectExplanationOperationalGrounding {

  static final String WORKFLOW_HEADING = "## Verified local workflow";
  static final String TROUBLESHOOTING_HEADING = "## Troubleshooting";
  private static final Pattern H2 = Pattern.compile("(?m)^##\\s+.+$");

  private ProjectExplanationOperationalGrounding() {}

  static boolean isCanonical(String markdown, String evidence, Set<String> repositories) {
    return markdown != null && markdown.equals(append(markdown, evidence, repositories));
  }

  static String append(String markdown, String evidence, Set<String> repositories) {
    String prose =
        removeSection(removeSection(markdown, WORKFLOW_HEADING), TROUBLESHOOTING_HEADING);
    List<String> orderedRepositories =
        repositories == null
            ? List.of()
            : repositories.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    ProjectGeneratedDocValidator.SetupAuthority authority =
        ProjectGeneratedDocValidator.decodedSetupSources(evidence, Set.copyOf(orderedRepositories));

    StringBuilder out = new StringBuilder(prose.stripTrailing());
    out.append("\n\n").append(WORKFLOW_HEADING).append("\n\n");
    if (!authority.valid() || authority.sources().isEmpty() || orderedRepositories.isEmpty()) {
      out.append(
          "No install, test, run, or runtime prerequisite is established by the supplied"
              + " deterministic setup evidence.\n");
    } else {
      for (String repository : orderedRepositories) {
        appendRepository(out, authority, repository);
      }
    }

    out.append("\n").append(TROUBLESHOOTING_HEADING).append("\n\n");
    if (!authority.valid() || authority.sources().isEmpty() || orderedRepositories.isEmpty()) {
      out.append(
          "No repository-specific troubleshooting remedy is established by the supplied"
              + " deterministic evidence. Inspect the exact revision and its setup sources before"
              + " choosing a command.\n");
    } else {
      boolean node = orderedRepositories.stream().anyMatch(authority::hasPackageJson);
      if (node) {
        out.append(
            "- If a Node.js or package-manager executable is unavailable, confirm that Node.js is"
                + " installed and satisfies the evidenced `package.json` engine constraint when"
                + " one is listed below.\n");
      }
      out.append(
          "- If an evidenced command fails, run that exact command from the named repository root"
              + " and inspect its emitted error. No more specific remedy is established by the"
              + " supplied deterministic evidence.\n");
    }
    return out.toString().stripTrailing();
  }

  private static void appendRepository(
      StringBuilder out, ProjectGeneratedDocValidator.SetupAuthority authority, String repository) {
    out.append("### `").append(safe(repository)).append("`\n\n");
    if (authority.hasPackageJson(repository)) {
      var nodeEngine = authority.nodeEngine(repository);
      out.append("- Prerequisite: Node.js");
      nodeEngine.ifPresent(engine -> out.append(" `").append(safe(engine)).append("`"));
      out.append("; derived from the exact-revision `package.json` setup evidence");
      if (nodeEngine.isEmpty()) out.append(", which does not declare a Node.js engine version");
      out.append(".\n");
    } else {
      out.append("- Prerequisite: not established by the supplied deterministic setup evidence.\n");
    }

    List<String> commands =
        authority.evidencedCommands(repository).stream()
            .sorted(
                Comparator.comparingInt(ProjectExplanationOperationalGrounding::commandRank)
                    .thenComparing(String::compareTo))
            .toList();
    appendCommands(
        out, "Install", commands.stream().filter(command -> commandRank(command) == 0).toList());
    appendCommands(
        out, "Test", commands.stream().filter(command -> commandRank(command) == 1).toList());
    appendCommands(
        out, "Run", commands.stream().filter(command -> commandRank(command) == 2).toList());
  }

  private static void appendCommands(StringBuilder out, String label, List<String> commands) {
    if (commands.isEmpty()) {
      out.append("- ")
          .append(label)
          .append(": not established by the supplied deterministic setup evidence.\n");
      return;
    }
    out.append("- ").append(label).append(": ");
    List<String> escaped = new ArrayList<>();
    for (String command : commands) escaped.add("`" + safe(command) + "`");
    out.append(String.join(", ", escaped)).append(".\n");
  }

  private static int commandRank(String command) {
    String lower = command.toLowerCase(Locale.ROOT);
    if (lower.matches(
        "(?:npm (?:ci|install)|pnpm install|yarn install|pip3? install .+|"
            + "python3? -m pip install .+|bundle install|cargo fetch|go mod download|mvnw? install|"
            + "gradlew? build)")) return 0;
    if (lower.matches(".*(?:^|\\s|:)(?:test|tests|pytest)(?:\\s|$).*")) return 1;
    if (lower.matches(
        "(?:npm start|npm run (?:dev|serve|start)|pnpm (?:dev|start)|"
            + "yarn (?:dev|start)|make (?:run|start|dev|serve)).*")) return 2;
    return 3;
  }

  private static String removeSection(String markdown, String heading) {
    if (markdown == null || markdown.isBlank()) return "";
    Pattern target =
        Pattern.compile("(?im)^" + Pattern.quote(heading) + "\\s*$", Pattern.CASE_INSENSITIVE);
    String cleaned = markdown;
    while (true) {
      Matcher start = target.matcher(cleaned);
      if (!start.find()) return cleaned;
      Matcher next = H2.matcher(cleaned);
      next.region(start.end(), cleaned.length());
      int end = next.find() ? next.start() : cleaned.length();
      cleaned = cleaned.substring(0, start.start()) + cleaned.substring(end);
    }
  }

  private static String safe(String value) {
    return value.replace("`", "&#96;").replace("\r", " ").replace("\n", " ");
  }
}
