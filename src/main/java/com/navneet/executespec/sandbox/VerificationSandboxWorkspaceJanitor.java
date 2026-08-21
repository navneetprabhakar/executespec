package com.navneet.executespec.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Deletes only terminal, sandbox-owned VERIFICATION trees under durable retry claims. */
@Component
@RequiredArgsConstructor
@Slf4j
public class VerificationSandboxWorkspaceJanitor {

  private static final Duration STALE_CLAIM = Duration.ofMinutes(10);

  private final VerificationSandboxWorkspaceCleanupStore store;
  private final SandboxProperties properties;

  public int sweep(int limit) {
    int completed = 0;
    for (int i = 0; i < Math.max(0, limit); i++) {
      var claim = store.claimNext(STALE_CLAIM).orElse(null);
      if (claim == null) break;
      try {
        deleteOwnedWorkspace(claim.sandboxId());
        store.complete(claim);
        completed++;
      } catch (RuntimeException failure) {
        log.error("verification sandbox {} workspace cleanup failed", claim.sandboxId(), failure);
        store.fail(claim, failure.getMessage());
      }
    }
    return completed;
  }

  void deleteOwnedWorkspace(java.util.UUID sandboxId) {
    Path global = Path.of(properties.getWorkspaceRoot()).toAbsolutePath().normalize();
    Path owned = SandboxContainerCommand.ownedWorkspaceRoot(global, sandboxId.toString());
    Path workspace = owned.resolve("workspace").normalize();
    if (!owned.equals(workspace.getParent())) {
      throw new IllegalStateException("VERIFICATION_CLEANUP_PATH_REFUSED");
    }
    try {
      if (Files.isSymbolicLink(owned) || Files.isSymbolicLink(workspace)) {
        throw new IllegalStateException("VERIFICATION_CLEANUP_SYMLINK_REFUSED");
      }
      if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
        try (var paths = Files.walk(workspace)) {
          for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(path);
          }
        }
      }
      if (Files.isDirectory(owned, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(owned);
    } catch (IOException failure) {
      throw new IllegalStateException("VERIFICATION_CLEANUP_DELETE_FAILED", failure);
    }
  }
}
