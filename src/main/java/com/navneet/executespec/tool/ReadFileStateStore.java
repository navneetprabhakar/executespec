package com.navneet.executespec.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-run read-before-write ledger (RL-2026-05-051, harness adoption #1).
 *
 * <p>Tracks, per run, the SHA-256 of each file's contents <b>at the moment the worker last read or
 * wrote it</b>. {@link EditFileTool} consults this before applying an edit:
 *
 * <ul>
 *   <li><b>read-before-edit</b> — refuse to edit a file the worker never read this run (forces the
 *       worker to ground edits in real content, not a hallucinated view);
 *   <li><b>staleness</b> — refuse if the file changed on disk since that read (a concurrent
 *       tool/process — or, historically, the lint pass — rewrote it under the worker), so the
 *       worker re-reads instead of clobbering / matching against a stale mental model.
 * </ul>
 *
 * <p>Modelled on Claude Code's {@code readFileState} (its {@code FileEditTool} enforces the same
 * two gates). Content-hash based rather than mtime-based to avoid filesystem mtime-resolution
 * flakiness on fast successive edits. A successful write (writeFile/editFile) updates the ledger to
 * the new content, so consecutive edits to the same file don't require a re-read.
 *
 * <p>The store is a process-wide singleton; per-run maps are evicted LRU past {@link #MAX_RUNS} so
 * a long-lived JVM serving many runs never leaks (explicit {@link #clear(String)} is also available
 * at run-end). All operations are fail-open: any IO/hash error degrades to "treat as read" so the
 * gate can never wedge a legitimate edit.
 */
@Component
@Slf4j
public class ReadFileStateStore {

  /** Max runs retained before LRU eviction of the eldest run's ledger. */
  static final int MAX_RUNS = 200;

  /** Outcome of the pre-edit gate check. */
  public enum Status {
    /** File was read (or written) this run and is unchanged since — edit allowed. */
    OK,
    /** File was never read/written this run — worker must readFile it first. */
    NOT_READ,
    /** File changed on disk since the worker last read it — worker must re-read. */
    STALE
  }

  // runId -> (absolute-normalized path string -> content sha). LRU-bounded by MAX_RUNS.
  private final Map<String, Map<String, String>> byRun =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) {
              return size() > MAX_RUNS;
            }
          });

  /** Record that {@code file} was read this run, capturing its current on-disk content hash. */
  public void recordRead(String runId, Path file, String content) {
    put(runId, file, sha(content));
  }

  /** Record that {@code file} was written this run with {@code newContent} (counts as "known"). */
  public void recordWrite(String runId, Path file, String newContent) {
    put(runId, file, sha(newContent));
  }

  /**
   * Gate check for an edit: {@code currentContent} is the file's content the caller just read off
   * disk. Returns OK only if the worker previously read/wrote this exact content.
   */
  public Status check(String runId, Path file, String currentContent) {
    if (runId == null || file == null) return Status.OK; // fail-open
    String recorded;
    Map<String, String> m = byRun.get(runId);
    recorded = m == null ? null : m.get(key(file));
    if (recorded == null) return Status.NOT_READ;
    String current = sha(currentContent);
    if (current == null || recorded.equals(current)) return Status.OK; // fail-open on hash error
    return Status.STALE;
  }

  /** Drop a run's ledger (call at run-end; also enforced lazily by LRU eviction). */
  public void clear(String runId) {
    if (runId != null) byRun.remove(runId);
  }

  private void put(String runId, Path file, String sha) {
    if (runId == null || file == null || sha == null) return;
    byRun.computeIfAbsent(runId, k -> new ConcurrentHashMap<>()).put(key(file), sha);
  }

  private static String key(Path file) {
    return file.toAbsolutePath().normalize().toString();
  }

  /**
   * Convenience: read the file off disk and record it (used where the caller lacks the content).
   */
  public void recordReadFromDisk(String runId, Path file) {
    try {
      recordRead(runId, file, Files.readString(file));
    } catch (Exception e) {
      log.debug("recordReadFromDisk failed (non-fatal) run={} file={}", runId, file, e);
    }
  }

  private static String sha(String content) {
    if (content == null) return null;
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] d = md.digest(content.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(d.length * 2);
      for (byte b : d)
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      return sb.toString();
    } catch (Exception e) {
      return null; // fail-open: callers treat null hash as "match"
    }
  }
}
