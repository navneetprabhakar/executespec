package com.navneet.executespec.harness;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import com.navneet.executespec.testgen.BoundedProcess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * RR-651 — {@code VendoredPathGuard.runGit} drained the git child to EOF before starting its timed
 * wait, so a hung git that kept its output descriptor open blocked the pipeline thread past every
 * deadline. It now runs through {@link BoundedProcess} via {@link VendoredPathGuard#runBoundedGit},
 * which surfaces a timeout as an {@link IOException} instead of hanging. This pins that a child
 * outliving its deadline is destroyed and reported, not awaited forever.
 */
@DisabledOnOs(OS.WINDOWS)
class VendoredPathGuardTimeoutTest {

  @Test
  void aHungChildIsTimedOutRatherThanBlockingForever() {
    ProcessBuilder pb = new ProcessBuilder("sh", "-c", "sleep 30").redirectErrorStream(true);

    Instant start = Instant.now();
    IOException thrown =
        assertThrows(IOException.class, () -> VendoredPathGuard.runBoundedGit(pb, 1, "sleep-test"));
    Duration elapsed = Duration.between(start, Instant.now());

    assertTrue(thrown.getMessage().contains("timed out"), thrown.getMessage());
    assertTrue(elapsed.toSeconds() < 15, "returned in " + elapsed);
  }

  @Test
  void aChildHoldingItsPipeOpenStillHitsTheDeadline() {
    // Emits a line then sleeps with stdout still open — the exact drain-then-waitFor hang shape.
    ProcessBuilder pb =
        new ProcessBuilder("sh", "-c", "echo started; sleep 30").redirectErrorStream(true);

    assertThrows(IOException.class, () -> VendoredPathGuard.runBoundedGit(pb, 1, "hold-open-test"));
  }

  @Test
  void aFastChildReturnsItsOutcome() throws Exception {
    ProcessBuilder pb = new ProcessBuilder("sh", "-c", "echo ok; exit 0").redirectErrorStream(true);

    BoundedProcess.Outcome outcome = VendoredPathGuard.runBoundedGit(pb, 30, "fast-test");

    assertTrue(outcome.finished());
    assertTrue(outcome.output().contains("ok"));
  }
}
