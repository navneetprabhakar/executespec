package com.navneet.executespec.codeintel;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.navneet.executespec.agent.ByokContextUnavailableException;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.cost.ProjectedCallBudgetExceededException;
import com.navneet.executespec.executioncontext.LlmDispatchPolicyException;
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
          lease.token(),
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
        if (providerStarted.get() && retryConfirmationRequired(failure)) {
          store.needsConfirmation(lease, errorCode(failure), postDispatchMessage(failure));
        } else if (deterministicPreTransportFailure(failure)) {
          store.fail(lease, errorCode(failure), safeMessage(failure));
        } else if (providerStarted.get()) {
          String code =
              knownPostDispatchFailure(failure) ? errorCode(failure) : "OUTCOME_UNCERTAIN";
          store.needsConfirmation(lease, code, postDispatchMessage(failure));
        } else {
          store.fail(lease, errorCode(failure), safeMessage(failure));
        }
        log.warn(
            "project document job failed job={} project={} code={} errorClass={}",
            lease.job().id(),
            lease.job().projectId(),
            errorCode(failure),
            failure.getClass().getSimpleName(),
            failure);
      }
    } finally {
      heartbeat.cancel(false);
    }
  }

  private static String errorCode(RuntimeException failure) {
    if (failure
        instanceof ProjectDocGeneratorService.PreTransportGenerationException preTransport) {
      return preTransportErrorCode(preTransport.failure());
    }
    if (failure instanceof ProjectedCallBudgetExceededException) {
      return "PROJECT_DOC_BUDGET_EXCEEDED";
    }
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
    if (failure instanceof ProjectDocGeneratorService.InvalidGeneratedDocumentException invalid) {
      return "INVALID_GENERATED_DOCUMENT_" + invalid.validationSubcode();
    }
    String simple = failure.getClass().getSimpleName();
    return simple.isBlank()
        ? "DOCUMENT_GENERATION_FAILED"
        : simple.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
  }

  private static String safeMessage(RuntimeException failure) {
    if (failure
        instanceof ProjectDocGeneratorService.PreTransportGenerationException preTransport) {
      return preTransportSafeMessage(preTransport.failure());
    }
    if (failure instanceof ProjectedCallBudgetExceededException) {
      return "The document generation budget was exhausted before another provider request could"
          + " begin.";
    }
    if (failure instanceof ProjectDocGeneratorService.IncompleteGenerationException
        || failure instanceof ProjectDocGeneratorService.ProviderNonCompleteGenerationException) {
      return "Provider output ended before the document was complete.";
    }
    if (failure instanceof ProjectDocGeneratorService.StaleSourceException) {
      return "The project architecture changed during generation. Confirm regeneration against"
          + " the latest project evidence.";
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

  private static boolean knownPostDispatchFailure(RuntimeException failure) {
    return failure instanceof ProjectDocGeneratorService.IncompleteGenerationException
        || failure instanceof ProjectDocGeneratorService.ProviderNonCompleteGenerationException
        || failure instanceof ProjectDocGeneratorService.StaleSourceException
        || failure instanceof ProjectDocGeneratorService.UnsafeGeneratedDocumentException
        || failure instanceof ProjectDocGeneratorService.InvalidGeneratedDocumentException;
  }

  private static boolean deterministicPreTransportFailure(RuntimeException failure) {
    return failure instanceof ProjectDocGeneratorService.PreTransportGenerationException
        || failure instanceof ProjectedCallBudgetExceededException;
  }

  /**
   * A fresh bounded attempt is the only useful recovery after an already-started operation consumed
   * its physical-call budget. Keep that recovery behind the durable confirmation barrier instead of
   * allowing a new Generate request to bypass acknowledgement of the earlier provider work.
   */
  private static boolean retryConfirmationRequired(RuntimeException failure) {
    if (failure instanceof ProjectedCallBudgetExceededException) return true;
    return failure
            instanceof ProjectDocGeneratorService.PreTransportGenerationException preTransport
        && (preTransport.failure() instanceof ProjectedCallBudgetExceededException
            || (preTransport.failure() instanceof LlmDispatchPolicyException policy
                && policy.reason() == LlmDispatchPolicyException.Reason.BUDGET_EXHAUSTED));
  }

  private static String preTransportErrorCode(RuntimeException failure) {
    if (failure instanceof ProjectedCallBudgetExceededException) {
      return "PROJECT_DOC_BUDGET_EXCEEDED";
    }
    if (failure instanceof ByokContextUnavailableException || missingProviderCredential(failure)) {
      return "PROJECT_PROVIDER_NOT_CONFIGURED";
    }
    if (failure instanceof LlmDispatchPolicyException policy) {
      return switch (policy.reason()) {
        case CREDENTIAL_UNAVAILABLE -> "PROJECT_PROVIDER_NOT_CONFIGURED";
        case PROVIDER_NOT_ALLOWED, MODEL_NOT_ALLOWED, EXACT_ROUTE_VIOLATION ->
            "PROJECT_PROVIDER_INVALID";
        case BUDGET_EXHAUSTED -> "PROJECT_DOC_BUDGET_EXCEEDED";
        default -> "PROJECT_PROVIDER_POLICY_REJECTED";
      };
    }
    return "DOCUMENT_GENERATION_FAILED";
  }

  private static String preTransportSafeMessage(RuntimeException failure) {
    String code = preTransportErrorCode(failure);
    return switch (code) {
      case "PROJECT_DOC_BUDGET_EXCEEDED" ->
          "The document generation budget was exhausted before another provider request could"
              + " begin.";
      case "PROJECT_PROVIDER_NOT_CONFIGURED" ->
          "The selected project provider is not configured. Update provider settings and retry.";
      case "PROJECT_PROVIDER_INVALID" ->
          "The selected project provider or model is not valid for this operation.";
      case "PROJECT_PROVIDER_POLICY_REJECTED" ->
          "Provider policy rejected the document request before provider dispatch began.";
      default -> "Document generation could not start. Check project readiness and retry.";
    };
  }

  private static boolean missingProviderCredential(RuntimeException failure) {
    String message = failure.getMessage();
    if (message == null) return false;
    String normalized = message.toLowerCase(Locale.ROOT);
    return normalized.contains("api-key not configured")
        || normalized.contains("api key not configured")
        || normalized.contains("credential unavailable");
  }

  private static String postDispatchMessage(RuntimeException failure) {
    if (retryConfirmationRequired(failure)) {
      return "The bounded document attempt exhausted its provider-call budget after provider"
          + " dispatch began; delivery outcome may be uncertain. Confirm regeneration on the same"
          + " route to start a fresh bounded attempt; this may incur additional usage.";
    }
    if (failure instanceof ProjectDocGeneratorService.StaleSourceException) {
      return "The project architecture changed after provider dispatch began. Delivery outcome may"
          + " be uncertain, and no document was published. Confirm regeneration against the latest"
          + " project evidence on the same route; this may incur additional usage.";
    }
    String replayWarning =
        " No document was published after provider dispatch began; delivery outcome may be"
            + " uncertain. Retrying the same route may incur additional usage.";
    if (knownPostDispatchFailure(failure)) return safeMessage(failure) + replayWarning;
    return "No document was published after provider dispatch began; delivery outcome may be"
        + " uncertain. Retrying the same route may incur additional usage.";
  }
}
