package com.navneet.executespec.harness;

import java.util.List;

import com.navneet.executespec.tool.ToolCapabilities;
import com.navneet.executespec.tool.ToolCapability;

/**
 * Reads a worker tool-call log entry and classifies it for the executor's waste guards and delivery
 * evidence (RR-1599).
 *
 * <p>Entries are produced by {@code engine.WorkerAgent.toolCallLogEntry} and look like {@code
 * "writeFile(sig=ab12cd34) [target=src/App.tsx] -> OK"}; older/synthesised entries can still carry
 * literal arguments ({@code "editFile({\"path\":\"src/App.tsx\", …})"}).
 *
 * <p><b>Why this is capability-keyed.</b> Every predicate here used to be a literal-name prefix
 * match, and the registry mints several names over one implementation ({@code executeCommand} /
 * {@code runCommand} / {@code terminalCommand} for shell; {@code build} / {@code runBuild} for the
 * build; {@code applyPatch} / {@code ast_edit} / {@code move} / {@code delete} for file mutation).
 * A call made under an alias was therefore invisible: {@code max-repeated-failures} never fired for
 * it, and a genuine delivery produced no attributable product path.
 *
 * <p>Fail-closed direction differs per predicate and is documented on each one — "closed" means
 * closed in the direction that cannot vouch for the run.
 */
final class WorkerToolLogEvidence {

  private WorkerToolLogEvidence() {}

  /**
   * Groups a failing command entry by its stable {@code tool(args)} prefix, so the same command
   * failing repeatedly is caught even when stderr differs between attempts. Null when the entry is
   * not a command call.
   *
   * <p>Fails CLOSED: an UNCLASSIFIED call-shaped entry is grouped, so an unknown tool cannot escape
   * the waste guard.
   */
  static String repeatedCommandFailureSignature(String normalized) {
    String toolName = leadingToolName(normalized);
    if (toolName == null || normalized.length() <= toolName.length()) return null;
    if (normalized.charAt(toolName.length()) != '(') return null;
    ToolCapability capability = ToolCapabilities.of(toolName).orElse(null);
    if (capability != null && !runsCommandOrProjectScript(capability)) return null;
    int status = normalized.indexOf("->");
    String call = status < 0 ? normalized : normalized.substring(0, status).trim();
    return call.isBlank() ? null : call;
  }

  /**
   * The mutated path an entry claims, or null when it is not a file mutation or carries no path.
   *
   * <p>Two defects fixed here. (1) The tool set omitted {@code applyPatch} / {@code apply_patch} /
   * {@code ast_edit} / {@code move} / {@code delete}, so those mutations produced no signature and
   * a real delivery read as unproven. (2) Extraction only knew the legacy {@code "path": "…"} /
   * {@code path=…} argument forms, but the live log hashes arguments and carries the path in an
   * explicit {@code [target=…]} marker — so on the current format this returned null for EVERY
   * entry and edit-churn evidence was dead. Both forms are read now, {@code [target=…]} first.
   */
  static String mutationPathSignature(String entry) {
    if (entry == null || entry.isBlank()) return null;
    String normalized = entry.replace("→", "->").replaceAll("\\s+", " ").trim();
    if (!mutatesFiles(leadingToolName(normalized))) return null;
    String path = targetMarkerPath(normalized);
    if (path == null) path = quotedJsonPath(normalized);
    if (path == null) path = keyValuePath(normalized);
    return path == null || path.isBlank() ? null : path;
  }

  /**
   * Counts SUCCESSFUL file-mutating tool calls. We only need a count (not paths) to decide whether
   * an incomplete worker still produced real output.
   *
   * <p>Fails CLOSED in the opposite direction from the safety predicates: an UNCLASSIFIED name is
   * NOT counted, because over-counting would falsely clear the zero-write provider-breakdown
   * signal. The old literal list omitted {@code ast_edit}, {@code move} and {@code delete}, so a
   * worker delivering only through those looked like it had written nothing.
   *
   * <p>RR-261 (P1 outcome integrity) — a REJECTED edit is not delivered code. The entry's trailing
   * status ({@code -> OK} / {@code -> ERR}, or a {@code → REFUSED} gate marker) is now consulted,
   * so a worker that attempted only stale/forbidden/invalid edits counts zero file writes and
   * cannot be laundered into a soft completion. A mutation entry with no explicit status is still
   * counted (legacy/synthesised entries), because the live worker path always stamps a status and
   * dropping an unlabelled genuine write would understate a real delivery.
   */
  static int countFileWrites(List<String> toolLog) {
    if (toolLog == null || toolLog.isEmpty()) return 0;
    int n = 0;
    for (String entry : toolLog) {
      if (entry != null && mutatesFiles(leadingToolName(entry.trim())) && !isRejectedEntry(entry)) {
        n++;
      }
    }
    return n;
  }

  /**
   * RR-261 — true when a tool-log entry records a failed or refused call. Matches the {@code ->
   * ERR} status stamped by {@code WorkerAgent.toolCallLogEntry} and the {@code → REFUSED} gate
   * markers, across the {@code →}/{@code ->} arrow variants.
   */
  private static boolean isRejectedEntry(String entry) {
    String normalized = entry.replace("→", "->").replaceAll("\\s+", " ").trim();
    int arrow = normalized.lastIndexOf("-> ");
    if (arrow < 0) return false;
    String status = normalized.substring(arrow + 3).trim();
    return status.startsWith("ERR") || status.startsWith("REFUSED");
  }

  private static boolean mutatesFiles(String toolName) {
    return toolName != null
        && ToolCapabilities.of(toolName).map(ToolCapability::mutatesFiles).orElse(false);
  }

  /** Capabilities whose invocation runs a command or a project-controlled script. */
  private static boolean runsCommandOrProjectScript(ToolCapability capability) {
    return capability.executesCommands()
        || capability == ToolCapability.BUILD_PROJECT
        || capability == ToolCapability.RUN_TESTS
        || capability == ToolCapability.VERIFY_BOOT;
  }

  /**
   * Leading tool identifier of an entry, e.g. {@code "writeFile"}. Null when the entry does not
   * start with an identifier, which excludes the synthetic markers the worker also appends.
   */
  private static String leadingToolName(String normalized) {
    if (normalized == null || normalized.isEmpty()) return null;
    if (!Character.isJavaIdentifierStart(normalized.charAt(0))) return null;
    int i = 1;
    while (i < normalized.length() && Character.isJavaIdentifierPart(normalized.charAt(i))) i++;
    return normalized.substring(0, i);
  }

  /** Reads the {@code [target=…]} marker written by {@code WorkerAgent.toolCallLogEntry}. */
  private static String targetMarkerPath(String text) {
    int key = text.indexOf("[target=");
    if (key < 0) return null;
    int start = key + "[target=".length();
    int end = text.indexOf(']', start);
    return end > start ? text.substring(start, end).trim() : null;
  }

  private static String quotedJsonPath(String text) {
    int key = text.indexOf("\"path\"");
    if (key < 0) return null;
    int colon = text.indexOf(':', key);
    int start = colon < 0 ? -1 : text.indexOf('"', colon + 1);
    int end = start < 0 ? -1 : text.indexOf('"', start + 1);
    return end > start ? text.substring(start + 1, end) : null;
  }

  private static String keyValuePath(String text) {
    int key = text.indexOf("path=");
    if (key < 0) return null;
    int start = key + "path=".length();
    int comma = text.indexOf(',', start);
    int paren = text.indexOf(')', start);
    int end = comma < 0 ? paren : paren < 0 ? comma : Math.min(comma, paren);
    return end > start ? text.substring(start, end).trim() : text.substring(start).trim();
  }
}
