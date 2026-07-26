package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxWorkspaceOwnershipTest {

  @Test
  void admitsAWorkspaceOwnedAndWritableByTheConfiguredRuntime(@TempDir Path workspace)
      throws Exception {
    Files.writeString(workspace.resolve("app.py"), "print('ok')\n");
    Map<String, Object> attrs = Files.readAttributes(workspace, "unix:uid,gid");
    int uid = ((Number) attrs.get("uid")).intValue();
    int gid = ((Number) attrs.get("gid")).intValue();

    assertThatNoException()
        .isThrownBy(() -> SandboxWorkspaceOwnership.requireWritableTree(workspace, uid, gid));
  }

  @Test
  void refusesAWorkspaceThatOnlyTheHostOwnerCanWrite(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("app.py"), "print('ok')\n");
    Files.setPosixFilePermissions(workspace, PosixFilePermissions.fromString("rwxr-xr-x"));
    Map<String, Object> attrs = Files.readAttributes(workspace, "unix:uid,gid");
    int uid = ((Number) attrs.get("uid")).intValue();
    int gid = ((Number) attrs.get("gid")).intValue();

    assertThatThrownBy(
            () -> SandboxWorkspaceOwnership.requireWritableTree(workspace, uid + 1, gid + 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not writable by sandbox uid:gid");
  }

  @Test
  void immutableGitObjectsDoNotMakeTheProductWorkspaceUnwritable(@TempDir Path workspace)
      throws Exception {
    Path objects = Files.createDirectories(workspace.resolve(".git/objects/aa"));
    Path object = Files.writeString(objects.resolve("object"), "immutable");
    Files.setPosixFilePermissions(object, PosixFilePermissions.fromString("r--r--r--"));
    Map<String, Object> attrs = Files.readAttributes(workspace, "unix:uid,gid");
    int uid = ((Number) attrs.get("uid")).intValue();
    int gid = ((Number) attrs.get("gid")).intValue();

    assertThatNoException()
        .isThrownBy(() -> SandboxWorkspaceOwnership.requireWritableTree(workspace, uid, gid));
  }
}
