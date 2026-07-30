package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

import com.navneet.executespec.security.SafeWorkspacePath;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Resolves the application root inside a restored run workspace. */
@Component
@RequiredArgsConstructor
class PreviewWorkspaceResolver {

  private final RunRepoSnapshotRepository snapshotRepository;

  Path resolve(String runId, Path restoredRunRoot) {
    List<RunRepoSnapshot> snapshots = snapshotRepository.findByRunId(runId);
    Path applicationRoot;
    if (snapshots.isEmpty()) {
      applicationRoot = restoredRunRoot;
    } else {
      applicationRoot = resolveLinkedRepository(restoredRunRoot, snapshots);
    }
    return stage(runId, restoredRunRoot, applicationRoot);
  }

  private static Path resolveLinkedRepository(
      Path restoredRunRoot, List<RunRepoSnapshot> snapshots) {
    if (snapshots.size() != 1) {
      throw new PreviewException(
          "Preview requires exactly one repository; this run contains "
              + snapshots.size()
              + ". Preview an individual repository instead.");
    }

    String fullName = snapshots.getFirst().getRepoFullName();
    String directoryName = repositoryDirectoryName(fullName);
    try {
      Path applicationRoot =
          SafeWorkspacePath.resolveWithin(restoredRunRoot, "repos/" + directoryName);
      if (!Files.isDirectory(applicationRoot, LinkOption.NOFOLLOW_LINKS)) {
        throw new PreviewException("Repository workspace is unavailable for preview: " + fullName);
      }
      return applicationRoot;
    } catch (IOException | SecurityException e) {
      throw new PreviewException("Repository workspace is unsafe for preview: " + fullName, e);
    }
  }

  private static Path stage(String runId, Path restoredRunRoot, Path applicationRoot) {
    String safeRunId;
    try {
      safeRunId = SafeWorkspacePath.requireSafeRelativePosixPath(runId);
    } catch (SecurityException unsafe) {
      throw new PreviewException("Run identity is unsafe for preview.", unsafe);
    }
    Path parent = restoredRunRoot.toAbsolutePath().normalize().getParent();
    if (parent == null) throw new PreviewException("Preview workspace has no safe parent.");
    Path target =
        parent.resolve("." + restoredRunRoot.getFileName() + "-preview-context").resolve(safeRunId);
    try {
      deleteTree(target);
      copyTree(applicationRoot.toRealPath(), target);
      return target;
    } catch (IOException | SecurityException failure) {
      deleteTree(target);
      throw new PreviewException("Could not stage an isolated preview build context.", failure);
    }
  }

  private static void copyTree(Path source, Path target) throws IOException {
    try (var paths = Files.walk(source)) {
      for (Path path : paths.toList()) {
        Path destination = target.resolve(source.relativize(path).toString());
        if (Files.isSymbolicLink(path)) {
          Path link = Files.readSymbolicLink(path);
          if (link.isAbsolute() || !path.getParent().resolve(link).normalize().startsWith(source)) {
            throw new SecurityException("preview source contains an escaping symbolic link");
          }
          Files.createDirectories(destination.getParent());
          Files.createSymbolicLink(destination, link);
        } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
          Files.createDirectories(destination);
        } else {
          Files.createDirectories(destination.getParent());
          Files.copy(
              path,
              destination,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.COPY_ATTRIBUTES,
              LinkOption.NOFOLLOW_LINKS);
        }
      }
    }
  }

  private static void deleteTree(Path root) {
    if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // The caller reports a staging failure if cleanup prevents the subsequent copy.
    }
  }

  private static String repositoryDirectoryName(String fullName) {
    if (fullName == null || fullName.isBlank()) {
      throw new PreviewException("Repository identity is missing for preview.");
    }
    int slash = fullName.lastIndexOf('/');
    String name = slash >= 0 ? fullName.substring(slash + 1) : fullName;
    try {
      return SafeWorkspacePath.requireSafeRelativePosixPath(name);
    } catch (SecurityException e) {
      throw new PreviewException("Repository identity is unsafe for preview: " + fullName, e);
    }
  }
}
