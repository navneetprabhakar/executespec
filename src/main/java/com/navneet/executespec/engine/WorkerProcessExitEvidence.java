package com.navneet.executespec.engine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses deterministic process-exit evidence from bounded worker tool envelopes. */
final class WorkerProcessExitEvidence {

  private static final Pattern EXIT_PATTERN =
      Pattern.compile("(?:\\\\[nr]|(?<![A-Za-z0-9_]))exit(?:_code)?[\"=:\\s\\\\]+(-?\\d+)");

  private WorkerProcessExitEvidence() {}

  static boolean failed(String envelope) {
    if (envelope == null || envelope.isEmpty()) return false;
    String head = envelope.length() > 400 ? envelope.substring(0, 400) : envelope;
    if (head.startsWith("{\"ok\":false")) return true;
    String exit = lastExit(envelope);
    return exit != null && !"0".equals(exit);
  }

  static boolean provesExitZero(String envelope) {
    if (envelope == null || !envelope.startsWith("{\"ok\":true")) return false;
    return "0".equals(lastExit(envelope));
  }

  private static String lastExit(String envelope) {
    Matcher match = EXIT_PATTERN.matcher(envelope);
    String last = null;
    while (match.find()) last = match.group(1);
    return last;
  }
}
