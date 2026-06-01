package com.navneet.executespec.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure string-replacement engine for {@link EditFileTool}. Extracted as a Spring-free, fully
 * deterministic helper so the matching cascade can be unit-tested in isolation (RL-2026-06).
 *
 * <p><strong>Why this exists:</strong> the old {@code editFile} did a single {@code indexOf} exact
 * match and hard-rejected both zero matches and multiple matches. Combined with {@code readFile}
 * truncation, that made edits to real files fail constantly — the #1 way coding agents stall. This
 * engine keeps exact match as the trusted primary, then degrades gracefully:
 *
 * <ol>
 *   <li><b>exact</b> — {@code indexOf}; honours {@code replaceAll} / {@code occurrence}.
 *   <li><b>whitespace-tolerant</b> (only when exact finds ZERO) — line-based match ignoring trailing
 *       whitespace; leading indentation must still match. Insert {@code newStr} verbatim.
 *   <li><b>indent-flexible</b> (only when the above find ZERO) — line-based match ignoring leading
 *       AND trailing whitespace; {@code newStr} is re-indented to the matched block.
 * </ol>
 *
 * <p>Every fuzzy tier requires a <b>unique</b> match — if a fuzzy pattern matches more than one
 * region the edit is rejected and the model is told to make {@code oldStr} exact. This keeps the
 * "fail loudly, never corrupt" guarantee while eliminating the brittle exact-byte requirement.
 */
public final class StringEditMatcher {

  private StringEditMatcher() {}

  /** Outcome of applying one edit. */
  public sealed interface Result permits Applied, Failed {}

  /** Edit applied successfully. {@code how} is a short tag for logging/telemetry. */
  public record Applied(String updated, String how) implements Result {}

  /** Edit could not be applied; {@code message} is model-facing guidance. */
  public record Failed(String message) implements Result {}

  /**
   * Applies a single {@code oldStr → newStr} replacement to {@code content}.
   *
   * @param replaceAll when true and {@code oldStr} matches exactly, replace every occurrence
   * @param occurrence when non-null and {@code oldStr} matches exactly, replace only the k-th
   *     (1-indexed) occurrence
   */
  public static Result apply(
      String content, String oldStr, String newStr, boolean replaceAll, Integer occurrence) {
    if (oldStr == null || newStr == null) {
      return new Failed("oldStr and newStr are required");
    }
    if (oldStr.isEmpty()) {
      return new Failed("oldStr must not be empty");
    }

    // ---- Tier 1: exact -------------------------------------------------------------------
    List<Integer> hits = new ArrayList<>();
    for (int at = content.indexOf(oldStr); at >= 0; at = content.indexOf(oldStr, at + 1)) {
      hits.add(at);
    }
    if (!hits.isEmpty()) {
      if (replaceAll) {
        return new Applied(content.replace(oldStr, newStr), "exact-all(" + hits.size() + ")");
      }
      if (occurrence != null) {
        if (occurrence < 1 || occurrence > hits.size()) {
          return new Failed(
              "occurrence "
                  + occurrence
                  + " out of range — oldStr occurs "
                  + hits.size()
                  + " time(s) (no changes written)");
        }
        int at = hits.get(occurrence - 1);
        return new Applied(splice(content, at, oldStr.length(), newStr), "occurrence(" + occurrence + ")");
      }
      if (hits.size() == 1) {
        int at = hits.get(0);
        return new Applied(splice(content, at, oldStr.length(), newStr), "exact");
      }
      return new Failed(
          "oldStr occurs "
              + hits.size()
              + " times — pass replaceAll=true to replace all, occurrence=<1.."
              + hits.size()
              + "> to pick one, or add surrounding context to make oldStr unique (no changes"
              + " written)");
    }

    // ---- Tiers 2 & 3: fuzzy line-based (only when exact found nothing) --------------------
    Result whitespace = fuzzyLineMatch(content, oldStr, newStr, false);
    if (whitespace != null) return whitespace;
    Result indent = fuzzyLineMatch(content, oldStr, newStr, true);
    if (indent != null) return indent;

    String firstOld = oldStr.split("\n", -1)[0].strip();
    return new Failed(
        "oldStr not found (tried exact + whitespace-tolerant + indent-flexible search). "
            + "First line searched: \""
            + truncate(firstOld, 80)
            + "\". Re-read the file with readFile and copy the exact current text (no changes"
            + " written).");
  }

  /**
   * Line-based match. {@code indentFlexible=false} ignores only trailing whitespace (leading
   * indentation must match) and inserts {@code newStr} verbatim. {@code indentFlexible=true} also
   * ignores leading whitespace and re-indents {@code newStr} to the matched block. Returns null when
   * there is no match at this tier (so the caller can fall through); returns a {@link Failed} when
   * the pattern matches more than one region (ambiguous — must be made exact).
   */
  private static Result fuzzyLineMatch(
      String content, String oldStr, String newStr, boolean indentFlexible) {
    String oldTrimmed = stripOneTrailingNewline(oldStr);
    String[] oldLines = oldTrimmed.split("\n", -1);

    // Line-start offsets for content (0, and every index right after a '\n').
    List<Integer> lineStarts = new ArrayList<>();
    lineStarts.add(0);
    for (int i = 0; i < content.length(); i++) {
      if (content.charAt(i) == '\n') lineStarts.add(i + 1);
    }
    int n = lineStarts.size();
    int k = oldLines.length;
    if (k == 0 || k > n) return null;

    List<Integer> matchStarts = new ArrayList<>();
    for (int i = 0; i + k <= n; i++) {
      boolean all = true;
      for (int j = 0; j < k; j++) {
        if (!lineMatches(lineContent(content, lineStarts, i + j), oldLines[j], indentFlexible)) {
          all = false;
          break;
        }
      }
      if (all) matchStarts.add(i);
    }
    if (matchStarts.isEmpty()) return null;
    if (matchStarts.size() > 1) {
      return new Failed(
          "oldStr matched "
              + matchStarts.size()
              + " regions under whitespace-tolerant search — add surrounding context to make it"
              + " unique, or use exact text (no changes written)");
    }

    int i = matchStarts.get(0);
    int spanStart = lineStarts.get(i);
    int spanEnd = (i + k < n) ? lineStarts.get(i + k) : content.length();
    boolean trailingNewline = spanEnd > spanStart && content.charAt(spanEnd - 1) == '\n';

    String replacement = newStr;
    if (indentFlexible) {
      String baseIndent = leadingWhitespace(lineContent(content, lineStarts, i));
      String oldIndent = leadingWhitespace(oldLines[0]);
      replacement = reindent(newStr, oldIndent, baseIndent);
    }
    // Preserve the matched block's trailing-newline shape.
    if (trailingNewline && !replacement.endsWith("\n")) {
      replacement = replacement + "\n";
    } else if (!trailingNewline && replacement.endsWith("\n")) {
      replacement = replacement.substring(0, replacement.length() - 1);
    }
    String updated = content.substring(0, spanStart) + replacement + content.substring(spanEnd);
    return new Applied(updated, indentFlexible ? "indent-flex" : "whitespace-tolerant");
  }

  private static String lineContent(String content, List<Integer> lineStarts, int idx) {
    int start = lineStarts.get(idx);
    int end = (idx + 1 < lineStarts.size()) ? lineStarts.get(idx + 1) - 1 : content.length();
    if (end < start) end = start;
    return content.substring(start, end);
  }

  private static boolean lineMatches(String contentLine, String oldLine, boolean indentFlexible) {
    if (indentFlexible) {
      return contentLine.strip().equals(oldLine.strip());
    }
    return contentLine.stripTrailing().equals(oldLine.stripTrailing());
  }

  /** Re-indent each line of {@code text}: strip {@code oldIndent}, prepend {@code newIndent}. */
  private static String reindent(String text, String oldIndent, String newIndent) {
    String[] lines = text.split("\n", -1);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.isBlank()) {
        sb.append(line); // keep blank lines blank
      } else if (!oldIndent.isEmpty() && line.startsWith(oldIndent)) {
        sb.append(newIndent).append(line.substring(oldIndent.length()));
      } else {
        // Line shallower than oldIndent (or oldIndent empty): shift by the new base indent.
        sb.append(newIndent).append(line.stripLeading());
      }
      if (i < lines.length - 1) sb.append('\n');
    }
    return sb.toString();
  }

  private static String leadingWhitespace(String s) {
    int i = 0;
    while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
    return s.substring(0, i);
  }

  private static String stripOneTrailingNewline(String s) {
    if (s.endsWith("\r\n")) return s.substring(0, s.length() - 2);
    if (s.endsWith("\n")) return s.substring(0, s.length() - 1);
    return s;
  }

  private static String splice(String content, int at, int oldLen, String newStr) {
    return content.substring(0, at) + newStr + content.substring(at + oldLen);
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }
}
