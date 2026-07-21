package com.navneet.executespec.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * RR-500 regression — the destructive one.
 *
 * <p>The run workspace holds a cloned, customer-controlled repository. Before this guard, a
 * repository could ship {@code .agent-context/worker} (or {@code .agent-context}) as a symlink to
 * any writable host directory: checkpoint writes escaped the workspace, and run-end cleanup
 * enumerated the link target and DELETED every regular file it found there, under the server
 * account.
 *
 * <p>These tests plant a canary directory outside the workspace, point the reserved metadata name
 * at it, and assert the canary survives a full save/cleanup cycle.
 */
@ExtendWith(MockitoExtension.class)
class ContextStoreSymlinkEscapeTest {

  @Mock private Metrics metrics;

  @TempDir Path tempRoot;

  private Path workspace;
  private Path outsideCanaryDir;
  private Path canaryFile;

  private void plantCanary() throws IOException {
    workspace = Files.createDirectories(tempRoot.resolve("workspace"));
    outsideCanaryDir = Files.createDirectories(tempRoot.resolve("host-secrets"));
    canaryFile = outsideCanaryDir.resolve("id_rsa");
    Files.writeString(canaryFile, "PRIVATE KEY — must never be touched");
  }

  /**
   * @return false when the platform refuses symlink creation (e.g. Windows without privileges)
   */
  private boolean linkAgentContextTo(Path target) {
    try {
      Files.createSymbolicLink(workspace.resolve(".agent-context"), target);
      return true;
    } catch (IOException | UnsupportedOperationException e) {
      return false;
    }
  }

  @Test
  void runCleanupNeverDeletesThroughARepositorySymlink() throws Exception {
    plantCanary();
    // The repository ships `.agent-context` as a link to a host directory, so
    // `.agent-context/worker` resolves outside the workspace entirely.
    assumeThat(linkAgentContextTo(outsideCanaryDir)).isTrue();

    ContextStore store = new ContextStore(new ObjectMapper(), metrics, (StringRedisTemplate) null);
    ExecutionContext ctx = new ExecutionContext("run-escape", "task-1", workspace);

    // Neither checkpointing nor cleanup may follow the link.
    store.save(ctx, List.of(new org.springframework.ai.chat.messages.UserMessage("payload")), 1);
    long deleted = store.deleteAll("run-escape", workspace);

    assertThat(deleted).isZero();
    assertThat(canaryFile).exists();
    assertThat(Files.readString(canaryFile)).startsWith("PRIVATE KEY");
    assertThat(Files.list(outsideCanaryDir).toList()).hasSize(1);
  }

  @Test
  void checkpointWritesNeverEscapeTheWorkspace() throws Exception {
    plantCanary();
    assumeThat(linkAgentContextTo(outsideCanaryDir)).isTrue();

    ContextStore store = new ContextStore(new ObjectMapper(), metrics, (StringRedisTemplate) null);
    ExecutionContext ctx = new ExecutionContext("run-escape", "task-1", workspace);

    // No Redis and a refused disk write means nothing was persisted at all.
    assertThat(
            store.save(
                ctx, List.of(new org.springframework.ai.chat.messages.UserMessage("payload")), 1))
        .isZero();
    assertThat(store.exists(ctx)).isFalse();
    assertThat(store.load(ctx)).isEmpty();
    // The link target gained no ExecuteSpec files.
    assertThat(Files.list(outsideCanaryDir).toList()).hasSize(1);
  }

  @Test
  void deleteAllStillCleansAGenuineInWorkspaceCheckpoint() throws Exception {
    plantCanary();

    ContextStore store = new ContextStore(new ObjectMapper(), metrics, (StringRedisTemplate) null);
    ExecutionContext ctx = new ExecutionContext("run-ok", "task-1", workspace);
    store.save(ctx, List.of(new org.springframework.ai.chat.messages.UserMessage("payload")), 1);

    assertThat(store.deleteAll("run-ok", workspace)).isEqualTo(1);
    assertThat(store.exists(ctx)).isFalse();
  }
}
