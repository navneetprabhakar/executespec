package com.navneet.executespec.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * RR-999 — every gcloud subprocess timeout used to start only after a blocking {@code readAllBytes}
 * of stdout, so a hung gcloud that kept its pipe open never reached the deadline. The deploy paths
 * now drain stdout and stderr on separate threads with the deadline started before the reads. This
 * pins that a child which outlives its deadline is destroyed rather than pinning the caller thread,
 * including a child that emits output on both streams and then holds them open.
 */
@DisabledOnOs(OS.WINDOWS)
class CloudRunClientProcessTest {

  @Test
  void separateDrainerDestroysAChildThatOutlivesItsDeadline() throws Exception {
    ProcessBuilder pb = new ProcessBuilder("sh", "-c", "sleep 30");

    Instant start = Instant.now();
    CloudRunClient.GcloudResult result =
        CloudRunClient.runGcloudSeparate(pb, Duration.ofSeconds(1));
    Duration elapsed = Duration.between(start, Instant.now());

    assertFalse(result.finished(), "deadline must fire rather than blocking on the child");
    assertEquals(-1, result.exitCode());
    assertTrue(elapsed.toSeconds() < 15, "returned in " + elapsed);
  }

  @Test
  void separateDrainerHitsDeadlineEvenWhenBothStreamsStayOpen() throws Exception {
    // stdout AND stderr each get one line, then the child sleeps with both descriptors open — the
    // exact shape (JSON on stdout, progress on stderr) that made the old read-then-waitFor block.
    ProcessBuilder pb = new ProcessBuilder("sh", "-c", "echo out; echo err 1>&2; sleep 30");

    CloudRunClient.GcloudResult result =
        CloudRunClient.runGcloudSeparate(pb, Duration.ofSeconds(1));

    assertFalse(result.finished());
  }

  @Test
  void separateDrainerKeepsStdoutAndStderrApartForCleanJson() throws Exception {
    ProcessBuilder pb =
        new ProcessBuilder("sh", "-c", "echo '{\"uri\":\"x\"}'; echo progress 1>&2; exit 0");

    CloudRunClient.GcloudResult result =
        CloudRunClient.runGcloudSeparate(pb, Duration.ofSeconds(30));

    assertTrue(result.finished());
    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("uri"), "JSON stays on stdout");
    assertFalse(result.stdout().contains("progress"), "stderr must not pollute stdout JSON");
    assertTrue(result.stderr().contains("progress"));
  }
}
