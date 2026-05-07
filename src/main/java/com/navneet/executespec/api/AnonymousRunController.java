package com.navneet.executespec.api;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.navneet.executespec.anonrun.AnonymousRunService;
import com.navneet.executespec.anonrun.AnonymousRunSession;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-111 — anonymous-trial endpoint. Thin HTTP shell over {@link AnonymousRunService} (which holds
 * the rate-limit / abuse-heuristic / dispatch pipeline so it's testable without MockMvc).
 *
 * <p>Routes:
 *
 * <ul>
 *   <li>{@code POST /api/v3/anonymous-runs} — submit a spec; returns 202 with a sessionToken (or
 *       429 / 400 with a structured reason)
 *   <li>{@code GET /api/v3/anonymous-runs/{sessionToken}} — poll session status
 * </ul>
 *
 * Real LLM dispatch is gated behind {@code v3.anonymous-trial.real-dispatch} (default OFF). When
 * OFF, a stub COMPLETE response is returned so the UI flow is testable end-to-end without burning
 * LLM tokens.
 */
@Slf4j
@RestController
@RequestMapping("/api/v3")
@RequiredArgsConstructor
public class AnonymousRunController {

  private final AnonymousRunService service;

  @PostMapping("/anonymous-runs")
  public ResponseEntity<AnonymousRunResponse> submit(
      @Valid @RequestBody AnonymousRunRequest body, HttpServletRequest req) {
    AnonymousRunService.Outcome outcome = service.submit(body.spec(), clientIp(req));
    return switch (outcome) {
      case AnonymousRunService.Outcome.Accepted a ->
          ResponseEntity.status(202).body(AnonymousRunResponse.fromSession(a.session()));
      case AnonymousRunService.Outcome.Rejected r ->
          ResponseEntity.status(r.httpStatus())
              .body(AnonymousRunResponse.error(r.code(), r.message()));
    };
  }

  @GetMapping("/anonymous-runs/{sessionToken}")
  public ResponseEntity<AnonymousRunResponse> status(@PathVariable String sessionToken) {
    Optional<AnonymousRunSession> s = service.findSession(sessionToken);
    if (s.isEmpty()) {
      return ResponseEntity.status(404)
          .body(
              AnonymousRunResponse.error(
                  "not_found", "Session not found or expired. Sign up to keep your work."));
    }
    return ResponseEntity.ok(AnonymousRunResponse.fromSession(s.get()));
  }

  private static String clientIp(HttpServletRequest req) {
    String xff = req.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      int comma = xff.indexOf(',');
      return (comma > 0 ? xff.substring(0, comma) : xff).trim();
    }
    return req.getRemoteAddr();
  }

  public record AnonymousRunRequest(@NotBlank @Size(max = 4_000) String spec) {}

  public record AnonymousRunResponse(
      String sessionToken,
      String runId,
      String status,
      String message,
      String pollUrl,
      String error) {
    public static AnonymousRunResponse error(String code, String msg) {
      return new AnonymousRunResponse(null, null, null, msg, null, code);
    }

    public static AnonymousRunResponse fromSession(AnonymousRunSession s) {
      return new AnonymousRunResponse(
          s.token(),
          s.runId(),
          s.status(),
          s.message(),
          "/api/v3/anonymous-runs/" + s.token(),
          null);
    }
  }
}
