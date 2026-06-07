package com.navneet.executespec.tool;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * W4.2 (P6, vix-leverage) — registry of long-running background shell jobs. Provider-agnostic: a
 * worker driven by ANY provider can start a server / long build, get a {@code jobId} back
 * immediately, and later poll status, tail logs, or kill it — instead of blocking a foreground
 * {@code shell} call (which is capped at {@link ShellTool#MAX_TIMEOUT_SEC}s).
 *
 * <p><b>Scope (first cut): host-side execution</b> in the run workspace (local product runs).
 * Container-routed / remote-dispatched background jobs (the part that unblocks an in-container
 * Terminal-Bench server) are the documented follow-up — they need {@code docker exec -d} + an
 * in-container pid handle, or a Harbor-adapter background mode. The command is sandbox-validated by
 * the caller ({@link BackgroundJobTool}) exactly like {@link ShellTool}.
 *
 * <p><b>Lifecycle.</b> Each job's combined stdout+stderr streams to a temp log file; {@link
 * #cleanup(String)} (wired at run-end beside the context-store cleanup) kills any still-live jobs
 * of that run and deletes their logs, so a forgotten background process can't leak past the run.
 * The map is keyed by {@code jobId} with a {@code runId} index for that cleanup.
 */
@Component
@Slf4j
public class JobRegistry {

  static final int DEFAULT_TAIL_CHARS = 6000;

  private final Map<String, Job> jobs = new ConcurrentHashMap<>();

  /** A started background job. */
  public record Job(
      String jobId, String runId, Process process, Path logFile, String command, long startedMs) {}

  /**
   * Start a detached background process. The caller MUST have sandbox-validated {@code command}.
   *
   * @throws IOException if the process or its log file can't be created
   */
  public Job start(ExecutionContext ctx, String command) throws IOException {
    String jobId = "job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    Path log = Files.createTempFile("es-bgjob-", "-" + jobId + ".log");
    ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
    pb.directory(ctx.workspaceRoot().toFile());
    sanitizeEnv(pb);
    pb.redirectErrorStream(true);
    pb.redirectOutput(log.toFile());
    Process proc = pb.start();
    Job job = new Job(jobId, ctx.runId(), proc, log, command, System.currentTimeMillis());
    jobs.put(jobId, job);
    log.toFile().deleteOnExit();
    return job;
  }

  public Optional<Job> get(String jobId) {
    return Optional.ofNullable(jobs.get(jobId));
  }

  /** Human-readable status: "running (pid=…, elapsed=…s)" or "exited (rc=…, ran=…s)". */
  public String status(String jobId) {
    Job j = jobs.get(jobId);
    if (j == null) {
      return "unknown job " + jobId;
    }
    long secs = (System.currentTimeMillis() - j.startedMs()) / 1000;
    if (j.process().isAlive()) {
      return "running (pid=" + j.process().pid() + ", elapsed=" + secs + "s)";
    }
    return "exited (rc=" + j.process().exitValue() + ", ran=" + secs + "s)";
  }

  /** Tail of the job's combined stdout+stderr log (last {@code maxChars}). */
  public String tail(String jobId, int maxChars) {
    Job j = jobs.get(jobId);
    if (j == null) {
      return "unknown job " + jobId;
    }
    try {
      return tailFile(j.logFile(), Math.max(1, maxChars));
    } catch (IOException e) {
      return "(log unavailable: " + e.getMessage() + ")";
    }
  }

  /** Kill the process tree (descendants first, then the root). Idempotent. */
  public String kill(String jobId) {
    Job j = jobs.get(jobId);
    if (j == null) {
      return "unknown job " + jobId;
    }
    killTree(j.process());
    return "killed " + jobId;
  }

  /**
   * Run-end cleanup — kill any still-live jobs of {@code runId}, delete their logs, drop them from
   * the map. Always-on hygiene (like {@code ContextStore.deleteAll}); returns the count cleaned.
   */
  public int cleanup(String runId) {
    int n = 0;
    for (Job j : List.copyOf(jobs.values())) {
      if (j.runId().equals(runId)) {
        killTree(j.process());
        try {
          Files.deleteIfExists(j.logFile());
        } catch (IOException ignored) {
          // best-effort
        }
        jobs.remove(j.jobId());
        n++;
      }
    }
    if (n > 0) {
      log.info("JobRegistry cleaned {} background job(s) for run={}", n, runId);
    }
    return n;
  }

  private static void killTree(Process proc) {
    try {
      proc.descendants().forEach(ProcessHandle::destroyForcibly);
      proc.destroyForcibly();
    } catch (Exception e) {
      log.debug("killTree failed (best-effort): {}", e.toString());
    }
  }

  private static void sanitizeEnv(ProcessBuilder pb) {
    Map<String, String> env = pb.environment();
    String path = env.get("PATH");
    String home = env.get("HOME");
    String dockerHost = env.get("DOCKER_HOST");
    env.clear();
    if (path != null) env.put("PATH", path);
    if (home != null) env.put("HOME", home);
    if (dockerHost != null) env.put("DOCKER_HOST", dockerHost);
  }

  /** Read the last {@code maxChars} of a file (UTF-8), prefixing a marker when truncated. */
  static String tailFile(Path file, int maxChars) throws IOException {
    long size = Files.size(file);
    if (size <= maxChars) {
      return Files.readString(file, StandardCharsets.UTF_8);
    }
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
      raf.seek(size - maxChars);
      byte[] buf = new byte[maxChars];
      int read = Math.max(0, raf.read(buf));
      return "…(truncated to last "
          + maxChars
          + " chars)…\n"
          + new String(buf, 0, read, StandardCharsets.UTF_8);
    }
  }
}
