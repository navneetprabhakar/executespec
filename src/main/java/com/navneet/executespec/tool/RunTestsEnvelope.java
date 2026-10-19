package com.navneet.executespec.tool;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.smoketest.TestCommandResolver;

/**
 * Result-envelope construction for {@link RunTestsTool}.
 *
 * <p>Extracted verbatim from {@code RunTestsTool} on 2026-07-20 so that class stays under the
 * 600-line new-class ceiling enforced by {@code GodClassLineCeilingTest} after the RR-547 admission
 * and shell-policy work. Behaviour is unchanged: the field shape ({@code summary} / {@code passed}
 * / {@code failed} / {@code errored} / {@code exit_code} / {@code raw_tail}) is the contract the
 * V182 {@code completeTask} gate reads, so it must stay identical across frameworks.
 */
final class RunTestsEnvelope {

  private final ObjectMapper json;

  RunTestsEnvelope(ObjectMapper json) {
    this.json = json;
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

  /**
   * Go test failure markers: per-test {@code --- FAIL: TestX} and per-package {@code FAIL\tpkg}.
   */
  private static final Pattern GO_FAIL_LINE = Pattern.compile("(?m)^\\s*--- FAIL: (\\S+)");

  private static final Pattern GO_PKG_FAIL_LINE = Pattern.compile("(?m)^FAIL\\s+(\\S+)");

  String parseAndEnvelope(
      ExecutionContext ctx, List<String> requestedIds, int exit, String output) {
    ObjectNode env = json.createObjectNode();

    // Find pass/fail counts in the summary line (e.g. "1 passed, 2 failed in 1.23s").
    String summary = "";
    Matcher sm = PYTEST_SUMMARY_LINE.matcher(output);
    while (sm.find()) summary = sm.group(1).trim();
    if (summary.isEmpty()) {
      summary = exit == 0 ? "test counts unavailable" : "exit=" + exit;
    }
    env.put("summary", summary);
    env.put("framework", "PYTEST");

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

    // Quiet pytest output normally omits individual node IDs. Aggregate positive execution
    // counts are evidence that tests ran; omitted node IDs are not collection errors.
    var counts =
        com.navneet.executespec.smoketest.TestOutputParser.parse(
            TestCommandResolver.Framework.PYTEST, output, exit);
    boolean provenPassing =
        exit == 0
            && counts.isPresent()
            && counts.get().getPassed() > 0
            && counts.get().getFailed() == 0
            && counts.get().getErrors() == 0;
    if (counts.isPresent()) {
      var count = counts.get();
      env.put("tests_passed", count.getPassed());
      env.put("tests_total", count.getTotalTests());
      env.put("tests_failed", count.getFailed());
      env.put("tests_errors", count.getErrors());
      if (summary.equals("test counts unavailable")) summary = count.getPassed() + " passed";
      env.put("summary", summary);
    }
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (JsonNode el : passed) seen.add(el.asText());
    for (JsonNode el : failed) seen.add(el.path("test_id").asText());
    ArrayNode errored = env.putArray("errored");
    ArrayNode unverified = env.putArray("unreported_requested_ids");
    for (String id : requestedIds) {
      if (!seen.contains(id)) {
        if (provenPassing) unverified.add(id);
        else errored.add(id);
      }
    }
    if (exit == 0 && passed.isEmpty() && !provenPassing) errored.add("PYTEST_EXECUTION_UNPROVEN");

    env.put("exit_code", exit);
    env.put("raw_tail", tailLines(output, RunTestsTool.RAW_TAIL_CHARS));
    // Output budget (#6): on large output, persist the full log + expose a readFile path so the
    // worker can inspect the COMPLETE failure trace, not just raw_tail.
    if (output != null && output.length() > ToolOutput.DEFAULT_INLINE_CAP) {
      String p = ToolOutput.persistFull(ctx, "tests", output);
      if (p != null) env.put("full_output_path", p);
    }
    return env.toString();
  }

  /**
   * Non-pytest envelope — same field shape so the V182 completeTask gate works on every stack:
   * green run ⇒ {@code "failed":[]} + {@code "errored":[]} + {@code "exit_code":0}; red run ⇒ a
   * failed entry (Go gets real {@code --- FAIL:} names; others get a framework-level entry) so the
   * gate blocks. Deliberately under-parsed beyond that — the model reads raw_tail.
   */
  String genericEnvelope(
      ExecutionContext ctx,
      TestCommandResolver.Framework framework,
      String command,
      List<String> requestedIds,
      int exit,
      String output) {
    ObjectNode env = json.createObjectNode();
    env.put("framework", framework.name());
    env.put("command", command);
    ArrayNode passed = env.putArray("passed");
    ArrayNode failed = env.putArray("failed");
    ArrayNode errored = env.putArray("errored");

    if (framework == TestCommandResolver.Framework.GO) {
      Matcher gf = GO_FAIL_LINE.matcher(output);
      while (gf.find()) {
        ObjectNode entry = failed.addObject();
        entry.put("test_id", gf.group(1));
        entry.put("message", "see raw_tail");
      }
      if (failed.isEmpty() && exit != 0) {
        Matcher pf = GO_PKG_FAIL_LINE.matcher(output);
        while (pf.find()) {
          ObjectNode entry = failed.addObject();
          entry.put("test_id", pf.group(1));
          entry.put("message", "package failed — see raw_tail (may be a build error)");
        }
      }
    }
    if (exit != 0 && failed.isEmpty()) {
      ObjectNode entry = failed.addObject();
      entry.put("test_id", framework.name().toLowerCase() + " suite");
      entry.put("message", "non-zero exit " + exit + " — see raw_tail");
    }
    if (exit == 0) {
      for (String id : requestedIds) passed.add(id);
    }

    env.put(
        "summary",
        exit == 0
            ? framework.name().toLowerCase() + " tests passed (exit=0)"
            : framework.name().toLowerCase() + " tests FAILED (exit=" + exit + ")");
    env.put("exit_code", exit);
    env.put("raw_tail", tailLines(output, RunTestsTool.RAW_TAIL_CHARS));
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
}
