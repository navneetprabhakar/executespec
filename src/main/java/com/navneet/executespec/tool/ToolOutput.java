package com.navneet.executespec.tool;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tool output budgeting (RL-2026-05-051, harness adoption #6).
 *
 * <p>Large command/build/test output used to be hard-truncated to a few KB (head+tail), so the
 * worker literally could not see a full pytest/maven failure trace — it had to act on a fragment.
 * That directly hurt verify-before-done: you can't fix a failure you can't read.
 *
 * <p>Modelled on Claude Code's {@code maxResultSizeChars} → persist-to-disk pattern: output over
 * the inline budget is written in full to {@code .agent-context/tool-output/<tool>-<ts>.txt} inside
 * the run workspace, and the model receives a head+tail preview plus the workspace-relative path
 * with an explicit instruction to {@code readFile} it for the complete output. The worker never
 * loses access to the full output — it pages it in on demand only when it needs the detail.
 *
 * <p>{@code .agent-context/} is run-infra: it is excluded from benchmark scoreable diffs and is not
 * pushed on product runs, so persisting here never pollutes a patch or a PR. Fail-open: any IO
 * error falls back to a plain head+tail truncation.
 */
public final class ToolOutput {

  private ToolOutput() {}

  /** Default inline budget for shell/build/test output before overflow is persisted to disk. */
  public static final int DEFAULT_INLINE_CAP = 6_000;

  /**
   * Return {@code output} as-is if within {@code inlineCap}; otherwise persist the full output
   * under the workspace's {@code .agent-context/tool-output/} and return a head+tail preview + a
   * path the worker can {@code readFile} for the complete text.
   */
  public static String budget(ExecutionContext ctx, String tool, String output, int inlineCap) {
    if (output == null) return "";
    if (output.length() <= inlineCap) return output;
    String rel = persistFull(ctx, tool, output);
    if (rel == null) {
      // Fail-open: never let output-handling fail a tool call — degrade to plain truncation.
      return headTail(output, inlineCap) + "\n...[output truncated; persist failed]";
    }
    return headTail(output, inlineCap)
        + "\n\n[full output ("
        + output.length()
        + " chars) saved to "
        + rel
        + " — readFile "
        + rel
        + " for the complete output (e.g. the full failure trace).]";
  }

  /**
   * Persist {@code output} in full to {@code .agent-context/tool-output/<tool>-<ts>.txt} and return
   * the workspace-relative path, or {@code null} if there's nothing to persist / the write failed.
   * Used by tools (e.g. runTests) that keep a structured inline preview but want to expose the full
   * raw output via a readFile path.
   */
  public static String persistFull(ExecutionContext ctx, String tool, String output) {
    if (output == null || output.isEmpty()) return null;
    try {
      Path dir = ctx.workspaceRoot().resolve(".agent-context").resolve("tool-output");
      Files.createDirectories(dir);
      String fname = tool + "-" + System.currentTimeMillis() + ".txt";
      Files.writeString(dir.resolve(fname), output);
      return ".agent-context/tool-output/" + fname;
    } catch (Exception e) {
      return null;
    }
  }

  /** Head+tail preview keeping the start (context) + the end (where errors usually are). */
  private static String headTail(String s, int cap) {
    if (s.length() <= cap) return s;
    int tail = Math.min(1_500, cap / 4);
    int head = cap - tail;
    int omitted = s.length() - cap;
    return s.substring(0, head)
        + "\n...["
        + omitted
        + " chars omitted]...\n"
        + s.substring(s.length() - tail);
  }
}
