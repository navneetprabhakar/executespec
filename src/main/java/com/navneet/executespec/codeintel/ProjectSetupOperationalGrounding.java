package com.navneet.executespec.codeintel;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds a byte-stable SETUP document exclusively from exact-revision setup authority. */
final class ProjectSetupOperationalGrounding {

  private ProjectSetupOperationalGrounding() {}

  static boolean isCanonical(
      String markdown, String projectName, String evidence, Set<String> repositories) {
    if (markdown == null) return false;
    try {
      return markdown.equals(assemble(projectName, evidence, repositories));
    } catch (ProjectDocGeneratorService.InvalidGeneratedDocumentException invalidEvidence) {
      return false;
    }
  }

  static String assemble(String projectName, String evidence, Set<String> repositories) {
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
    if (!authority.valid()) {
      throw new ProjectDocGeneratorService.InvalidGeneratedDocumentException(
          "SETUP_EVIDENCE_INVALID", authority.error());
    }

    StringBuilder out = new StringBuilder();
    out.append("# Setting up ").append(safeText(projectName, "the project")).append("\n\n");
    appendPrerequisites(out, authority, orderedRepositories);
    appendClone(out, authority, orderedRepositories);
    appendEnvironment(out);
    appendFirstRun(out, authority, orderedRepositories);
    appendFullStack(out, authority, orderedRepositories);
    appendTroubleshooting(out);
    return out.toString().stripTrailing();
  }

  private static void appendPrerequisites(
      StringBuilder out,
      ProjectGeneratedDocValidator.SetupAuthority authority,
      List<String> repositories) {
    out.append("## Prerequisites\n\n");
    for (String repository : repositories) {
      out.append("For `").append(safeInline(repository)).append("`:\n\n");
      if (authority.hasPackageJson(repository)) {
        out.append("- Node.js");
        authority
            .nodeEngine(repository)
            .ifPresent(value -> out.append(" `").append(safeInline(value)).append("`"));
        out.append(", established by the exact-revision `package.json`.\n");
        if (projectedCommands(authority, repository).stream()
            .anyMatch(command -> command.toLowerCase(Locale.ROOT).startsWith("npm "))) {
          out.append("- npm, established by the exact projected npm commands.\n");
        }
      } else {
        out.append(
            "- No runtime or package manager is established by the supplied deterministic"
                + " evidence.\n");
      }
      out.append(
          "- No local service prerequisite is established by the supplied deterministic"
              + " evidence.\n\n");
    }
    if (repositories.isEmpty()) {
      out.append(
          "No repository prerequisite is established by the supplied deterministic evidence.\n\n");
    }
  }

  private static void appendClone(
      StringBuilder out,
      ProjectGeneratedDocValidator.SetupAuthority authority,
      List<String> repositories) {
    out.append("## Clone\n\n");
    for (String repository : repositories) {
      out.append("### `").append(safeInline(repository)).append("`\n\n");
      String scm =
          authority.scmByRepository().getOrDefault(repository.toLowerCase(Locale.ROOT), "GITHUB");
      String host = "BITBUCKET".equalsIgnoreCase(scm) ? "bitbucket.org" : "github.com";
      appendCommand(out, "git clone https://" + host + "/" + repository + ".git");
    }
  }

  private static void appendEnvironment(StringBuilder out) {
    out.append("## Environment variables\n\n")
        .append(
            "No environment variable is established by the supplied deterministic evidence.\n\n")
        .append("| var | repo | purpose | example |\n")
        .append("| --- | --- | --- | --- |\n\n");
  }

  private static void appendFirstRun(
      StringBuilder out,
      ProjectGeneratedDocValidator.SetupAuthority authority,
      List<String> repositories) {
    out.append("## First-run per repo\n\n");
    for (String repository : repositories) {
      List<String> commands =
          projectedCommands(authority, repository).stream()
              .sorted(
                  Comparator.comparingInt(ProjectSetupOperationalGrounding::commandRank)
                      .thenComparing(String::compareTo))
              .toList();
      out.append("### `")
          .append(safeInline(repository))
          .append("`\n\n")
          .append("Run commands from the repository root.\n\n");
      if (commands.stream().noneMatch(command -> commandRank(command) == 0)) {
        out.append(
            "Dependency installation is not established by the supplied deterministic"
                + " evidence.\n\n");
      }
      out.append(
          "Database migration is not established by the supplied deterministic evidence.\n\n");
      if (commands.isEmpty()) {
        out.append(
            "No test, check, or start command is established by the supplied deterministic"
                + " evidence.\n\n");
      } else {
        for (String command : commands) {
          out.append(commandLabel(command)).append(":\n\n");
          appendCommand(out, command);
        }
      }
    }
  }

  private static void appendFullStack(
      StringBuilder out,
      ProjectGeneratedDocValidator.SetupAuthority authority,
      List<String> repositories) {
    out.append("## Running the full stack locally\n\n");
    boolean emitted = false;
    for (String repository : repositories) {
      List<String> starts =
          projectedCommands(authority, repository).stream()
              .filter(ProjectSetupOperationalGrounding::isStartCommand)
              .sorted()
              .toList();
      for (String command : starts) {
        out.append("Evidenced start command for `").append(safeInline(repository)).append("`:\n\n");
        appendCommand(out, command);
        emitted = true;
      }
    }
    if (!emitted) {
      out.append(
          "No application start command is established by the supplied deterministic"
              + " evidence.\n\n");
    }
    out.append(
        "No multi-service startup order, port, health endpoint, or health-check command is"
            + " established by the supplied deterministic evidence.\n\n");
  }

  private static void appendTroubleshooting(StringBuilder out) {
    out.append("## Troubleshooting\n\n")
        .append(
            "No deterministic troubleshooting evidence was supplied. If an evidenced command fails,"
                + " run that exact command from the named repository root and inspect its emitted"
                + " error.\n");
  }

  private static boolean isStartCommand(String command) {
    String lower = command.toLowerCase(Locale.ROOT);
    return lower.equals("npm start")
        || lower.matches("npm run (?:dev|serve|start)")
        || lower.matches("(?:pnpm|yarn) (?:dev|start)")
        || lower.matches("make (?:run|start|dev|serve)");
  }

  private static Set<String> projectedCommands(
      ProjectGeneratedDocValidator.SetupAuthority authority, String repository) {
    if (repository == null) return Set.of();
    return Set.copyOf(
        authority
            .derivedCommandsByRepository()
            .getOrDefault(repository.strip().toLowerCase(Locale.ROOT), Set.of()));
  }

  private static int commandRank(String command) {
    String lower = command.toLowerCase(Locale.ROOT);
    if (lower.matches("(?:npm (?:ci|install)|pnpm install|yarn install|make install)")) return 0;
    if (lower.matches(".*(?:^|\\s|:)(?:check|lint)(?:\\s|$).*")) return 1;
    if (lower.matches(".*(?:^|\\s|:)(?:test|tests)(?:\\s|$).*")) return 2;
    if (isStartCommand(command)) return 3;
    return 4;
  }

  private static String commandLabel(String command) {
    return switch (commandRank(command)) {
      case 0 -> "Dependency installation";
      case 1 -> "Check";
      case 2 -> "Test";
      case 3 -> "Start";
      default -> "Evidenced command";
    };
  }

  private static void appendCommand(StringBuilder out, String command) {
    out.append("```bash\n").append(command).append("\n```\n\n");
  }

  private static String safeText(String value, String fallback) {
    if (value == null || value.isBlank()) return fallback;
    String safe = value.replaceAll("[\\r\\n\\p{Cntrl}]", " ").strip();
    return safe.isBlank() ? fallback : escapeHtml(safe);
  }

  private static String safeInline(String value) {
    return (value == null ? "" : value.replace("\r", " ").replace("\n", " "))
        .replace("&", "&amp;")
        .replace("`", "&#96;");
  }

  private static String escapeHtml(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
