package com.navneet.executespec.codeintel;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.router.LlmProvider;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Claims durable document jobs and publishes only complete, validated generated documents. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectGeneratedDocJobWorker {

  private static final Duration LEASE = Duration.ofMinutes(5);
  private static final int MAX_PARALLEL = 2;

  private final ProjectGeneratedDocJobStore store;
  private final ProjectDocGeneratorService generator;

  @Qualifier("projectArchitectureExecutor") private final ThreadPoolTaskExecutor executor;

  @Qualifier("projectArchitectureLeaseExecutor") private final ScheduledExecutorService leaseExecutor;

  private final java.util.concurrent.atomic.AtomicInteger inFlight =
      new java.util.concurrent.atomic.AtomicInteger();
  private final String workerId = "project-doc:" + UUID.randomUUID();

  @Scheduled(fixedDelayString = "${project.generated-doc-jobs.poll-interval-millis:2000}")
  public void poll() {
    store.sweepExpired();
    int available = Math.max(0, MAX_PARALLEL - inFlight.get());
    for (UUID candidate : store.claimCandidates(available)) {
      var lease = store.tryAcquire(candidate, workerId, LEASE);
      if (lease.isEmpty()) continue;
      inFlight.incrementAndGet();
      try {
        executor.execute(
            () -> {
              try {
                process(lease.get());
              } finally {
                inFlight.decrementAndGet();
              }
            });
      } catch (RuntimeException rejected) {
        inFlight.decrementAndGet();
        store.fail(lease.get(), "EXECUTOR_REJECTED", "Document worker capacity is unavailable.");
      }
    }
  }

  @Scheduled(fixedDelayString = "${project.generated-doc-jobs.retention-sweep-millis:3600000}")
  public void pruneTerminalJobs() {
    store.deleteTerminalOlderThan(Duration.ofDays(30));
  }

  void process(ProjectGeneratedDocJobStore.Lease lease) {
    AtomicBoolean leaseLost = new AtomicBoolean();
    AtomicBoolean providerStarted = new AtomicBoolean();
    ScheduledFuture<?> heartbeat =
        leaseExecutor.scheduleAtFixedRate(
            () -> {
              try {
                if (!store.renew(lease, LEASE)) leaseLost.set(true);
              } catch (RuntimeException failure) {
                leaseLost.set(true);
              }
            },
            60,
            60,
            TimeUnit.SECONDS);
    try {
      LlmProvider provider = LlmProvider.valueOf(lease.job().requestedProvider());
      DocType docType = DocType.valueOf(lease.job().docType());
      generator.generateExactForJob(
          lease.job().projectId(),
          docType,
          lease.job().requestedByUserId(),
          provider,
          lease.job().requestedModel(),
          lease.job().id(),
          () -> {
            if (!store.markProviderStarted(lease)) {
              leaseLost.set(true);
              throw new IllegalStateException(
                  "Document provider dispatch lost its durable job fence");
            }
            providerStarted.set(true);
          },
          result -> {
            if (leaseLost.get()
                || result.getId() == null
                || result.getVersion() == null
                || !store.complete(lease, result.getId(), result.getVersion())) {
              throw new IllegalStateException(
                  "Document result could not be bound to its live durable job");
            }
          });
    } catch (RuntimeException failure) {
      if (!leaseLost.get()) {
        if (providerStarted.get()) {
          store.needsConfirmation(
              lease,
              "OUTCOME_UNCERTAIN",
              "Provider execution began, but no complete document was durably published. Retry"
                  + " requires confirmation.");
        } else {
          store.fail(lease, errorCode(failure), safeMessage(failure));
        }
        log.warn(
            "project document job failed job={} project={} code={} errorClass={}",
            lease.job().id(),
            lease.job().projectId(),
            errorCode(failure),
            failure.getClass().getSimpleName());
      }
    } finally {
      heartbeat.cancel(false);
    }
  }

  private static String errorCode(RuntimeException failure) {
    if (failure instanceof ProjectDocGeneratorService.IncompleteGenerationException
        || failure instanceof ProjectDocGeneratorService.ProviderNonCompleteGenerationException) {
      return "PROVIDER_OUTPUT_INCOMPLETE";
    }
    if (failure instanceof ProjectDocGeneratorService.StaleSourceException) {
      return "PROJECT_SOURCE_CHANGED";
    }
    if (failure instanceof ProjectDocGeneratorService.UnsafeGeneratedDocumentException) {
      return "UNSAFE_GENERATED_CONTENT";
    }
    if (failure instanceof ProjectDocGeneratorService.InvalidGeneratedDocumentException) {
      return "INVALID_GENERATED_DOCUMENT";
    }
    String simple = failure.getClass().getSimpleName();
    return simple.isBlank()
        ? "DOCUMENT_GENERATION_FAILED"
        : simple.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
  }

  private static String safeMessage(RuntimeException failure) {
    if (failure instanceof ProjectDocGeneratorService.IncompleteGenerationException
        || failure instanceof ProjectDocGeneratorService.ProviderNonCompleteGenerationException) {
      return "Provider output ended before the document was complete.";
    }
    if (failure instanceof ProjectDocGeneratorService.StaleSourceException) {
      return "The project architecture changed during generation. Start a new document job.";
    }
    if (failure instanceof ProjectDocGeneratorService.UnsafeGeneratedDocumentException) {
      return "The generated document failed the publication safety check.";
    }
    if (failure instanceof ProjectDocGeneratorService.InvalidGeneratedDocumentException) {
      return "The generated document did not satisfy the required document structure.";
    }
    if (failure instanceof org.springframework.web.server.ResponseStatusException status
        && status.getStatusCode().is4xxClientError()) {
      String reason = status.getReason();
      if (reason != null
          && !reason.toLowerCase(Locale.ROOT).contains("credential")
          && !reason.toLowerCase(Locale.ROOT).contains("token")
          && !reason.toLowerCase(Locale.ROOT).contains("secret")) {
        return reason;
      }
    }
    return "Document generation could not be completed. Check project readiness and retry.";
  }
}
