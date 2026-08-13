package com.navneet.executespec.workspacegeneration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;

import com.navneet.executespec.config.AgentProperties;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Retries deletion of recovery scratch trees without changing authoritative restore truth. */
@Component
@RequiredArgsConstructor
@Slf4j
class WorkspaceGenerationRecoveryArtifactJanitor {

  private static final Duration STALE_CLAIM = Duration.ofMinutes(10);

  private final WorkspaceGenerationRecoveryCleanupStore store;
  private final AgentProperties properties;

  void enqueue(String runId, Path artifact) {
    if (artifact != null && Files.exists(artifact, LinkOption.NOFOLLOW_LINKS)) {
      Path safe = validate(runId, artifact);
      store.enqueue(runId, safe.toString());
    }
  }

  @Scheduled(
      fixedDelayString = "${execution.workspace-generation.cleanup-scan-ms:60000}",
      initialDelayString = "${execution.workspace-generation.cleanup-initial-delay-ms:60000}")
  int sweep() {
    int completed = 0;
    for (int i = 0; i < 100; i++) {
      var claim = store.claimNext(STALE_CLAIM).orElse(null);
      if (claim == null) break;
      try {
        deleteTree(validate(claim.runId(), Path.of(claim.path())));
        store.complete(claim);
        completed++;
      } catch (RuntimeException failure) {
        log.error("workspace recovery artifact cleanup failed run={}", claim.runId(), failure);
        store.fail(claim, failure.getMessage());
      }
    }
    return completed;
  }

  private Path validate(String runId, Path candidate) {
    Path base = Path.of(properties.getWorkspace()).toAbsolutePath().normalize();
    Path safe = candidate.toAbsolutePath().normalize();
    String name = safe.getFileName() == null ? "" : safe.getFileName().toString();
    boolean named =
        name.startsWith(runId + ".accepted-")
            || name.startsWith(runId + ".displaced-")
            || name.startsWith(runId + ".rejected-");
    if (!base.equals(safe.getParent()) || !named || Files.isSymbolicLink(safe)) {
      throw new IllegalStateException("WORKSPACE_RECOVERY_CLEANUP_PATH_REFUSED");
    }
    return safe;
  }

  private static void deleteTree(Path root) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("WORKSPACE_RECOVERY_CLEANUP_DELETE_FAILED", failure);
    }
  }
}
