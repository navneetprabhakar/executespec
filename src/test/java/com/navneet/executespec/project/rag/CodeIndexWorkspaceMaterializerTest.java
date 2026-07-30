package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.project.entity.ProjectRepo;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class CodeIndexWorkspaceMaterializerTest {

  @TempDir Path workspace;

  @Test
  void publicRepositoryMaterializesWithoutCredentialIntoCanonicalIdentity() throws Exception {
    WorkspaceInitService init = fixtureInit();
    ProjectRepo repository = repository("a".repeat(40));
    CodeIndexWorkspaceMaterializer materializer = new CodeIndexWorkspaceMaterializer(init);

    Path result = materializer.materialize("project-1", repository, null, null);

    assertThat(result)
        .isEqualTo(
            workspace.resolve(CodeIndexWorkspaceIdentity.workspaceId("project-1", repository)));
    verify(init)
        .cloneReadOnlyRepository(
            anyString(), Mockito.eq("https://github.com/acme/public.git"), isNull(), isNull());
  }

  @RepeatedTest(20)
  void concurrentRequestsShareOneCloneFlight() throws Exception {
    WorkspaceInitService init = fixtureInit();
    ProjectRepo repository = repository("b".repeat(40));
    CodeIndexWorkspaceMaterializer materializer = new CodeIndexWorkspaceMaterializer(init);
    CountDownLatch cloneEntered = new CountDownLatch(1);
    CountDownLatch releaseClone = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              String id = invocation.getArgument(0);
              cloneEntered.countDown();
              releaseClone.await();
              Files.createDirectories(workspace.resolve(id).resolve(".git"));
              return null;
            })
        .when(init)
        .cloneReadOnlyRepository(anyString(), anyString(), isNull(), isNull());

    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> materializer.materialize("project-1", repository, null, null));
      cloneEntered.await();
      AtomicReference<Thread> secondThread = new AtomicReference<>();
      var second =
          pool.submit(
              () -> {
                secondThread.set(Thread.currentThread());
                return materializer.materialize("project-1", repository, null, null);
              });
      await()
          .atMost(Duration.ofSeconds(2))
          .until(
              () ->
                  secondThread.get() != null
                      && secondThread.get().getState() == Thread.State.WAITING);
      releaseClone.countDown();

      assertThat(first.get()).isEqualTo(second.get());
    }
    verify(init, times(1)).cloneReadOnlyRepository(anyString(), anyString(), isNull(), isNull());
  }

  @Test
  void poisonedPartialCacheIsRemovedAndReplaced() throws Exception {
    WorkspaceInitService init = fixtureInit();
    ProjectRepo repository = repository("c".repeat(40));
    String canonical = CodeIndexWorkspaceIdentity.workspaceId("project-1", repository);
    Path poison = workspace.resolve(canonical);
    Files.createDirectories(poison);
    Files.writeString(poison.resolve("partial-clone"), "broken");
    CodeIndexWorkspaceMaterializer materializer = new CodeIndexWorkspaceMaterializer(init);

    Path result = materializer.materialize("project-1", repository, null, null);

    assertThat(result.resolve(".git")).isDirectory();
    assertThat(result.resolve("partial-clone")).doesNotExist();
    verify(init).cloneReadOnlyRepository(anyString(), anyString(), isNull(), isNull());
  }

  @Test
  void changedSourceRevisionUsesFreshWorkspaceInsteadOfStaleClone() throws Exception {
    WorkspaceInitService init = fixtureInit();
    ProjectRepo repository = repository("d".repeat(40));
    CodeIndexWorkspaceMaterializer materializer = new CodeIndexWorkspaceMaterializer(init);

    Path first = materializer.materialize("project-1", repository, null, null);
    repository.setLastSyncedSha("e".repeat(40));
    Path second = materializer.materialize("project-1", repository, null, null);

    assertThat(second).isNotEqualTo(first);
    verify(init, times(2)).cloneReadOnlyRepository(anyString(), anyString(), isNull(), isNull());
  }

  private WorkspaceInitService fixtureInit() throws Exception {
    WorkspaceInitService init = Mockito.mock(WorkspaceInitService.class);
    when(init.runWorkspacePath(anyString()))
        .thenAnswer(invocation -> workspace.resolve(invocation.getArgument(0, String.class)));
    when(init.checkoutExactCommit(anyString(), anyString())).thenReturn(true);
    doAnswer(
            invocation -> {
              String id = invocation.getArgument(0);
              Files.createDirectories(workspace.resolve(id).resolve(".git"));
              return null;
            })
        .when(init)
        .cloneReadOnlyRepository(anyString(), anyString(), isNull(), isNull());
    return init;
  }

  private static ProjectRepo repository(String sha) {
    return ProjectRepo.builder()
        .id(UUID.randomUUID())
        .githubRepoFullName("acme/public")
        .scmCloneUrl("https://github.com/acme/public.git")
        .lastSyncedSha(sha)
        .build();
  }
}
