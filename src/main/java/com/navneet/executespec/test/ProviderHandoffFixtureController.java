package com.navneet.executespec.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.navneet.executespec.agent.ProviderDispatchAuthorization;
import com.navneet.executespec.benchmark.ProviderHandoffFixtureHarness;
import com.navneet.executespec.benchmark.ProviderHandoffFixtureHarness.Evidence;
import com.navneet.executespec.engine.WorkerAgent;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Operator-only, local/dev/benchmark provider-handoff fixture endpoint. It is intentionally a POST
 * with a complete route contract: no implicit fixture, fallback, reservation, or failover policy.
 */
@Profile({"local", "dev", "benchmark"})
@RestController
@RequestMapping("/api/v2/test")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class ProviderHandoffFixtureController {

  private final WorkerAgent worker;
  private final ProviderDispatchAuthorization dispatchAuthorization;

  public record Request(
      String fixtureId,
      String fixtureVersion,
      String primaryProvider,
      String primaryModel,
      String primaryReservationId,
      String fallbackProvider,
      String fallbackModel,
      String fallbackReservationId,
      Set<String> allowedProviders,
      ProviderHandoffFixtureHarness.HandoffTrigger handoffTrigger) {}

  @PostMapping("/provider-handoff")
  public Evidence run(@RequestBody Request request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request is required");
    }
    Path workspace = null;
    try {
      workspace = Files.createTempDirectory("executespec-provider-handoff-");
      return ProviderHandoffFixtureHarness.run(
          worker,
          new ProviderHandoffFixtureHarness.Request(
              request.fixtureId(),
              request.fixtureVersion(),
              request.primaryProvider(),
              request.primaryModel(),
              request.primaryReservationId(),
              request.fallbackProvider(),
              request.fallbackModel(),
              request.fallbackReservationId(),
              request.allowedProviders(),
              request.handoffTrigger()),
          "provider-handoff-" + java.util.UUID.randomUUID(),
          workspace,
          dispatchAuthorization::authorizeReservedTest);
    } catch (IllegalArgumentException denied) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, denied.getMessage(), denied);
    } catch (IOException failure) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "fixture failed", failure);
    } finally {
      if (workspace != null) deleteTree(workspace);
    }
  }

  private static void deleteTree(Path root) {
    try (var walk = Files.walk(root)) {
      walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (IOException ignored) {
      // Disposable operator evidence is returned before cleanup; cleanup failure must not hide it.
    }
  }
}
