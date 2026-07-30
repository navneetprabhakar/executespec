package com.navneet.executespec.codeintel;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ProjectGeneratedDocJobWorkerTest {

  @Test
  void publishesGeneratedDocumentAgainstTheDurableJob() {
    ProjectGeneratedDocJobStore store = mock(ProjectGeneratedDocJobStore.class);
    ProjectDocGeneratorService generator = mock(ProjectDocGeneratorService.class);
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.initialize();
    var leaseExecutor = Executors.newSingleThreadScheduledExecutor();
    try {
      ProjectGeneratedDocJobWorker worker =
          new ProjectGeneratedDocJobWorker(store, generator, executor, leaseExecutor);
      var lease = lease("ANTHROPIC", "claude-sonnet-4-6");
      UUID resultId = UUID.randomUUID();
      ProjectGeneratedDoc result =
          ProjectGeneratedDoc.builder()
              .id(resultId)
              .projectId("project-1")
              .docType(DocType.EXPLANATION)
              .version(4)
              .content("# Complete")
              .build();
      doAnswer(
              invocation -> {
                Runnable providerFence = invocation.getArgument(6);
                providerFence.run();
                @SuppressWarnings("unchecked")
                java.util.function.Consumer<ProjectGeneratedDoc> fence = invocation.getArgument(7);
                fence.accept(result);
                return result;
              })
          .when(generator)
          .generateExactForJob(
              org.mockito.ArgumentMatchers.eq("project-1"),
              org.mockito.ArgumentMatchers.eq(DocType.EXPLANATION),
              org.mockito.ArgumentMatchers.eq("user-1"),
              org.mockito.ArgumentMatchers.eq(com.navneet.executespec.router.LlmProvider.ANTHROPIC),
              org.mockito.ArgumentMatchers.eq("claude-sonnet-4-6"),
              org.mockito.ArgumentMatchers.eq(lease.job().id()),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any());
      org.mockito.Mockito.when(store.markProviderStarted(lease)).thenReturn(true);
      org.mockito.Mockito.when(store.complete(lease, resultId, 4)).thenReturn(true);

      worker.process(lease);

      verify(store).complete(lease, resultId, 4);
    } finally {
      leaseExecutor.shutdownNow();
      executor.shutdown();
    }
  }

  @Test
  void persistsActionableFailureInsteadOfLosingTheJob() {
    ProjectGeneratedDocJobStore store = mock(ProjectGeneratedDocJobStore.class);
    ProjectDocGeneratorService generator = mock(ProjectDocGeneratorService.class);
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.initialize();
    var leaseExecutor = Executors.newSingleThreadScheduledExecutor();
    try {
      ProjectGeneratedDocJobWorker worker =
          new ProjectGeneratedDocJobWorker(store, generator, executor, leaseExecutor);
      var lease = lease("ANTHROPIC", "claude-sonnet-4-6");
      org.mockito.Mockito.when(
              generator.generateExactForJob(
                  org.mockito.ArgumentMatchers.eq("project-1"),
                  org.mockito.ArgumentMatchers.eq(DocType.EXPLANATION),
                  org.mockito.ArgumentMatchers.eq("user-1"),
                  org.mockito.ArgumentMatchers.eq(
                      com.navneet.executespec.router.LlmProvider.ANTHROPIC),
                  org.mockito.ArgumentMatchers.eq("claude-sonnet-4-6"),
                  org.mockito.ArgumentMatchers.eq(lease.job().id()),
                  org.mockito.ArgumentMatchers.any(),
                  org.mockito.ArgumentMatchers.any()))
          .thenThrow(new IllegalStateException("project has no architecture doc yet"));

      worker.process(lease);

      verify(store)
          .fail(
              lease,
              "ILLEGALSTATEEXCEPTION",
              "Document generation could not be completed. Check project readiness and retry.");
    } finally {
      leaseExecutor.shutdownNow();
      executor.shutdown();
    }
  }

  @Test
  void uncertainFailureAfterProviderDispatchRequiresExplicitRetryConfirmation() {
    ProjectGeneratedDocJobStore store = mock(ProjectGeneratedDocJobStore.class);
    ProjectDocGeneratorService generator = mock(ProjectDocGeneratorService.class);
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.initialize();
    var leaseExecutor = Executors.newSingleThreadScheduledExecutor();
    try {
      ProjectGeneratedDocJobWorker worker =
          new ProjectGeneratedDocJobWorker(store, generator, executor, leaseExecutor);
      var lease = lease("ANTHROPIC", "claude-sonnet-4-6");
      org.mockito.Mockito.when(store.markProviderStarted(lease)).thenReturn(true);
      doAnswer(
              invocation -> {
                Runnable providerFence = invocation.getArgument(6);
                providerFence.run();
                throw new IllegalStateException("connection reset after request write");
              })
          .when(generator)
          .generateExactForJob(
              org.mockito.ArgumentMatchers.eq("project-1"),
              org.mockito.ArgumentMatchers.eq(DocType.EXPLANATION),
              org.mockito.ArgumentMatchers.eq("user-1"),
              org.mockito.ArgumentMatchers.eq(com.navneet.executespec.router.LlmProvider.ANTHROPIC),
              org.mockito.ArgumentMatchers.eq("claude-sonnet-4-6"),
              org.mockito.ArgumentMatchers.eq(lease.job().id()),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any());

      worker.process(lease);

      verify(store)
          .needsConfirmation(
              lease,
              "OUTCOME_UNCERTAIN",
              "Provider execution began, but no complete document was durably published. Retry"
                  + " requires confirmation.");
      verify(store, never())
          .fail(
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.anyString());
    } finally {
      leaseExecutor.shutdownNow();
      executor.shutdown();
    }
  }

  private static ProjectGeneratedDocJobStore.Lease lease(String provider, String model) {
    UUID id = UUID.randomUUID();
    var job =
        new ProjectGeneratedDocJobStore.Job(
            id,
            "project-1",
            "org-1",
            "user-1",
            "EXPLANATION",
            provider,
            model,
            ProjectGeneratedDocJobStore.Status.RUNNING,
            1,
            Instant.now(),
            Instant.now().plusSeconds(1800),
            "worker",
            1,
            Instant.now().plusSeconds(300),
            null,
            null,
            null,
            null,
            null,
            Instant.now(),
            Instant.now(),
            null);
    return new ProjectGeneratedDocJobStore.Lease(job, "worker", 1);
  }
}
