package com.navneet.executespec.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Fail-closed bridge between goal-scoped live provider tests and the durable provider budget
 * ledger. Production customer traffic does not enable test mode; controlled live tests must carry
 * an exact reservation id whose provider/model/run scope is atomically authorized by the ledger.
 */
final class ProviderTestBudgetGuard {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private ProviderTestBudgetGuard() {}

  static void authorize(String reservationId, String provider, String model, String runId) {
    if (reservationId == null || reservationId.isBlank()) {
      throw new IllegalStateException(
          "Goal live-test mode requires executespec.live-llm.reservation-id");
    }
    Path script = Path.of("scripts", "provider-budget-ledger.mjs").toAbsolutePath().normalize();
    if (!Files.isRegularFile(script)) {
      throw new IllegalStateException("Provider budget ledger script not found: " + script);
    }
    Process process;
    try {
      process =
          new ProcessBuilder(
                  "node",
                  script.toString(),
                  "authorize-dispatch",
                  "--reservation",
                  reservationId,
                  "--provider",
                  provider,
                  "--model",
                  model,
                  "--run",
                  runId)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
    } catch (IOException error) {
      throw new IllegalStateException("Could not start provider budget authorization", error);
    }
    try {
      if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("Provider budget authorization timed out");
      }
      if (process.exitValue() != 0) {
        String detail =
            new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        throw new IllegalStateException("Provider budget authorization denied: " + detail);
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new IllegalStateException("Provider budget authorization was interrupted", error);
    } catch (IOException error) {
      throw new IllegalStateException("Could not read provider budget authorization result", error);
    }
  }
}
