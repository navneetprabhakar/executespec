package com.navneet.executespec.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.programbench.ProgramBenchContainerStore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Structured pytest tool for patch-mode (SWE-bench / generic Python). Takes a list of pytest test
 * ids, runs them inside the bench container (or host workspace if no container is registered for
 * this run), and returns a parsed pass/fail summary instead of raw stdout.
 *
 * <p>Why structured: with {@code shell} the agent had to parse pytest output itself — count
 * "passed" / "failed" lines, extract assertion errors from indented blocks. Models sometimes
 * mis-counted, sometimes treated a single-test-failed run as "everything failed," sometimes claimed
 * a passing test based on a misread. A typed envelope removes that parsing burden:
 *
 * <pre>{@code
 * {
 *   "summary": "1 passed, 1 failed",
 *   "passed":  ["test/foo.py::test_a"],
 *   "failed":  [
 *     {"test_id": "test/foo.py::test_b",
 *      "message": "AssertionError: expected 'x' got 'y'",
 *      "traceback": "...short traceback..."}
 *   ],
 *   "raw_tail": "...last ~60 lines of output for context..."
 * }
 * }</pre>
 *
 * <p>Routing: same dispatch tree as {@link ShellTool} — consult the SWE-bench container store
 * first, then the ProgramBench store. If neither has a cid for this run, falls back to running
 * pytest in the host workspace through a {@link ProcessBuilder} argument array.
 *
 * <p>Doubles as the gate for {@code completeTask} verification (see Layer 3 in {@code
 * WorkerAgent}). Calling {@code runTests} successfully marks the conversation as "test-verified" in
 * the loop's local state; the agent must call this within a small window of completing or {@code
 * completeTask} is refused.
 */
@Component
@Slf4j
public class RunTestsTool implements Tool {

  /** Tail truncation for the verbatim output that goes into {@code raw_tail}. */
  static final int RAW_TAIL_CHARS = 4_000;

  /** Default per-call timeout (pytest can be slow in some repos). */
  static final int DEFAULT_TIMEOUT_SEC = 240;

  static final int MAX_TIMEOUT_SEC = 600;

  /** Hard cap on test ids per call so the LLM doesn't cram a 200-test list into one call. */
  static final int MAX_TEST_IDS = 20;

  private final ProgramBenchContainerStore programBenchContainers;
  private final com.navneet.executespec.swebench.SwebenchContainerStore swebenchContainers;
  private final ObjectMapper json;

  @Autowired
  public RunTestsTool(
      ProgramBenchContainerStore programBenchContainers,
      com.navneet.executespec.swebench.SwebenchContainerStore swebenchContainers,
      ObjectMapper json) {
    this.programBenchContainers = programBenchContainers;
    this.swebenchContainers = swebenchContainers;
    this.json = json;
  }

  @Override
  public String name() {
    return "runTests";
  }

  @Override
  public String description() {
    return "Runs a list of pytest test ids inside the project's test environment and returns "
        + "a parsed pass/fail summary. Use this instead of `shell` for running tests — it "
        + "extracts assertion errors and counts cleanly. Required before completeTask in "
        + "patch mode: the agent must observe the failing test pass before claiming the "
        + "task is done.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {
    "test_ids": {
      "type": "array",
      "items": {"type": "string"},
      "description": "pytest test ids, e.g. ['test/foo.py::test_a', 'test/bar.py::test_b']. Max 20 per call.",
      "minItems": 1,
      "maxItems": 20
    },
    "timeoutSeconds": {
      "type": "integer",
      "description": "Optional timeout (default 240, max 600).",
      "minimum": 1,
      "maximum": 600
    }
  },
  "required": ["test_ids"],
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    JsonNode idsNode = args.path("test_ids");
    if (!idsNode.isArray() || idsNode.size() == 0) {
      return ToolResult.err(
          ToolResult.ErrorKind.INVALID_ARGS,
          "test_ids is required and must be a non-empty array of pytest ids");
    }
    if (idsNode.size() > MAX_TEST_IDS) {
      return ToolResult.err(
          ToolResult.ErrorKind.INVALID_ARGS,
          "test_ids exceeds max of "
              + MAX_TEST_IDS
              + " per call; split into multiple runTests calls");
    }
    List<String> testIds = new ArrayList<>(idsNode.size());
    for (JsonNode el : idsNode) {
      String id = el.asText("").trim();
      if (id.isEmpty()) continue;
      // Defence: pytest test ids should never contain shell metachars. If the LLM tries to inject a
      // payload, refuse the whole call. This is layered defence on top of the ProcessBuilder
      // argument-array dispatch below.
      if (containsShellMetachar(id)) {
        return ToolResult.err(
            ToolResult.ErrorKind.INVALID_ARGS, "test_id contains forbidden shell metachars: " + id);
      }
      testIds.add(id);
    }
    if (testIds.isEmpty()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "test_ids was empty after trim");
    }
    int timeout =
        args.hasNonNull("timeoutSeconds")
            ? Math.min(args.get("timeoutSeconds").asInt(DEFAULT_TIMEOUT_SEC), MAX_TIMEOUT_SEC)
            : DEFAULT_TIMEOUT_SEC;

    // Dispatch routing — mirrors ShellTool's container-store ladder.
    // useLoginShell flag: SWE-bench images depend on conda activation that lives in
    // /etc/bash.bashrc, so bare `sh -c "python -m pytest"` runs against base Python
    // (no pytest installed). bash -lc sources /etc/profile → bashrc → conda activate
    // testbed → pytest resolves. Programbench's images don't need this; host fallback
    // also doesn't need it.
    String containerId = null;
    String workdir = null;
    boolean useLoginShell = false;
    if (swebenchContainers != null) {
      String swCid = swebenchContainers.get(ctx.runId());
      if (swCid != null) {
        containerId = swCid;
        workdir = swebenchContainers.getWorkdir(ctx.runId());
        useLoginShell = true;
      }
    }
    if (containerId == null && programBenchContainers != null) {
      String pbCid = programBenchContainers.get(ctx.runId());
      if (pbCid != null) {
        containerId = pbCid;
        workdir = "/workspace";
      }
    }

    try {
      ProcessBuilder pb =
          new ProcessBuilder(buildProcessCommand(containerId, workdir, useLoginShell, testIds));
      if (containerId == null) {
        pb.directory(ctx.workspaceRoot().toFile());
      }
      Map<String, String> env = pb.environment();
      String path = env.get("PATH");
      String home = env.get("HOME");
      String dockerHost = env.get("DOCKER_HOST");
      env.clear();
      if (path != null) env.put("PATH", path);
      if (home != null) env.put("HOME", home);
      if (dockerHost != null) env.put("DOCKER_HOST", dockerHost);
      pb.redirectErrorStream(true);

      Process proc = pb.start();
      boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);
      if (!finished) {
        proc.destroyForcibly();
        return ToolResult.err(
            ToolResult.ErrorKind.TIMEOUT,
            "pytest exceeded "
                + timeout
                + "s timeout for "
                + testIds.size()
                + " test(s). Try fewer ids per call or raise timeoutSeconds.");
      }
      String output = new String(proc.getInputStream().readAllBytes());
      int exit = proc.exitValue();
      log.info(
          "runTests run={} ids={} exit={} outBytes={}",
          ctx.runId(),
          testIds.size(),
          exit,
          output.length());
      String envelope = parseAndEnvelope(ctx, testIds, exit, output);
      return ToolResult.ok(envelope);
    } catch (IOException ioe) {
      log.warn("runTests IO error: {}", ioe.getMessage());
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return ToolResult.err(ToolResult.ErrorKind.INTERNAL, "interrupted");
    }
  }

  /**
   * pytest -q output ends with a summary line like:
   *
   * <pre>
   *   1 passed, 2 failed in 1.23s
   *   FAILED test/foo.py::test_b - AssertionError: ...
   *   PASSED test/foo.py::test_a
   * </pre>
   *
   * We extract those into a structured envelope. Failing tests' assertion lines are pulled from the
   * {@code FAILED ... - <error>} suffix when present; otherwise we leave the message blank and let
   * the LLM read raw_tail.
   */
  private static final Pattern PYTEST_FAILED_LINE =
      Pattern.compile("(?m)^FAILED\\s+(\\S+)(?:\\s+-\\s+(.*))?$");

  private static final Pattern PYTEST_PASSED_LINE = Pattern.compile("(?m)^PASSED\\s+(\\S+)\\s*$");
  private static final Pattern PYTEST_SUMMARY_LINE =
      Pattern.compile("(?m)^=+\\s*(.*?passed.*?|.*?failed.*?|.*?error.*?)\\s*=+\\s*$");

  private String parseAndEnvelope(
      ExecutionContext ctx, List<String> requestedIds, int exit, String output) {
    ObjectNode env = json.createObjectNode();

    // Find pass/fail counts in the summary line (e.g. "1 passed, 2 failed in 1.23s").
    String summary = "";
    Matcher sm = PYTEST_SUMMARY_LINE.matcher(output);
    while (sm.find()) summary = sm.group(1).trim();
    if (summary.isEmpty()) {
      summary = exit == 0 ? "all passed" : "exit=" + exit;
    }
    env.put("summary", summary);

    // Extract individual passed / failed test ids.
    ArrayNode passed = env.putArray("passed");
    ArrayNode failed = env.putArray("failed");

    Matcher pm = PYTEST_PASSED_LINE.matcher(output);
    while (pm.find()) passed.add(pm.group(1));

    Matcher fm = PYTEST_FAILED_LINE.matcher(output);
    while (fm.find()) {
      ObjectNode entry = failed.addObject();
      entry.put("test_id", fm.group(1));
      String msg = fm.group(2);
      entry.put("message", msg == null ? "" : msg.trim());
    }

    // For tests in the request that didn't appear in either list (e.g. collection error),
    // mark them as "errored" so the LLM doesn't think they silently passed.
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (JsonNode el : passed) seen.add(el.asText());
    for (JsonNode el : failed) seen.add(el.path("test_id").asText());
    ArrayNode errored = env.putArray("errored");
    for (String id : requestedIds) {
      if (!seen.contains(id)) errored.add(id);
    }

    env.put("exit_code", exit);
    env.put("raw_tail", tailLines(output, RAW_TAIL_CHARS));
    // Output budget (#6): on large output, persist the full log + expose a readFile path so the
    // worker can inspect the COMPLETE failure trace, not just raw_tail.
    if (output != null && output.length() > ToolOutput.DEFAULT_INLINE_CAP) {
      String p = ToolOutput.persistFull(ctx, "tests", output);
      if (p != null) env.put("full_output_path", p);
    }
    return env.toString();
  }

  private static String tailLines(String s, int maxChars) {
    if (s == null) return "";
    if (s.length() <= maxChars) return s;
    int idx = s.length() - maxChars;
    int nl = s.indexOf('\n', idx);
    if (nl >= 0 && nl < s.length() - 1) idx = nl + 1;
    return "... [head omitted] ...\n" + s.substring(idx);
  }

  // Avoids unused-warning when stream/stdlib alternates compile.
  @SuppressWarnings("unused")
  private static final String UNUSED_REF = Arrays.toString(new String[0]);

  static List<String> buildProcessCommand(
      String containerId, String workdir, boolean useLoginShell, List<String> testIds) {
    List<String> cmd = new ArrayList<>();
    if (containerId != null) {
      cmd.add("docker");
      cmd.add("exec");
      cmd.add("-w");
      cmd.add(workdir);
      cmd.add(containerId);
      if (useLoginShell) {
        cmd.add("/bin/bash");
        cmd.add("-lc");
        cmd.add("python -m pytest \"$@\" --tb=short --no-header -q");
        cmd.add("_");
        cmd.addAll(testIds);
        return cmd;
      }
    }
    cmd.add("python");
    cmd.add("-m");
    cmd.add("pytest");
    cmd.addAll(testIds);
    cmd.add("--tb=short");
    cmd.add("--no-header");
    cmd.add("-q");
    return cmd;
  }

  /**
   * Returns true if {@code id} contains any shell metachar that could escape pytest argument
   * context if a later refactor stops using ProcessBuilder arg arrays. Defence-in-depth — audit
   * 2026-05-12 review item O #77.
   */
  static boolean containsShellMetachar(String id) {
    for (int i = 0; i < id.length(); i++) {
      char c = id.charAt(i);
      switch (c) {
        case ';':
        case '&':
        case '|':
        case '$':
        case '`':
        case '<':
        case '>':
        case '(':
        case ')':
        case '\\':
        case '\n':
        case '\r':
        case '\0':
          return true;
        default:
          // ASCII control chars (0x01-0x1F minus already-handled tab) — refuse.
          if (c < 0x20 && c != '\t') return true;
      }
    }
    return false;
  }
}
