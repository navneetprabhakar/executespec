package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three properties the sandbox exec bound rests on, exercised against real processes because
 * none of them can be proven by reading the argument vector.
 */
@DisabledOnOs(OS.WINDOWS)
class SandboxProcessTest {

  @Test
  @Timeout(30)
  void streamsAreCapturedSeparatelyWithTheExitStatus() {
    SandboxProcess.Outcome outcome =
        SandboxProcess.run(
            List.of("sh", "-c", "echo to-out; echo to-err 1>&2; exit 3"), Duration.ofSeconds(20));

    assertThat(outcome.finished()).isTrue();
    assertThat(outcome.exitCode()).isEqualTo(3);
    assertThat(outcome.stdout()).contains("to-out").doesNotContain("to-err");
    assertThat(outcome.stderr()).contains("to-err").doesNotContain("to-out");
    assertThat(outcome.truncated()).isFalse();
  }

  @Test
  @Timeout(60)
  void aRunawayWriterIsCappedAndSaysSoRatherThanExhaustingTheHeap() {
    // Well past the per-stream ceiling, and still writing after it: the drain must keep consuming
    // so the child is never blocked on a full pipe, while the retained text stops growing.
    SandboxProcess.Outcome outcome =
        SandboxProcess.run(
            List.of(
                "sh",
                "-c",
                "i=0; while [ $i -lt 8000 ]; do echo " + "x".repeat(80) + "; i=$((i+1)); done"),
            Duration.ofSeconds(45));

    assertThat(outcome.finished()).as("the child must not deadlock on a full pipe").isTrue();
    assertThat(outcome.truncated()).isTrue();
    assertThat(outcome.stdout().length())
        .isLessThanOrEqualTo(SandboxProcess.MAX_CAPTURED_CHARS_PER_STREAM);
  }

  @Test
  @Timeout(60)
  void theTimeoutIsReachableWhileTheChildIsStillWriting() {
    // The defect this guards: draining to EOF on the calling thread makes waitFor(timeout) always
    // run against an already-dead process, so a child that never exits pins the caller forever.
    long startedAt = System.nanoTime();
    SandboxProcess.Outcome outcome =
        SandboxProcess.run(
            List.of("sh", "-c", "while true; do echo still-here; sleep 1; done"),
            Duration.ofSeconds(2));
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    assertThat(outcome.finished()).isFalse();
    assertThat(outcome.exitCode()).isEqualTo(-1);
    assertThat(outcome.detail()).contains("wall-clock");
    assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
    // Whatever was produced before the deadline is still honest partial output.
    assertThat(outcome.stdout()).contains("still-here");
  }

  @Test
  @Timeout(60)
  void expiryDestroysGrandchildrenAndNotOnlyTheDirectChild(@TempDir Path temp) throws Exception {
    // A prior lane found destroyForcibly leaving node grandchildren alive. Here the direct child is
    // a shell and the long sleep is its child; killing only the shell would leave the sleep
    // running.
    Path pidFile = temp.resolve("grandchild.pid");
    SandboxProcess.Outcome outcome =
        SandboxProcess.run(
            List.of(
                "sh",
                "-c",
                "sleep 120 & echo $! > " + pidFile.toAbsolutePath() + "; echo started; wait"),
            Duration.ofSeconds(3));

    assertThat(outcome.finished()).isFalse();
    assumeTrue(Files.exists(pidFile), "shell did not report a grandchild pid");
    long grandchildPid = Long.parseLong(Files.readString(pidFile).trim());

    // The kill is asynchronous at the OS level; allow a bounded settling window.
    boolean dead = false;
    for (int attempt = 0; attempt < 50 && !dead; attempt++) {
      Optional<ProcessHandle> handle = ProcessHandle.of(grandchildPid);
      dead = handle.isEmpty() || !handle.get().isAlive();
      if (!dead) {
        Thread.sleep(100);
      }
    }
    assertThat(dead).as("the grandchild survived the timeout kill").isTrue();
  }

  @Test
  @Timeout(30)
  void aCommandThatCannotStartIsUnfinishedRatherThanAFailingCommand() {
    SandboxProcess.Outcome outcome =
        SandboxProcess.run(
            List.of("es-no-such-binary-" + System.nanoTime()), Duration.ofSeconds(10));

    // Nothing ran, so there is no exit status to report and finished() must not claim one.
    assertThat(outcome.finished()).isFalse();
    assertThat(outcome.exitCode()).isEqualTo(-1);
    assertThat(outcome.detail()).isNotBlank();
  }
}
