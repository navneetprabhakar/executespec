package com.navneet.executespec.codeintel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.cost.ProjectedCallBudgetExceededException;

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
                Runnable providerFence = invocation.getArgument(7);
                providerFence.run();
                @SuppressWarnings("unchecked")
                java.util.function.Consumer<ProjectGeneratedDoc> fence = invocation.getArgument(8);
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
              org.mockito.ArgumentMatchers.eq(lease.token()),
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
                  org.mockito.ArgumentMatchers.eq(lease.token()),
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
                Runnable providerFence = invocation.getArgument(7);
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
              org.mockito.ArgumentMatchers.eq(lease.token()),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any());

      worker.process(lease);

      verify(store)
          .needsConfirmation(
              lease,
              "OUTCOME_UNCERTAIN",
              "No document was published after provider dispatch began; delivery outcome may be"
                  + " uncertain. Retrying the same route may incur additional usage.");
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

  @Test
  void knownInvalidOutputAfterDispatchKeepsExactSafeClassificationBehindConfirmation() {
    ProjectGeneratedDocJobStore store = mock(ProjectGeneratedDocJobStore.class);
    ProjectDocGeneratorService generator = mock(ProjectDocGeneratorService.class);
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.initialize();
    var leaseExecutor = Executors.newSingleThreadScheduledExecutor();
    try {
      ProjectGeneratedDocJobWorker worker =
          new ProjectGeneratedDocJobWorker(store, generator, executor, leaseExecutor);
      var lease = lease("DEEPSEEK", "deepseek-v4-pro-native");
      org.mockito.Mockito.when(store.markProviderStarted(lease)).thenReturn(true);
      doAnswer(
              invocation -> {
                Runnable providerFence = invocation.getArgument(7);
                providerFence.run();
                throw new ProjectDocGeneratorService.InvalidGeneratedDocumentException(
                    "missing required heading");
              })
          .when(generator)
          .generateExactForJob(
              org.mockito.ArgumentMatchers.eq("project-1"),
              org.mockito.ArgumentMatchers.eq(DocType.EXPLANATION),
              org.mockito.ArgumentMatchers.eq("user-1"),
              org.mockito.ArgumentMatchers.eq(com.navneet.executespec.router.LlmProvider.DEEPSEEK),
              org.mockito.ArgumentMatchers.eq("deepseek-v4-pro-native"),
              org.mockito.ArgumentMatchers.eq(lease.job().id()),
              org.mockito.ArgumentMatchers.eq(lease.token()),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any());

      worker.process(lease);

      verify(store)
          .needsConfirmation(
              lease,
              "INVALID_GENERATED_DOCUMENT_MISSING_REQUIRED_HEADING",
              "The generated document did not satisfy the required document structure. No"
                  + " document was published after provider dispatch began; delivery outcome may"
                  + " be uncertain. Retrying the same route may incur additional usage.");
      verify(store, never()).fail(any(), anyString(), anyString());
    } finally {
      leaseExecutor.shutdownNow();
      executor.shutdown();
    }
  }

  @Test
  void correctiveCallBudgetRejectionAfterKnownFirstResultRequiresConfirmedFreshAttempt() {
    ProjectGeneratedDocJobStore store = mock(ProjectGeneratedDocJobStore.class);
    ProjectDocGeneratorService generator = mock(ProjectDocGeneratorService.class);
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.initialize();
    var leaseExecutor = Executors.newSingleThreadScheduledExecutor();
    try {
      ProjectGeneratedDocJobWorker worker =
          new ProjectGeneratedDocJobWorker(store, generator, executor, leaseExecutor);
      var lease = lease("DEEPSEEK", "deepseek-v4-pro-native");
      org.mockito.Mockito.when(store.markProviderStarted(lease)).thenReturn(true);
      doAnswer(
              invocation -> {
                Runnable providerFence = invocation.getArgument(7);
                // The first provider response is already known and invalid; the exact-route budget
                // then rejects the corrective request before its transport begins.
                providerFence.run();
                throw new ProjectDocGeneratorService.PreTransportGenerationException(
                    new ProjectedCallBudgetExceededException("operation hard cap", 3.0, 2.9, 0.2));
              })
          .when(generator)
          .generateExactForJob(
              org.mockito.ArgumentMatchers.eq("project-1"),
              org.mockito.ArgumentMatchers.eq(DocType.EXPLANATION),
              org.mockito.ArgumentMatchers.eq("user-1"),
              org.mockito.ArgumentMatchers.eq(com.navneet.executespec.router.LlmProvider.DEEPSEEK),
              org.mockito.ArgumentMatchers.eq("deepseek-v4-pro-native"),
              org.mockito.ArgumentMatchers.eq(lease.job().id()),
              org.mockito.ArgumentMatchers.eq(lease.token()),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any());

      worker.process(lease);

      verify(store)
          .needsConfirmation(
              lease,
              "PROJECT_DOC_BUDGET_EXCEEDED",
              "The bounded document attempt exhausted its provider-call budget after provider"
                  + " dispatch began; delivery outcome may be uncertain. Confirm regeneration on"
                  + " the same route to start a fresh bounded attempt; this may incur additional"
                  + " usage.");
      verify(store, never()).fail(any(), anyString(), anyString());
    } finally {
      leaseExecutor.shutdownNow();
      executor.shutdown();
    }
  }

  @Test
  void staleSourceAfterProviderWorkOffersOnlyConfirmedRegenerationAgainstLatestEvidence() {
    ProjectGeneratedDocJobStore store = mock(ProjectGeneratedDocJobStore.class);
    ProjectDocGeneratorService generator = mock(ProjectDocGeneratorService.class);
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.initialize();
    var leaseExecutor = Executors.newSingleThreadScheduledExecutor();
    try {
      ProjectGeneratedDocJobWorker worker =
          new ProjectGeneratedDocJobWorker(store, generator, executor, leaseExecutor);
      var lease = lease("DEEPSEEK", "deepseek-v4-pro-native");
      org.mockito.Mockito.when(store.markProviderStarted(lease)).thenReturn(true);
      doAnswer(
              invocation -> {
                Runnable providerFence = invocation.getArgument(7);
                providerFence.run();
                throw new ProjectDocGeneratorService.StaleSourceException();
              })
          .when(generator)
          .generateExactForJob(
              org.mockito.ArgumentMatchers.eq("project-1"),
              org.mockito.ArgumentMatchers.eq(DocType.EXPLANATION),
              org.mockito.ArgumentMatchers.eq("user-1"),
              org.mockito.ArgumentMatchers.eq(com.navneet.executespec.router.LlmProvider.DEEPSEEK),
              org.mockito.ArgumentMatchers.eq("deepseek-v4-pro-native"),
              org.mockito.ArgumentMatchers.eq(lease.job().id()),
              org.mockito.ArgumentMatchers.eq(lease.token()),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any());

      worker.process(lease);

      verify(store)
          .needsConfirmation(
              lease,
              "PROJECT_SOURCE_CHANGED",
              "The project architecture changed after provider dispatch began. Delivery outcome"
                  + " may be uncertain, and no document was published. Confirm regeneration"
                  + " against the latest project evidence on the same route; this may incur"
                  + " additional usage.");
      verify(store, never()).fail(any(), anyString(), anyString());
    } finally {
      leaseExecutor.shutdownNow();
      executor.shutdown();
    }
  }

  @Test
  void credentialAdmissionRejectionBeforeTransportFailsWithoutProviderStartOrConfirmation() {
    ProjectGeneratedDocJobStore store = mock(ProjectGeneratedDocJobStore.class);
    ProjectDocGeneratorService generator = mock(ProjectDocGeneratorService.class);
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.initialize();
    var leaseExecutor = Executors.newSingleThreadScheduledExecutor();
    try {
      ProjectGeneratedDocJobWorker worker =
          new ProjectGeneratedDocJobWorker(store, generator, executor, leaseExecutor);
      var lease = lease("DEEPSEEK", "deepseek-v4-pro-native");
      org.mockito.Mockito.when(
              generator.generateExactForJob(
                  org.mockito.ArgumentMatchers.eq("project-1"),
                  org.mockito.ArgumentMatchers.eq(DocType.EXPLANATION),
                  org.mockito.ArgumentMatchers.eq("user-1"),
                  org.mockito.ArgumentMatchers.eq(
                      com.navneet.executespec.router.LlmProvider.DEEPSEEK),
                  org.mockito.ArgumentMatchers.eq("deepseek-v4-pro-native"),
                  org.mockito.ArgumentMatchers.eq(lease.job().id()),
                  org.mockito.ArgumentMatchers.eq(lease.token()),
                  org.mockito.ArgumentMatchers.any(),
                  org.mockito.ArgumentMatchers.any()))
          .thenThrow(
              new ProjectDocGeneratorService.PreTransportGenerationException(
                  new com.navneet.executespec.executioncontext.LlmDispatchPolicyException(
                      com.navneet.executespec.executioncontext.LlmDispatchPolicyException.Reason
                          .CREDENTIAL_UNAVAILABLE,
                      "encrypted credential unavailable")));

      worker.process(lease);

      verify(store)
          .fail(
              lease,
              "PROJECT_PROVIDER_NOT_CONFIGURED",
              "The selected project provider is not configured. Update provider settings and"
                  + " retry.");
      verify(store, never()).markProviderStarted(any());
      verify(store, never()).needsConfirmation(any(), anyString(), anyString());
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
