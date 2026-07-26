package com.navneet.executespec.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import com.navneet.executespec.security.SafeWorkspacePath;

/**
 * Fail-closed Linux bind-mount write admission for the strict runtime.
 *
 * <p>Docker applies host inode permissions to bind mounts. Running the container as a fixed
 * non-root uid is therefore not sufficient: a root-owned {@code 0755/0644} workspace looks writable
 * in Docker Desktop but is read-only to uid 65532 on the GCE Linux host. This check proves every
 * existing non-symlink directory and file is writable by the configured runtime identity before the
 * mount is admitted. It never chmods or chowns customer data.
 */
final class SandboxWorkspaceOwnership {

  private static final int OWNER_WRITE = 0200;
  private static final int OWNER_EXECUTE = 0100;
  private static final int GROUP_WRITE = 0020;
  private static final int GROUP_EXECUTE = 0010;
  private static final int OTHER_WRITE = 0002;
  private static final int OTHER_EXECUTE = 0001;

  private SandboxWorkspaceOwnership() {}

  static void requireWritableTree(Path workspace, int runtimeUid, int runtimeGid) {
    if (runtimeUid <= 0 || runtimeGid <= 0) {
      throw new IllegalArgumentException("sandbox runtime uid/gid must be non-root");
    }
    try {
      SafeWorkspacePath.requireWithin(workspace, workspace);
      try (Stream<Path> entries = Files.walk(workspace)) {
        Path incompatible =
            entries
                // Git object files are intentionally immutable (often 0444). Commands need the
                // repository metadata readable, not arbitrarily writable; git itself creates new
                // objects in owner-writable directories.
                .filter(
                    path ->
                        !underGitMetadata(workspace, path)
                            || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> !Files.isSymbolicLink(path))
                .filter(path -> !writableBy(path, runtimeUid, runtimeGid))
                .findFirst()
                .orElse(null);
        if (incompatible != null) {
          throw new IllegalStateException(
              "run workspace is not writable by sandbox uid:gid "
                  + runtimeUid
                  + ":"
                  + runtimeGid
                  + " at "
                  + workspace.relativize(incompatible));
        }
      }
    } catch (UnsupportedOperationException unsupported) {
      throw new IllegalStateException(
          "host filesystem does not expose unix ownership for sandbox admission", unsupported);
    } catch (IOException failure) {
      throw new IllegalStateException(
          "run workspace ownership could not be verified for sandbox admission", failure);
    }
  }

  private static boolean underGitMetadata(Path workspace, Path path) {
    Path relative = workspace.relativize(path);
    for (Path segment : relative) {
      if (".git".equals(segment.toString())) {
        return true;
      }
    }
    return false;
  }

  private static boolean writableBy(Path path, int runtimeUid, int runtimeGid) {
    try {
      Map<String, Object> attrs =
          Files.readAttributes(path, "unix:uid,gid,mode", LinkOption.NOFOLLOW_LINKS);
      int owner = ((Number) attrs.get("uid")).intValue();
      int group = ((Number) attrs.get("gid")).intValue();
      int mode = ((Number) attrs.get("mode")).intValue();
      int writeBit =
          owner == runtimeUid ? OWNER_WRITE : group == runtimeGid ? GROUP_WRITE : OTHER_WRITE;
      if ((mode & writeBit) == 0) {
        return false;
      }
      if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        return true;
      }
      int executeBit =
          owner == runtimeUid ? OWNER_EXECUTE : group == runtimeGid ? GROUP_EXECUTE : OTHER_EXECUTE;
      return (mode & executeBit) != 0;
    } catch (IOException | RuntimeException unreadable) {
      return false;
    }
  }
}
