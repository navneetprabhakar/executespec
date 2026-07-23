package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathSafetyTest {

  @TempDir Path temp;

  @Test
  void rejectsExistingFileSymlink() throws IOException {
    Path workspace = Files.createDirectory(temp.resolve("workspace"));
    Path outside = Files.writeString(temp.resolve("secret.txt"), "secret");
    Path link = workspace.resolve("secret-link");
    if (!createSymlink(link, outside)) return;

    assertThatThrownBy(() -> PathSafety.safeResolve(workspace, "secret-link"))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("symbolic links");
  }

  @Test
  void rejectsDirectorySymlinkForNewAndExistingChildren() throws IOException {
    Path workspace = Files.createDirectory(temp.resolve("workspace"));
    Path outside = Files.createDirectory(temp.resolve("outside"));
    Files.writeString(outside.resolve("existing.txt"), "secret");
    Path link = workspace.resolve("linked-dir");
    if (!createSymlink(link, outside)) return;

    assertThatThrownBy(() -> PathSafety.safeResolve(workspace, "linked-dir/existing.txt"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> PathSafety.safeResolve(workspace, "linked-dir/new.txt"))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  void allowsNewLeafUnderValidatedRealParent() throws IOException {
    Path workspace = Files.createDirectory(temp.resolve("workspace"));
    Files.createDirectories(workspace.resolve("src/main"));

    assertThat(PathSafety.safeResolve(workspace, "src/main/New.java"))
        .isEqualTo(workspace.toRealPath().resolve("src/main/New.java"));
  }

  private static boolean createSymlink(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
      return true;
    } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
      return false;
    }
  }
}
