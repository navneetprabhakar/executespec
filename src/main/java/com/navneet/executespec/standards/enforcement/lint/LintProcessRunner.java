package com.navneet.executespec.standards.enforcement.lint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * E3.4 — shared subprocess runner used by the lint adapters.
 *
 * <p>RR-1051 hardening. The child is repository-controlled code, so this runner now:
 *
 * <ul>
 *   <li><b>Strips the environment.</b> The inherited environment is cleared and only {@link
 *       LintExecutionProperties#getEnvironmentAllowlist()} is restored, so provider API keys, SCM
 *       tokens and database credentials are never handed to the child. {@code HOME} is pointed at a
 *       throwaway directory rather than inherited, so tools that probe {@code $HOME} do not find
 *       the service account's credential files.
 *   <li><b>Drains concurrently and boundedly.</b> The previous code called {@code waitFor} before
 *       reading either stream, so any linter emitting more than one pipe buffer deadlocked until
 *       the timeout — a whole-project ESLint JSON report reaches that size easily. Streams are now
 *       drained on separate threads with a byte cap, which also removes the unbounded {@code
 *       readAllBytes} heap exposure.
 *   <li><b>Kills the process tree.</b> {@code destroyForcibly} on the direct child left
 *       grandchildren running; descendants are now destroyed first.
 * </ul>
 *
 * <p>None of this is containment. It reduces what an admitted linter is handed and what it can do
 * to this JVM; it does not stop it reaching the network or the filesystem. See {@link
 * LintExecutionPolicy}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LintProcessRunner {

  private final LintExecutionProperties properties;

  public record Result(
      boolean executed, int exitCode, String stdout, String stderr, String unavailableReason) {
    public boolean ok() {
      return executed && exitCode == 0;
    }
  }

  public Result run(Path workingDir, List<String> argv, long timeoutSeconds) {
    Path scratchHome = null;
    try {
      if (argv == null || argv.isEmpty()) {
        return new Result(false, -1, null, null, "refusing to run an empty command");
      }
      ProcessBuilder pb = new ProcessBuilder(argv);
      if (workingDir != null) pb.directory(workingDir.toFile());
      pb.redirectErrorStream(false);
      scratchHome = Files.createTempDirectory("es-lint-home-");
      applyEnvironment(pb.environment(), scratchHome);

      Process p;
      try {
        p = pb.start();
      } catch (IOException ex) {
        return new Result(
            false,
            -1,
            null,
            null,
            "linter '" + argv.get(0) + "' could not be started (" + ex.getMessage() + ")");
      }
      StreamDrain out = StreamDrain.start(p.getInputStream(), properties.getMaxOutputBytes());
      StreamDrain err = StreamDrain.start(p.getErrorStream(), properties.getMaxOutputBytes());
      boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      if (!finished) {
        destroyTree(p);
        out.join();
        err.join();
        return new Result(
            true, -1, out.text(), "(timeout)", "linter exceeded " + timeoutSeconds + "s timeout");
      }
      out.join();
      err.join();
      return new Result(true, p.exitValue(), out.text(), err.text(), null);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new Result(false, -1, null, null, "runner interrupted");
    } catch (Exception ex) {
      log.warn("[LintProcessRunner] failed: {}", ex.getMessage());
      return new Result(false, -1, null, null, "runner failed: " + ex.getMessage());
    } finally {
      deleteQuietly(scratchHome);
    }
  }

  /** Clear everything inherited, then restore only what the allowlist names. */
  private void applyEnvironment(Map<String, String> childEnv, Path scratchHome) {
    Map<String, String> inherited = System.getenv();
    childEnv.clear();
    for (String key : properties.getEnvironmentAllowlist()) {
      String value = inherited.get(key);
      if (value != null) childEnv.put(key, value);
    }
    // Never inherited: a linter that reads $HOME must not find the service account's home.
    childEnv.put("HOME", scratchHome.toString());
    childEnv.put("TMPDIR", scratchHome.toString());
  }

  private static void destroyTree(Process p) {
    p.descendants().forEach(ProcessHandle::destroyForcibly);
    p.destroyForcibly();
  }

  private static void deleteQuietly(Path dir) {
    if (dir == null) return;
    try (var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // Best effort: a leftover scratch dir must not fail enforcement.
                }
              });
    } catch (IOException ignored) {
      // Best effort.
    }
  }

  /** Bounded, concurrent stream drain. Stops accumulating past the cap but keeps consuming. */
  private static final class StreamDrain {

    private final Thread thread;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private volatile boolean truncated;

    private StreamDrain(InputStream in, long maxBytes) {
      this.thread =
          new Thread(
              () -> {
                byte[] chunk = new byte[8192];
                long total = 0;
                try (InputStream stream = in) {
                  int read;
                  while ((read = stream.read(chunk)) != -1) {
                    long remaining = maxBytes - total;
                    if (remaining > 0) {
                      int keep = (int) Math.min(read, remaining);
                      buffer.write(chunk, 0, keep);
                      if (keep < read) truncated = true;
                    } else {
                      truncated = true;
                    }
                    total += read;
                  }
                } catch (IOException ignored) {
                  // The process died mid-stream; whatever was captured is what we report.
                }
              },
              "lint-stream-drain");
      this.thread.setDaemon(true);
    }

    static StreamDrain start(InputStream in, long maxBytes) {
      StreamDrain drain = new StreamDrain(in, maxBytes);
      drain.thread.start();
      return drain;
    }

    void join() throws InterruptedException {
      thread.join(TimeUnit.SECONDS.toMillis(10));
    }

    String text() {
      String captured = buffer.toString(StandardCharsets.UTF_8);
      return truncated ? captured + "\n(output truncated at the configured cap)" : captured;
    }
  }
}
