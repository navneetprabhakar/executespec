package com.navneet.executespec.testgen;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs a child process under a wall-clock bound that can actually fire.
 *
 * <p><strong>RR-1587.</strong> The previous call sites in this package drained the child's stdout
 * to EOF on the calling thread and only then called {@code waitFor(timeout)}. stdout closes when
 * the child exits, so {@code waitFor} was always reached on an already-terminated process and the
 * documented timeouts were unreachable: a hung suite pinned the thread and leaked its container
 * forever. Here the drain runs on its own thread so {@code waitFor(timeout)} is reached while the
 * child is still alive, and on expiry the whole process tree is destroyed.
 *
 * <p>{@link Outcome#finished()} is false when the deadline expired. Callers must treat that as an
 * unavailable result, never as a completed run.
 */
@Slf4j
public final class BoundedProcess {

  /** Hard cap on captured output so a runaway child cannot exhaust the heap. */
  private static final int MAX_OUTPUT_CHARS = 1_000_000;

  private BoundedProcess() {}

  /**
   * Start {@code pb} (stderr must already be redirected into stdout by the caller) and wait at most
   * {@code timeout} for it to exit.
   *
   * @return the exit code, captured output, and whether the child exited before the deadline
   */
  public static Outcome run(ProcessBuilder pb, Duration timeout) throws Exception {
    Process process = pb.start();
    StringBuilder captured = new StringBuilder();
    Thread drain =
        Thread.ofVirtual()
            .name("bounded-process-drain")
            .start(
                () -> {
                  try (BufferedReader reader =
                      new BufferedReader(
                          new InputStreamReader(
                              process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                      synchronized (captured) {
                        if (captured.length() < MAX_OUTPUT_CHARS) {
                          captured.append(line).append('\n');
                        }
                      }
                    }
                  } catch (Exception e) {
                    log.debug("[BoundedProcess] Output drain ended: {}", e.getMessage());
                  }
                });

    boolean finished;
    try {
      finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      destroyTree(process);
      drain.join(Duration.ofSeconds(5));
      return new Outcome(false, -1, snapshot(captured));
    }

    if (!finished) {
      destroyTree(process);
      // Give the child a brief chance to die before abandoning the drain thread.
      process.waitFor(5, TimeUnit.SECONDS);
    }
    drain.join(Duration.ofSeconds(5));

    int exitCode = finished ? process.exitValue() : -1;
    return new Outcome(finished, exitCode, snapshot(captured));
  }

  private static String snapshot(StringBuilder captured) {
    synchronized (captured) {
      return captured.toString();
    }
  }

  private static void destroyTree(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  /**
   * @param finished false when the deadline expired — the result is unavailable, not a run outcome
   * @param exitCode child exit status, or -1 when {@code finished} is false
   * @param output combined stdout/stderr, capped at {@value #MAX_OUTPUT_CHARS} characters
   */
  public record Outcome(boolean finished, int exitCode, String output) {}
}
