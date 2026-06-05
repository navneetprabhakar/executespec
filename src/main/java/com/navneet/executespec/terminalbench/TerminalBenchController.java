package com.navneet.executespec.terminalbench;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.terminalbench.TerminalBenchRunService.StartResult;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@Profile({"local", "dev"})
@RestController
@RequestMapping("/api/v2/test/terminal-bench")
@RequiredArgsConstructor
public class TerminalBenchController {

  private final TerminalBenchRunService service;

  /**
   * Request to launch a remote-tool worker run. {@code maxIterations} null → worker default (40).
   */
  public record StartRequest(
      String instruction, String provider, String model, Integer maxIterations) {}

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
        userDetails, body.instruction(), body.provider(), body.model(), body.maxIterations());
  }
}
