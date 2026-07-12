package com.navneet.executespec.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.agent.ProviderDispatchAuthorization;
import com.navneet.executespec.benchmark.LowCodingFixtureHarness;
import com.navneet.executespec.benchmark.LowCodingFixtureHarness.Evidence;
import com.navneet.executespec.engine.WorkerAgent;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** Operator-only local harness for a deterministic LOW coding fixture. */
@Profile({"local", "dev", "benchmark"})
@RestController
@RequestMapping("/api/v2/test")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class LowCodingFixtureController {

  private final WorkerAgent worker;
  private final ProviderDispatchAuthorization dispatchAuthorization;

  /**
   * Run one fixed-repository LOW task. The ledger reservation must be created before this call; the
   * canonical router guard enforces it before every provider request.
   */
  @GetMapping("/low-coding")
  public Evidence run(
      @RequestParam String provider,
      @RequestParam String model,
      @RequestParam String runId,
      @RequestParam String reservationId)
      throws IOException {
    try {
      dispatchAuthorization.authorizeReservedTest(reservationId, provider, model, runId);
    } catch (IllegalStateException denied) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "provider reservation denied", denied);
    }
    Path workspace = Files.createTempDirectory("executespec-low-coding-");
    try {
      return LowCodingFixtureHarness.run(worker, provider, model, runId, reservationId, workspace);
    } finally {
      deleteTree(workspace);
    }
  }

  private static void deleteTree(Path root) {
    try (var walk = Files.walk(root)) {
      walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (IOException ignored) {
      // The fixture is disposable; failed cleanup must not hide the worker evidence.
    }
  }
}
