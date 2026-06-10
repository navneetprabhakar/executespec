package com.navneet.executespec.benchmark;

import java.math.BigDecimal;
import java.util.List;

import com.navneet.executespec.auth.security.AppUserDetails;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;

/**
 * The COMMON, suite-agnostic in-app benchmark <b>tracking</b> API (RL-2026-06, see {@code
 * docs/plans/benchmark-closed-loop-2026-06-09.md}). An external verifier/harness (Harbor for
 * Terminal-Bench; SWE-bench / ProgramBench next) <b>publishes events</b> here as it scores tasks +
 * submits; ExecuteSpec persists + re-broadcasts (SSE) + renders the in-login {@code /benchmark}
 * dashboard. ExecuteSpec is the event sink + display, <b>not</b> an orchestrator — see {@link
 * BenchmarkTrackingService}.
 *
 * <p>Operator-only: SUPER_ADMIN (the {@code /api/v2/test/**} matcher is SUPER_ADMIN-gated in {@code
 * SecurityConfig}, plus the class {@code @PreAuthorize}) and
 * {@code @Profile({"local","dev","benchmark"})} — present on local/dev + the dedicated benchmark
 * env, absent in prod. Distinct from the SWE-bench <b>execution</b> harness {@link
 * BenchmarkController} at {@code /api/admin/benchmark}.
 */
@Profile({"local", "dev", "benchmark"})
@RestController
@RequestMapping("/api/v2/test/benchmark")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class BenchmarkTrackingController {

  private final BenchmarkTrackingService service;

  /** Body for {@code POST /start}. */
  public record StartRequest(
      String suite,
      String dataset,
      String provider,
      String model,
      Integer k,
      Integer totalTasks,
      String harborJobName) {}

  /** Body for {@code POST /{id}/event} — one event published by the external verifier. */
  public record EventRequest(
      String type,
      String taskName,
      Integer attempt,
      Integer reward,
      String esRunId,
      BigDecimal costUsd,
      Long inputTokens,
      Long outputTokens,
      String message,
      String payload) {}

  /** Run + its task results + full event stream (the {@code GET /{id}} detail view). */
  public record Detail(
      BenchmarkRun run, List<BenchmarkTaskResult> tasks, List<BenchmarkEvent> events) {}

  // ── Create ──────────────────────────────────────────────────────────────

  /**
   * Register a new tracked benchmark run. Returns the created {@link BenchmarkRun} (with its id).
   */
  @PostMapping("/start")
  public BenchmarkRun start(
      @AuthenticationPrincipal AppUserDetails user, @RequestBody StartRequest body) {
    if (body == null || body.suite() == null || !BenchmarkSuite.isKnown(body.suite())) {
      throw bad(
          "valid suite required (one of "
              + java.util.Arrays.toString(BenchmarkSuite.values())
              + ")");
    }
    if (blank(body.provider())) {
      throw bad("provider required");
    }
    if (blank(body.model())) {
      throw bad("model required");
    }
    return service.start(
        body.suite(),
        body.dataset(),
        body.provider(),
        body.model(),
        body.k() == null ? 1 : body.k(),
        body.totalTasks() == null ? 0 : body.totalTasks(),
        body.harborJobName(),
        userId(user));
  }

  // ── Ingest ──────────────────────────────────────────────────────────────

  /**
   * Ingest one published event for a run. Idempotent for {@code TASK_SCORED} on (task, attempt).
   */
  @PostMapping("/{id}/event")
  public BenchmarkRun event(
      @AuthenticationPrincipal AppUserDetails user,
      @PathVariable String id,
      @RequestBody EventRequest body) {
    if (body == null || blank(body.type())) {
      throw bad("type required");
    }
    try {
      return service.ingest(
          id,
          body.type(),
          body.taskName(),
          body.attempt(),
          body.reward(),
          body.esRunId(),
          body.costUsd(),
          body.inputTokens(),
          body.outputTokens(),
          body.message(),
          body.payload());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  // ── Reads ───────────────────────────────────────────────────────────────

  /** Most-recent 50 tracked runs (newest first). */
  @GetMapping
  public List<BenchmarkRun> list(@AuthenticationPrincipal AppUserDetails user) {
    return service.list();
  }

  /** One run + its task results + full event stream. */
  @GetMapping("/{id}")
  public Detail detail(@AuthenticationPrincipal AppUserDetails user, @PathVariable String id) {
    try {
      return new Detail(service.get(id), service.taskResults(id), service.events(id));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  /** Live SSE feed for one run (reuses the shared broadcaster — Redis-backed + replay). */
  @GetMapping("/{id}/events")
  public SseEmitter stream(
      @AuthenticationPrincipal AppUserDetails user,
      @PathVariable String id,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    return service.subscribe(id, lastEventId);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static String userId(AppUserDetails u) {
    return (u != null && u.getUser() != null) ? String.valueOf(u.getUser().getId()) : null;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
