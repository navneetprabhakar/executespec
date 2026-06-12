package com.navneet.executespec.terminalbench;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.terminalbench.TerminalBenchRunService.StartResult;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Operator-only endpoint to launch a {@link TerminalBenchRunService} remote-tool worker run that
 * drives the Terminal-Bench Harbor adapter (local hands / remote brain). Lives under {@code
 * /api/v2/test/**}, which {@code SecurityConfig} locks to SUPER_ADMIN, and is dev/local-only via
 * {@code @Profile}. RL-2026-06.
 */
@Profile({"local", "dev", "benchmark"})
@RestController
@RequestMapping("/api/v2/test/terminal-bench")
@RequiredArgsConstructor
public class TerminalBenchController {

  private final TerminalBenchRunService service;
  private final RunAccessGuard guard;

  /**
   * Request to launch a remote-tool worker run. {@code maxIterations} null or below the
   * Terminal-Bench floor uses the service's benchmark default. {@code wallClockSeconds} is the
   * per-task wall clock the adapter derives from the Harbor task's own {@code [agent]} timeout
   * (RL-2026-06 — see {@code TerminalBenchRunService.DEFAULT_WALL_CLOCK_SECONDS}); null falls back
   * to the service default, and the service clamps supplied values.
   */
  public record StartRequest(
      String instruction,
      String provider,
      String model,
      Integer maxIterations,
      Integer wallClockSeconds,
      Integer llmReadTimeoutSeconds,
      Integer transientRetryMaxAttempts) {}

  @PostMapping("/start")
  public StartResult start(
      @AuthenticationPrincipal AppUserDetails userDetails, @RequestBody StartRequest body) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth required");
    }
    if (body == null || body.instruction() == null || body.instruction().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "instruction required");
    }
    if (body.provider() == null || body.provider().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider required");
    }
    if (body.model() == null || body.model().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model required");
    }
    return service.start(
        userDetails,
        body.instruction(),
        body.provider(),
        body.model(),
        body.maxIterations(),
        body.wallClockSeconds(),
        body.llmReadTimeoutSeconds(),
        body.transientRetryMaxAttempts());
  }

  @DeleteMapping("/{runId}")
  public ResponseEntity<Void> cancel(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable String runId) {
    guard.requireRunAccess(runId, userDetails);
    return service.cancel(runId, "Terminal-Bench adapter interrupted")
        ? ResponseEntity.ok().build()
        : ResponseEntity.notFound().build();
  }
}
