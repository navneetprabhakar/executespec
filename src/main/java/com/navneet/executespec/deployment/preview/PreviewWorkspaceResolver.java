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

/** Resolves the application root inside a restored run workspace. */
@Component
class PreviewWorkspaceResolver {

  private final RunRepoSnapshotRepository snapshotRepository;
  private final com.navneet.executespec.integration.CorrectionWorkspaceTransaction generations;

  PreviewWorkspaceResolver(RunRepoSnapshotRepository snapshots) {
    this(snapshots, new com.navneet.executespec.integration.CorrectionWorkspaceTransaction());
  }

  @org.springframework.beans.factory.annotation.Autowired
  PreviewWorkspaceResolver(
      RunRepoSnapshotRepository snapshots,
      com.navneet.executespec.integration.CorrectionWorkspaceTransaction generations) {
    this.snapshotRepository = snapshots;
    this.generations = generations;
  }

  BoundWorkspace resolveBound(String runId, Path restoredRunRoot) {
    String before = generations.captureGeneration(restoredRunRoot);
    List<RunRepoSnapshot> snapshots = snapshotRepository.findByRunId(runId);
    Path application =
        snapshots.isEmpty() ? restoredRunRoot : resolveLinkedRepository(restoredRunRoot, snapshots);
    String applicationGeneration = generations.captureGeneration(application);
    Path staged = stage(runId + "-" + java.util.UUID.randomUUID(), restoredRunRoot, application);
    if (!applicationGeneration.equals(generations.captureGeneration(staged))
        || !before.equals(generations.captureGeneration(restoredRunRoot))) {
      deleteTree(staged);
      throw new PreviewException("Preview source changed while staging the build context.");
    }
    return new BoundWorkspace(staged, before);
  }

  boolean matchesGeneration(Path root, String generation) {
    if (generation == null) return false;
    return generation.equals(generations.captureGeneration(root));
  }

  void discard(Path workspace) {
    deleteTree(workspace);
  }

  record BoundWorkspace(Path workspace, String generation) {}

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
    RunRepoSnapshot snapshot = snapshots.getFirst();
    String directoryName = repositoryDirectoryName(fullName);
    try {
      Path nested = SafeWorkspacePath.resolveWithin(restoredRunRoot, "repos/" + directoryName);
      // repoId is the durable layout discriminator: project-linked snapshots always carry it;
      // standalone run snapshots deliberately do not. Directory names are customer-controlled and
      // cannot identify layout (a standalone repository may legitimately contain repos/<name>).
      if (snapshot.getRepoId() == null) {
        if (Files.isDirectory(restoredRunRoot.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
          return restoredRunRoot;
        }
        throw new PreviewException("Repository workspace is unavailable for preview: " + fullName);
      }
      // GCS archives omit every nested .git directory and reconstruct one synthetic repository at
      // the wrapper root. Directory layout, not nested Git metadata, is therefore authoritative.
      if (Files.isDirectory(nested, LinkOption.NOFOLLOW_LINKS)) return nested;
      throw new PreviewException("Repository workspace is unavailable for preview: " + fullName);
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
