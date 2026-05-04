package com.navneet.executespec.api.controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.v2.chat.ApplyResultStore;
import com.navneet.executespec.v2.chat.ApplyService;
import com.navneet.executespec.v2.chat.ChatService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Post-run conversation endpoints. V2-native: {@code POST/GET /runs/{id}/chat} are wired to {@link
 * ChatService}. {@code /apply} and {@code /iterate} are still 410 Gone pending a V2
 * re-implementation (they need to spin up a new run from the current workspace, which is more
 * plumbing than chat itself).
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Slf4j
public class RunConversationController {

  private final RunAccessGuard guard;
  private final ChatService chatService;
  private final ApplyService applyService;
  private final ApplyResultStore applyResultStore;

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
  private final ExecutorService applyExecutor =
      Executors.newFixedThreadPool(
          4,
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

  public record ChatResponseBody(String reply, int messageCount, int maxMessages) {}

  public record ChatHistoryItem(String role, String content) {}

  public record ApplyBody(String suggestion, String source) {}

  public record ApplyTaskBody(String taskId, String title) {}

  public record ApplyResponseBody(
      int taskCount, List<ApplyTaskBody> tasks, String workerOutcome, String summary) {}

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
    guard.requireRunAccess(runId, userDetails);
    if (body == null || body.message() == null || body.message().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
    }
    try {
      ChatService.SendResult r = chatService.send(runId, body.message());
      return ResponseEntity.ok(new ChatResponseBody(r.reply(), r.messageCount(), r.maxMessages()));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
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
    guard.requireRunAccess(runId, userDetails);
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
  public ResponseEntity<Map<String, String>> iterateRun(
      @PathVariable String runId,
      @RequestBody Map<String, String> body,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);
    throw new ResponseStatusException(
        HttpStatus.GONE, "Iterate-from-run not yet supported in V2 — submit a fresh run instead.");
  }
}
