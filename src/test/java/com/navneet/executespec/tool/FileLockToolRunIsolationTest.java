package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.state.entity.FileLock;
import com.navneet.executespec.state.repository.FileLockRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RR-1544 — file locks are keyed on {@code (runId, filePath)}. With the bare relative path as the
 * sole key, two concurrent runs (possibly different tenants) touching {@code src/index.ts}
 * collided: the second claim was refused, the row was reattributed, and {@code getOwner} disclosed
 * a foreign run's taskId.
 *
 * <p>Uses a tiny in-memory fake repository so no database or Spring context is required.
 */
class FileLockToolRunIsolationTest {

  /** Minimal in-memory stand-in keyed exactly as the real table now is. */
  private static class InMemoryLocks {
    private final List<FileLock> rows = new ArrayList<>();

    Optional<FileLock> find(String runId, String filePath) {
      return rows.stream()
          .filter(r -> r.getRunId().equals(runId) && r.getFilePath().equals(filePath))
          .findFirst();
    }

    void save(FileLock lock) {
      find(lock.getRunId(), lock.getFilePath()).ifPresent(rows::remove);
      rows.add(lock);
    }

    void delete(String runId, String filePath) {
      find(runId, filePath).ifPresent(rows::remove);
    }

    List<FileLock> byRun(String runId) {
      return rows.stream().filter(r -> r.getRunId().equals(runId)).toList();
    }
  }

  private InMemoryLocks store;
  private FileLockTool tool;

  @TempDir java.nio.file.Path workspace;

  @BeforeEach
  void setUp() {
    store = new InMemoryLocks();
    FileLockRepository repo = Mockito.mock(FileLockRepository.class);
    Mockito.when(repo.findByRunIdAndFilePath(Mockito.anyString(), Mockito.anyString()))
        .thenAnswer(i -> store.find(i.getArgument(0), i.getArgument(1)));
    Mockito.when(repo.save(Mockito.any(FileLock.class)))
        .thenAnswer(
            i -> {
              FileLock lock = i.getArgument(0);
              store.save(lock);
              return lock;
            });
    Mockito.doAnswer(
            i -> {
              store.delete(i.getArgument(0), i.getArgument(1));
              return null;
            })
        .when(repo)
        .deleteByRunIdAndFilePath(Mockito.anyString(), Mockito.anyString());
    Mockito.when(repo.findByRunId(Mockito.anyString()))
        .thenAnswer(i -> store.byRun(i.getArgument(0)));

    tool = new FileLockTool(repo, new ObjectMapper());
    ReflectionTestUtils.setField(tool, "workspaceRoot", workspace.toString());
  }

  private static final String SHARED_PATH = "src/index.ts";

  @Test
  void twoRunsClaimingTheSameRelativePathBothSucceed() {
    assertThat(tool.claim(SHARED_PATH, "run-A", "task-1")).isTrue();
    assertThat(tool.claim(SHARED_PATH, "run-B", "task-1")).isTrue();

    assertThat(store.byRun("run-A")).hasSize(1);
    assertThat(store.byRun("run-B")).hasSize(1);
  }

  @Test
  void withinOneRunASecondTaskIsStillRefused() {
    assertThat(tool.claim(SHARED_PATH, "run-A", "task-1")).isTrue();
    assertThat(tool.claim(SHARED_PATH, "run-A", "task-2")).isFalse();
  }

  @Test
  void getOwnerCannotObserveAForeignRunsLock() {
    tool.claim(SHARED_PATH, "run-A", "task-secret");

    assertThat(tool.getOwner("run-A", SHARED_PATH)).contains("task-secret");
    assertThat(tool.getOwner("run-B", SHARED_PATH)).isEmpty();
  }

  @Test
  void releaseOnlyAffectsTheOwningRun() {
    tool.claim(SHARED_PATH, "run-A", "task-1");
    tool.claim(SHARED_PATH, "run-B", "task-1");

    tool.release("run-A", SHARED_PATH, "task-1");

    assertThat(store.byRun("run-A")).isEmpty();
    assertThat(store.byRun("run-B")).hasSize(1);
  }

  @Test
  void sharedByDefaultFilesAreNeverLocked() {
    assertThat(tool.claim("pom.xml", "run-A", "task-1")).isTrue();
    assertThat(tool.claim("pom.xml", "run-A", "task-2")).isTrue();
    assertThat(tool.getOwner("run-A", "pom.xml")).isEmpty();
  }
}
