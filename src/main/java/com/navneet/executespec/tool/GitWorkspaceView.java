package com.navneet.executespec.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Resolves the real Git working trees inside a run workspace. */
final class GitWorkspaceView {

  private GitWorkspaceView() {}

  record Repository(Path root, String workspacePrefix) {}

  static List<Repository> repositories(Path workspaceRoot) throws IOException {
    Path reposRoot = workspaceRoot.resolve("repos");
    if (Files.isDirectory(reposRoot)) {
      try (var children = Files.list(reposRoot)) {
        List<Repository> nested =
            children
                .filter(Files::isDirectory)
                .filter(path -> Files.exists(path.resolve(".git")))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .map(
                    path ->
                        new Repository(
                            path, "repos/" + path.getFileName().toString().replace('\\', '/')))
                .toList();
        if (!nested.isEmpty()) return nested;
      }
    }
    return List.of(new Repository(workspaceRoot, ""));
  }

  static String section(Repository repository, String body) {
    if (repository.workspacePrefix().isBlank()) return body;
    return "## " + repository.workspacePrefix() + "\n" + body;
  }

  static String repositoryRelativePath(Repository repository, String workspaceRelativePath) {
    if (workspaceRelativePath == null || workspaceRelativePath.isBlank()) return "";
    String normalized = workspaceRelativePath.replace('\\', '/');
    String prefix = repository.workspacePrefix();
    if (prefix.isBlank()) return normalized;
    if (normalized.equals(prefix)) return "";
    return normalized.startsWith(prefix + "/") ? normalized.substring(prefix.length() + 1) : null;
  }
}
