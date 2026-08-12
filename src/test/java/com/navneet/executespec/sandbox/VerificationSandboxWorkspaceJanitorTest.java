package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerificationSandboxWorkspaceJanitorTest {

  @TempDir Path root;
  @TempDir Path outside;

  @Test
  void deletesOnlyTheDerivedSandboxOwnedWorkspaceAndCompletesTheDurableClaim() throws Exception {
    UUID id = UUID.randomUUID();
    Path workspace = Files.createDirectories(root.resolve(id.toString()).resolve("workspace"));
    Files.writeString(workspace.resolve("side-effect.txt"), "discard me");
    VerificationSandboxWorkspaceCleanupStore store =
        mock(VerificationSandboxWorkspaceCleanupStore.class);
    var claim = new VerificationSandboxWorkspaceCleanupStore.Claim(id, 1);
    when(store.claimNext(java.time.Duration.ofMinutes(10)))
        .thenReturn(Optional.of(claim), Optional.empty());
    SandboxProperties properties = new SandboxProperties();
    properties.setWorkspaceRoot(root.toString());

    int cleaned = new VerificationSandboxWorkspaceJanitor(store, properties).sweep(10);

    assertThat(cleaned).isOne();
    assertThat(root.resolve(id.toString())).doesNotExist();
    verify(store).complete(claim);
  }

  @Test
  void refusesASymlinkedSandboxRootWithoutDeletingItsTarget() throws Exception {
    UUID id = UUID.randomUUID();
    Path target = Files.createDirectories(outside.resolve("target"));
    Files.writeString(target.resolve("evidence.txt"), "retain");
    Files.createSymbolicLink(root.resolve(id.toString()), target);
    SandboxProperties properties = new SandboxProperties();
    properties.setWorkspaceRoot(root.toString());
    VerificationSandboxWorkspaceJanitor janitor =
        new VerificationSandboxWorkspaceJanitor(
            mock(VerificationSandboxWorkspaceCleanupStore.class), properties);

    assertThatThrownBy(() -> janitor.deleteOwnedWorkspace(id))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SYMLINK_REFUSED");
    assertThat(target.resolve("evidence.txt")).exists();
  }
}
