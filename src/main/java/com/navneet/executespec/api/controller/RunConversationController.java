package com.navneet.executespec.api.controller;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.chat.ApplyResultStore;
import com.navneet.executespec.chat.ApplyService;
import com.navneet.executespec.chat.ChatService;
import com.navneet.executespec.run.RunIterationService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Post-run conversation endpoints. V2-native: {@code POST/GET /runs/{id}/chat} are wired to {@link
 * ChatService}. Apply runs a bounded worker pass in-place; iterate creates a lineage-linked BUILD
 * run through the normal asynchronous pipeline.
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Slf4j
public class RunConversationController {

  /**
   * RR-599 — upper bound on a single run-chat turn. The endpoint had no request-length contract, so
   * a run writer could submit very large turns; the 50-message cap bounds count, not size.
   */
  static final int MAX_CHAT_MESSAGE_CHARS = 24_000;

  private final RunAccessGuard guard;
  private final ChatService chatService;
  private final ApplyService applyService;
  private final ApplyResultStore applyResultStore;
  private final RunIterationService runIterationService;

  /**
   * Apply-suggestion runs a 25-iteration WorkerAgent loop with Sonnet-grade calls (~10–20 s each
   * after the v2.6.1 tier bump). Holding that on a Tomcat handler thread trips Hikari's
   * connection-leak detector via OSIV (the EM stays bound to the request thread for the request's
   * lifetime).
   *
   * <p>Dispatching on this executor makes the controller return a {@link CompletableFuture}, which
   * signals Spring MVC to release the Tomcat thread + unbind the OSIV-held EM immediately after
   * <em>dispatch</em>, not after the full apply completes. The worker loop then runs on a thread
   * that was never OSIV-bound — individual {@code runRepo.findById} calls still open short Hikari
   * connections, but those are released per call rather than held for the entire loop.
   */
  /**
   * Apply-suggestion executor — sized {@code clamp(4, 12, cores × 2)}. Apply paths are bursty (a
   * user can fire several apply suggestions on the same run from chat); the 2× multiplier gives the
   * pool headroom on multi-core hosts without saturating Hikari since each call still opens its own
   * short DB connection.
   */
  private final ExecutorService applyExecutor =
      Executors.newFixedThreadPool(
          com.navneet.executespec.util.PoolSizing.coreMultiple(2, 4, 12),
          new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
              Thread t = new Thread(r, "run-apply-" + seq.incrementAndGet());
              t.setDaemon(true);
              return t;
            }
          });

  @PreDestroy
  void shutdownApplyExecutor() {
    applyExecutor.shutdown();
    try {
      if (!applyExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
        log.warn("applyExecutor did not terminate in 30s — forcing shutdown");
        applyExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      applyExecutor.shutdownNow();
    }
  }

  public record ChatMessageBody(String message) {}

  public record ChatResponseBody(
      String reply, int messageCount, int maxMessages, boolean complete, String incompleteReason) {}

  public record ChatHistoryItem(String role, String content) {}

  public record ApplyBody(String suggestion, String source) {}

  public record ApplyTaskBody(String taskId, String title) {}

  public record ApplyResponseBody(
      int taskCount, List<ApplyTaskBody> tasks, String workerOutcome, String summary) {}

  public record IterateBody(String userRequest) {}

  public record IterateResponseBody(
      String runId, String sourceRunId, String status, String aiProvider) {}

  @GetMapping("/{runId}/chat")
  public ResponseEntity<List<ChatHistoryItem>> getHistory(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);
    List<ChatHistoryItem> items =
        chatService.history(runId).stream()
            .map(m -> new ChatHistoryItem(m.role(), m.content()))
            .toList();
    return ResponseEntity.ok(items);
  }

  @PostMapping("/{runId}/chat")
  public ResponseEntity<ChatResponseBody> sendMessage(
      @PathVariable String runId,
      @RequestBody ChatMessageBody body,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    if (body == null || body.message() == null || body.message().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
    }
    // RR-599 — reject an oversize turn at the boundary; deeper history-byte compaction inside
    // ChatService remains tracked separately.
    if (body.message().length() > MAX_CHAT_MESSAGE_CHARS) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "message exceeds the " + MAX_CHAT_MESSAGE_CHARS + "-character limit.");
    }
    try {
      ChatService.SendResult r = chatService.send(runId, body.message());
      return ResponseEntity.ok(
          new ChatResponseBody(
              r.reply(), r.messageCount(), r.maxMessages(), r.complete(), r.incompleteReason()));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    } catch (ResponseStatusException e) {
      // Managed-AI/provider admission carries an intentional status such as 402. Do not collapse
      // that truthful refusal into the generic 500 below.
      throw e;
    } catch (RuntimeException e) {
      log.error("Chat send failed for run={}: {}", runId, e.getMessage(), e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Chat failed: " + e.getMessage());
    }
  }

  @PostMapping("/{runId}/apply")
  public CompletableFuture<ResponseEntity<ApplyResponseBody>> applySuggestion(
      @PathVariable String runId,
      @RequestBody ApplyBody body,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    if (body == null || body.suggestion() == null || body.suggestion().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "suggestion is required");
    }
    final String suggestion = body.suggestion();
    final String source = body.source() == null ? "COPILOT" : body.source();

    return CompletableFuture.supplyAsync(
        () -> {
          try {
            ApplyService.ApplyResult r = applyService.apply(runId, suggestion, source);
            return ResponseEntity.ok(
                new ApplyResponseBody(
                    r.taskCount(),
                    r.tasks().stream().map(t -> new ApplyTaskBody(t.taskId(), t.title())).toList(),
                    r.workerOutcome(),
                    r.summary()));
          } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
          } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
          } catch (RuntimeException e) {
            log.error("Apply failed for run={}: {}", runId, e.getMessage(), e);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Apply failed: " + e.getMessage());
          }
        },
        applyExecutor);
  }

  /**
   * Polling endpoint for apply-suggestion lifecycle state. Companion to {@code POST /apply} — lets
   * the UI re-attach to the apply outcome after the original POST response stream is severed
   * (broken pipe, browser navigation, Monaco crash, etc.). Returns the latest snapshot from {@link
   * ApplyResultStore} (Redis, 1h TTL); 404 when nothing has been recorded for this run in the last
   * hour.
   *
   * <p>Status values: {@code RUNNING} (worker in flight) · {@code COMPLETED} (worker terminated;
   * see {@code outcome} for details) · {@code FAILED} (dispatch threw before the worker even
   * started, see {@code error}).
   */
  @GetMapping("/{runId}/apply/status")
  public ResponseEntity<ApplyResultStore.ApplyState> applyStatus(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);
    return applyResultStore
        .get(runId)
        .map(ResponseEntity::ok)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No apply result recorded for run="
                        + runId
                        + " in the last "
                        + ApplyResultStore.RESULT_TTL.toHours()
                        + "h"));
  }

  @PostMapping("/{runId}/iterate")
  public ResponseEntity<IterateResponseBody> iterateRun(
      @PathVariable String runId,
      @RequestBody IterateBody body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    if (body == null || body.userRequest() == null || body.userRequest().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userRequest is required");
    }
    if (body.userRequest().length() > MAX_CHAT_MESSAGE_CHARS) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "userRequest exceeds the " + MAX_CHAT_MESSAGE_CHARS + "-character limit.");
    }
    RunIterationService.IterationResult result =
        runIterationService.iterate(runId, body.userRequest(), idempotencyKey, userDetails);
    var child = result.run();
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            new IterateResponseBody(
                child.getRunId(),
                child.getSourceRunId(),
                child.getStatus(),
                child.getAiProvider()));
  }
}
