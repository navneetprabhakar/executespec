package com.navneet.executespec.testgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * RR-1587 — the previous shape drained stdout to EOF before calling {@code waitFor(timeout)}, so
 * the documented timeouts could never fire. This pins that a child which outlives its deadline is
 * actually destroyed, and that a child holding stdout open does not defeat the bound.
 */
@DisabledOnOs(OS.WINDOWS)
class BoundedProcessTest {

  @Test
  void aChildThatOutlivesItsDeadlineIsDestroyed() throws Exception {
    ProcessBuilder pb = new ProcessBuilder("sh", "-c", "sleep 30");
    pb.redirectErrorStream(true);

    Instant start = Instant.now();
    BoundedProcess.Outcome outcome = BoundedProcess.run(pb, Duration.ofSeconds(1));
    Duration elapsed = Duration.between(start, Instant.now());

    assertFalse(outcome.finished(), "deadline must fire rather than blocking on the child");
    assertEquals(-1, outcome.exitCode());
    assertTrue(elapsed.toSeconds() < 15, "returned in " + elapsed);
  }

  @Test
  void aChildHoldingStdoutOpenStillHitsTheDeadline() throws Exception {
    // Emits one line then sleeps with stdout still open — the exact shape that made the old
    // drain-then-waitFor ordering block forever.
    ProcessBuilder pb = new ProcessBuilder("sh", "-c", "echo started; sleep 30");
    pb.redirectErrorStream(true);

    BoundedProcess.Outcome outcome = BoundedProcess.run(pb, Duration.ofSeconds(1));

    assertFalse(outcome.finished());
  }

  @Test
  void aChildThatExitsInTimeReportsItsStatusAndOutput() throws Exception {
    ProcessBuilder pb = new ProcessBuilder("sh", "-c", "echo hello; exit 3");
    pb.redirectErrorStream(true);

    BoundedProcess.Outcome outcome = BoundedProcess.run(pb, Duration.ofSeconds(30));

    assertTrue(outcome.finished());
    assertEquals(3, outcome.exitCode());
    assertTrue(outcome.output().contains("hello"));
  }

  @Test
  void drainsMoreThanTheOsPipeBufferWhileTheChildRuns() throws Exception {
    // Waiting before reading deadlocks once the pipe fills. Keep this comfortably above the
    // common 64 KiB pipe capacity so every tool that delegates here inherits a real regression
    // gate for the live pytest failure seen on 2026-07-24.
    ProcessBuilder pb =
        new ProcessBuilder(
            "sh", "-c", "i=0; while [ $i -lt 12000 ]; do echo output-$i; i=$((i+1)); done");
    pb.redirectErrorStream(true);

    BoundedProcess.Outcome outcome = BoundedProcess.run(pb, Duration.ofSeconds(10));

    assertTrue(outcome.finished());
    assertEquals(0, outcome.exitCode());
    assertTrue(outcome.output().length() > 65_536);
    assertTrue(outcome.output().contains("output-11999"));
  }
}
