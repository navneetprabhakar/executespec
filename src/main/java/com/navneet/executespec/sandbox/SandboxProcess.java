package com.navneet.executespec.sandbox;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs a host child process under a wall-clock bound that can actually fire, capturing stdout and
 * stderr separately and boundedly.
 *
 * <p><strong>Why this exists rather than draining inline.</strong> The obvious shape — read the
 * child's output to EOF on this thread, then call {@code waitFor(timeout)} — makes the timeout
 * unreachable, because stdout only closes when the child exits. {@code waitFor} is then always
 * reached on an already-dead process and a hung child pins the calling thread forever. That exact
 * defect was found and fixed elsewhere in this codebase (RR-1587, {@code testgen/BoundedProcess});
 * this is the sandbox-package equivalent, kept here rather than shared because the sandbox package
 * owns its own execution boundary and must not depend on the test-generation package for it. It
 * differs in keeping the two streams apart, which the sandbox exec contract requires.
 *
 * <p>On expiry the whole process <em>tree</em> is destroyed, not just the direct child. Destroying
 * only the direct child leaves grandchildren — a container CLI client's helpers, a node or shell
 * wrapper's children — alive and holding the pipes open.
 */
@Slf4j
final class SandboxProcess {

  /** Per-stream capture ceiling. A runaway child must not be able to exhaust the heap. */
  static final int MAX_CAPTURED_CHARS_PER_STREAM = 256_000;

  /** How long to wait for a destroyed tree to actually die before abandoning the drains. */
  private static final Duration REAP_GRACE = Duration.ofSeconds(5);

  private SandboxProcess() {}

  /**
   * Starts {@code argv} and waits at most {@code timeout} for it to exit.
   *
   * <p>{@code argv} is passed as a vector; nothing here builds or interprets a shell string.
   *
   * @return what happened, including whether the deadline expired
   */
  static Outcome run(List<String> argv, Duration timeout) {
    Instant startedAt = Instant.now();
    Process process;
    try {
      process = new ProcessBuilder(argv).start();
    } catch (Exception failure) {
      // Cannot start at all: no output exists and no exit status exists. Reported as unfinished
      // rather than as a failing command, because nothing ran.
      return new Outcome(
          false,
          -1,
          "",
          message(failure),
          false,
          Duration.between(startedAt, Instant.now()),
          message(failure));
    }

    Drain out = Drain.start(process.getInputStream(), "sandbox-proc-out");
    Drain err = Drain.start(process.getErrorStream(), "sandbox-proc-err");

    boolean finished;
    try {
      finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      destroyTree(process);
      out.join();
      err.join();
      return new Outcome(
          false,
          -1,
          out.text(),
          err.text(),
          out.truncated() || err.truncated(),
          Duration.between(startedAt, Instant.now()),
          "interrupted");
    }

    if (!finished) {
      destroyTree(process);
      try {
        process.waitFor(REAP_GRACE.toSeconds(), TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    out.join();
    err.join();

    return new Outcome(
        finished,
        finished ? process.exitValue() : -1,
        out.text(),
        err.text(),
        out.truncated() || err.truncated(),
        Duration.between(startedAt, Instant.now()),
        finished ? null : "wall-clock " + timeout + " elapsed");
  }

  /**
   * Destroys descendants before the parent. The other order lets the parent's death reparent its
   * children away, after which {@code descendants()} no longer finds them and they survive.
   */
  private static void destroyTree(Process process) {
    try {
      process.descendants().toList().forEach(ProcessHandle::destroyForcibly);
    } catch (RuntimeException denied) {
      // Some hardened macOS/container profiles deny the sysctl/procfs read used by
      // ProcessHandle.descendants(). Cleanup must still terminate the process we own and must
      // never let that platform restriction mask the original timeout result.
      log.debug("sandbox descendant discovery unavailable: {}", denied.toString());
    }
    process.destroyForcibly();
  }

  private static String message(Exception failure) {
    return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
  }

  /**
   * @param finished false when the deadline expired, the start failed, or the wait was interrupted;
   *     in every one of those cases there is no exit status and the result is not a command outcome
   * @param exitCode the child's exit status, or -1 when {@code finished} is false
   * @param stdout bounded standard output
   * @param stderr bounded standard error
   * @param truncated true when either stream hit {@link #MAX_CAPTURED_CHARS_PER_STREAM}
   * @param duration wall-clock actually spent
   * @param detail short non-secret reason when {@code finished} is false
   */
  record Outcome(
      boolean finished,
      int exitCode,
      String stdout,
      String stderr,
      boolean truncated,
      Duration duration,
      String detail) {}

  /** One stream drained on its own virtual thread so the parent's wait can reach its deadline. */
  private static final class Drain {

    private final Thread thread;
    private final StringBuilder captured = new StringBuilder();
    private volatile boolean truncated;

    private Drain(InputStream stream, String name) {
      this.thread = Thread.ofVirtual().name(name).unstarted(() -> pump(stream));
    }

    static Drain start(InputStream stream, String name) {
      Drain drain = new Drain(stream, name);
      drain.thread.start();
      return drain;
    }

    private void pump(InputStream stream) {
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          synchronized (captured) {
            if (captured.length() + line.length() + 1 <= MAX_CAPTURED_CHARS_PER_STREAM) {
              captured.append(line).append('\n');
            } else {
              // Keep draining after the cap so the child is never blocked on a full pipe; only
              // the retained text stops growing.
              truncated = true;
            }
          }
        }
      } catch (Exception ended) {
        // Expected when the tree is destroyed mid-read. Never fatal: whatever was captured before
        // the stream died is still the honest partial output.
        log.debug("sandbox process stream drain ended: {}", ended.getMessage());
      }
    }

    void join() {
      try {
        thread.join(REAP_GRACE);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    String text() {
      synchronized (captured) {
        return captured.toString();
      }
    }

    boolean truncated() {
      return truncated;
    }
  }
}
