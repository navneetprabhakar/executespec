package com.navneet.executespec.smoketest;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;

import lombok.extern.slf4j.Slf4j;

/** Locates bounded, independently buildable roots without selecting unrelated monorepo modules. */
@Slf4j
public final class BuildRootDiscovery {

  private static final int MAX_DEPTH = 5;
  private static final Set<String> SKIPPED_DIRECTORIES =
      Set.of(".git", "node_modules", "target", "build", "dist", "out", "vendor");

  private BuildRootDiscovery() {}

  public record Selection(List<Path> roots, List<Path> candidates, boolean ambiguous) {
    public Selection {
      roots = List.copyOf(roots);
      candidates = List.copyOf(candidates);
    }
  }

  /**
   * Select manifest roots changed relative to their containing Git checkout. A single candidate is
   * safe even when Git evidence is unavailable; multiple unscoped candidates are ambiguous rather
   * than an instruction to build every sibling application.
   */
  public static Selection select(Path workspaceRoot) {
    if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
      return new Selection(List.of(), List.of(), false);
    }
    Path workspace = workspaceRoot.toAbsolutePath().normalize();
    List<Path> candidates = candidates(workspace);
    if (candidates.isEmpty()) return new Selection(List.of(), List.of(), false);
    if (candidates.size() == 1) return new Selection(candidates, candidates, false);

    Map<Path, Set<String>> changesByRepository = new HashMap<>();
    List<Path> changed = new ArrayList<>();
    for (Path candidate : candidates) {
      Path repository = containingGitRoot(workspace, candidate);
      if (repository == null) continue;
      Set<String> changes =
          changesByRepository.computeIfAbsent(repository, BuildRootDiscovery::changedPaths);
      String module = repository.relativize(candidate).toString().replace('\\', '/');
      boolean matches =
          module.isBlank()
              ? !changes.isEmpty()
              : changes.stream()
                  .anyMatch(path -> path.equals(module) || path.startsWith(module + "/"));
      if (matches) changed.add(candidate);
    }
    changed.sort(Comparator.comparing(workspace::relativize));
    if (!changed.isEmpty()) return new Selection(changed, candidates, false);
    return new Selection(List.of(), candidates, true);
  }

  private static List<Path> candidates(Path workspace) {
    List<Path> found = new ArrayList<>();
    try {
      Files.walkFileTree(
          workspace,
          Set.of(),
          MAX_DEPTH,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              if (!dir.equals(workspace)) {
                String name = dir.getFileName().toString();
                if (SKIPPED_DIRECTORIES.contains(name)
                    || name.startsWith(".")
                    || Files.isSymbolicLink(dir)) {
                  return FileVisitResult.SKIP_SUBTREE;
                }
              }
              if (hasManifest(dir)) {
                Path relative = workspace.relativize(dir);
                if (safeRelative(relative)) found.add(dir.toAbsolutePath().normalize());
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      log.warn("Build root discovery failed in {}: {}", workspace, e.getMessage());
    }
    found.sort(Comparator.comparing(workspace::relativize));
    return List.copyOf(found);
  }

  private static Path containingGitRoot(Path workspace, Path candidate) {
    Path current = candidate;
    while (current != null && current.startsWith(workspace)) {
      if (Files.exists(current.resolve(".git"))) return current;
      if (current.equals(workspace)) break;
      current = current.getParent();
    }
    return null;
  }

  private static Set<String> changedPaths(Path repository) {
    try (Git git = Git.open(repository.toFile())) {
      Status status = git.status().call();
      Set<String> paths = new HashSet<>();
      paths.addAll(status.getAdded());
      paths.addAll(status.getChanged());
      paths.addAll(status.getConflicting());
      paths.addAll(status.getMissing());
      paths.addAll(status.getModified());
      paths.addAll(status.getRemoved());
      paths.addAll(status.getUncommittedChanges());
      paths.addAll(status.getUntracked());
      // JGit's untracked-folder rollup can report tracked sibling module directories when one
      // nested file is new. Exact untracked paths already carry all selection evidence and avoid
      // widening a backend-only change into every build root in the repository.
      return Set.copyOf(paths);
    } catch (Exception e) {
      log.debug(
          "Git status unavailable for build-root selection in {}: {}", repository, e.getMessage());
      return Set.of();
    }
  }

  static boolean hasManifest(Path dir) {
    return exists(dir, "pom.xml")
        || exists(dir, "build.gradle")
        || exists(dir, "build.gradle.kts")
        || exists(dir, "Cargo.toml")
        || exists(dir, "go.mod")
        || exists(dir, "package.json")
        || exists(dir, "pyproject.toml")
        || exists(dir, "setup.py")
        || exists(dir, "requirements.txt")
        || exists(dir, ".executespec/bootstrap/python.marker")
        || exists(dir, ".executespec/bootstrap/node-js.marker");
  }

  private static boolean safeRelative(Path relative) {
    for (Path segment : relative) {
      if (!segment.toString().matches("[A-Za-z0-9._-]+")) return false;
    }
    return true;
  }

  private static boolean exists(Path dir, String name) {
    return Files.isRegularFile(dir.resolve(name));
  }
}
