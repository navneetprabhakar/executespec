package com.navneet.executespec.reviewjob;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.codereview.CodeReviewService;
import com.navneet.executespec.prreview.PrReviewService;
import com.navneet.executespec.repo.GitHubRepoService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Bounded V299 poller. The executors provide capacity only; database leases and fencing own work.
 */
@Component
@Slf4j
public class ReviewBackgroundWorker {

  static final Duration LEASE = Duration.ofMinutes(2);
  static final Duration RETRY_DELAY = Duration.ofSeconds(15);
  static final int PARALLELISM = 4;

  private final ReviewBackgroundJobStore store;
  private final CodeReviewService codeReviewService;
  private final PrReviewService prReviewService;
  private final ObjectMapper mapper;
  private final ReviewLlmOperationRoot operationRoot;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final String workerId = hostLabel() + ":" + UUID.randomUUID();
  private final ExecutorService executor =
      Executors.newFixedThreadPool(
          PARALLELISM,
          runnable -> {
            Thread thread = new Thread(runnable, "review-job-worker");
            thread.setDaemon(true);
            return thread;
          });
  private final ScheduledExecutorService heartbeatExecutor =
      Executors.newScheduledThreadPool(
          PARALLELISM,
          runnable -> {
            Thread thread = new Thread(runnable, "review-job-heartbeat");
            thread.setDaemon(true);
            return thread;
          });

  @Autowired
  public ReviewBackgroundWorker(
      ReviewBackgroundJobStore store,
      CodeReviewService codeReviewService,
      PrReviewService prReviewService,
      ObjectMapper mapper,
      ReviewLlmOperationRoot operationRoot) {
    this.store = store;
    this.codeReviewService = codeReviewService;
    this.prReviewService = prReviewService;
    this.mapper = mapper;
    this.operationRoot = operationRoot;
  }

  ReviewBackgroundWorker(
      ReviewBackgroundJobStore store,
      CodeReviewService codeReviewService,
      PrReviewService prReviewService,
      ObjectMapper mapper) {
    this(store, codeReviewService, prReviewService, mapper, null);
  }

  @Scheduled(fixedDelayString = "${review.jobs.poll-interval-millis:2000}")
  public void poll() {
    store.finishAbandonedCancellations();
    store.finishExhaustedLeases();
    int available = Math.max(0, PARALLELISM - inFlight.get());
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
      } catch (RejectedExecutionException rejected) {
        inFlight.decrementAndGet();
        store.failOrRequeue(lease.get(), "EXECUTOR_SATURATED", rejected.getMessage(), RETRY_DELAY);
      }
    }
  }

  void process(ReviewBackgroundJobStore.Lease lease) {
    AtomicBoolean leaseLost = new AtomicBoolean();
    AtomicBoolean cancelled = new AtomicBoolean();
    java.util.concurrent.atomic.AtomicReference<ReviewLlmOperationRoot.Active> operation =
        new java.util.concurrent.atomic.AtomicReference<>();
    ScheduledFuture<?> heartbeat =
        heartbeatExecutor.scheduleAtFixedRate(
            () -> {
              try {
                if (store.cancellationRequested(lease)) {
                  cancelled.set(true);
                } else if (!store.heartbeat(lease, LEASE)
                    || operation.get() != null
                        && !operationRoot.heartbeat(operation.get(), LEASE)) {
                  leaseLost.set(true);
                }
              } catch (RuntimeException error) {
                leaseLost.set(true);
                log.error("review job heartbeat failed job={}", lease.job().id(), error);
              }
            },
            10,
            20,
            TimeUnit.SECONDS);
    Runnable checkpoint =
        () -> {
          if (cancelled.get() || store.cancellationRequested(lease)) {
            throw new CancellationRequestedException();
          }
          if (leaseLost.get()) throw new LeaseLostException();
          if (!store.heartbeat(lease, LEASE)) throw new LeaseLostException();
          if (operation.get() != null && !operationRoot.heartbeat(operation.get(), LEASE)) {
            throw new LeaseLostException();
          }
        };
    try {
      JsonNode payload = mapper.readTree(lease.job().payload());
      checkpoint.run();
      if (lease.job().resultPayload() == null && operationRoot != null) {
        boolean confirmedRetry = "CONFIRMED_RETRY".equals(lease.job().lastErrorCode());
        operation.set(
            operationRoot
                .claim(
                    lease.job(), lease.owner() + ":" + lease.fencingToken(), LEASE, confirmedRetry)
                .orElseThrow(LogicalOperationUnavailableException::new));
      }
      switch (lease.job().type()) {
        case CODE_REVIEW -> {
          var result =
              lease.job().resultPayload() == null
                  ? codeReviewService.runDurableJob(
                      lease,
                      required(payload, "repoFullName"),
                      required(payload, "sha"),
                      operation.get() == null ? null : operation.get().context(),
                      checkpoint)
                  : mapper.readValue(
                      lease.job().resultPayload(), CodeReviewService.DurableCodeReviewResult.class);
          if (lease.job().resultPayload() == null
              && !store.stageResult(lease, mapper.writeValueAsString(result))) {
            throw new LeaseLostException();
          }
          checkpoint.run();
          codeReviewService.publishDurableResult(lease, result);
        }
        case PR_REVIEW -> {
          var result =
              lease.job().resultPayload() == null
                  ? prReviewService.runDurableJob(
                      lease,
                      required(payload, "repoFullName"),
                      payload.path("prNumber").asInt(),
                      required(payload, "headSha"),
                      mapper.treeToValue(
                          payload.path("snapshot"), GitHubRepoService.PullRequestSnapshot.class),
                      operation.get() == null ? null : operation.get().context(),
                      checkpoint)
                  : mapper.readValue(
                      lease.job().resultPayload(), PrReviewService.DurablePrReviewResult.class);
          if (lease.job().resultPayload() == null
              && !store.stageResult(lease, mapper.writeValueAsString(result))) {
            throw new LeaseLostException();
          }
          checkpoint.run();
          prReviewService.publishDurableResult(lease, result);
        }
      }
      if (operation.get() != null) operationRoot.complete(operation.get());
    } catch (CancellationRequestedException cancelledWork) {
      if (operationRoot != null && operation.get() != null) {
        operationRoot.requestCancellation(lease.job());
      }
      terminateOperation(operation.get(), "REVIEW_CANCELLED", "cancelled by user");
      store.finishCancellation(lease);
    } catch (LeaseLostException | ReviewBackgroundJobStore.LeaseFencedException lost) {
      log.warn("review job lease lost job={} token={}", lease.job().id(), lease.fencingToken());
    } catch (Exception failure) {
      terminateOperation(operation.get(), errorCode(failure), failure.getMessage());
      if (store.cancellationRequested(lease)) {
        store.finishCancellation(lease);
      } else {
        store.failOrRequeue(lease, errorCode(failure), failure.getMessage(), RETRY_DELAY);
      }
      log.error(
          "review job failed job={} type={} attempt={}",
          lease.job().id(),
          lease.job().type(),
          lease.job().attemptCount(),
          failure);
    } finally {
      heartbeat.cancel(false);
    }
  }

  private static String required(JsonNode payload, String field) {
    String value = payload.path(field).asText();
    if (value.isBlank()) throw new IllegalArgumentException("job payload missing " + field);
    return value;
  }

  private static String errorCode(Exception failure) {
    if (failure instanceof LogicalOperationUnavailableException) {
      return "LLM_OPERATION_NOT_REPLAYABLE";
    }
    if (failure instanceof IllegalArgumentException) return "INVALID_JOB_PAYLOAD";
    if (failure instanceof IllegalStateException) return "DEPENDENCY_UNAVAILABLE";
    return "REVIEW_EXECUTION_FAILED";
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
    heartbeatExecutor.shutdown();
  }

  private static String hostLabel() {
    String host = System.getenv("HOSTNAME");
    return host == null || host.isBlank() ? "local" : host.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  static final class CancellationRequestedException extends RuntimeException {}

  static final class LeaseLostException extends RuntimeException {}

  static final class LogicalOperationUnavailableException extends RuntimeException {}

  private void terminateOperation(
      ReviewLlmOperationRoot.Active operation, String code, String message) {
    if (operationRoot == null || operation == null) return;
    try {
      operationRoot.terminate(operation, code, message);
    } catch (RuntimeException terminalizationFailure) {
      log.error(
          "review LLM operation terminalization failed code={}", code, terminalizationFailure);
    }
  }
}
