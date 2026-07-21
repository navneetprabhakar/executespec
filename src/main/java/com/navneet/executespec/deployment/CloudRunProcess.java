package com.navneet.executespec.deployment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.testgen.BoundedProcess;

import lombok.extern.slf4j.Slf4j;

/**
 * Bounded gcloud subprocess execution for {@link CloudRunClient} (RR-999).
 *
 * <p>Extracted from {@code CloudRunClient} to keep that class under the god-class line ceiling.
 * Pure static process helpers, no client state: a combined-output path via {@link BoundedProcess},
 * and a separate-streams path for the JSON-on-stdout deploy commands. Both start the wall-clock
 * deadline BEFORE any read and destroy the whole process tree on expiry — reading a stream to EOF
 * first (the shape this replaced) made the timeout unreachable for a child that keeps its pipe
 * open.
 */
@Slf4j
final class CloudRunProcess {

  /** Per-stream capture ceiling so a runaway gcloud cannot exhaust the heap. */
  private static final int MAX_GCLOUD_STREAM_CHARS = 1_000_000;

  private CloudRunProcess() {}

  /** stdout and stderr kept apart, both bounded, for the JSON-parsing deploy paths. */
  record GcloudResult(boolean finished, int exitCode, String stdout, String stderr) {}

  /**
   * Run a gcloud command whose stderr is folded into stdout (combined output is fine) under a
   * reachable wall-clock deadline. {@code pb} must already have {@code redirectErrorStream(true)}.
   */
  static BoundedProcess.Outcome runBounded(java.lang.ProcessBuilder pb, Duration timeout)
      throws IOException {
    try {
      return BoundedProcess.run(pb, timeout);
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  /**
   * Drain stdout and stderr on separate virtual threads (so the JSON on stdout stays clean of
   * gcloud's progress text on stderr) with the deadline started BEFORE the reads. The whole process
   * tree is destroyed on expiry.
   */
  static GcloudResult runGcloudSeparate(java.lang.ProcessBuilder pb, Duration timeout)
      throws IOException, InterruptedException {
    Process process = pb.start();
    StringBuilder out = new StringBuilder();
    StringBuilder err = new StringBuilder();
    Thread outDrain = startDrain(process.getInputStream(), out, "cloudrun-out");
    Thread errDrain = startDrain(process.getErrorStream(), err, "cloudrun-err");

    boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    joinDrain(outDrain);
    joinDrain(errDrain);
    return new GcloudResult(
        finished,
        finished ? process.exitValue() : -1,
        out.toString().trim(),
        err.toString().trim());
  }

  private static Thread startDrain(InputStream stream, StringBuilder sink, String name) {
    return Thread.ofVirtual()
        .name(name)
        .start(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  synchronized (sink) {
                    if (sink.length() + line.length() + 1 <= MAX_GCLOUD_STREAM_CHARS) {
                      sink.append(line).append('\n');
                    }
                    // Keep draining past the cap so the child is never blocked on a full pipe.
                  }
                }
              } catch (Exception ended) {
                log.debug("gcloud stream drain ended: {}", ended.getMessage());
              }
            });
  }

  private static void joinDrain(Thread drain) {
    try {
      drain.join(Duration.ofSeconds(5));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
