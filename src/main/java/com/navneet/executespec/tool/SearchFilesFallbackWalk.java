package com.navneet.executespec.tool;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.security.SensitiveWorkspacePathPolicy;

/**
 * The Java-walk + regex fallback engine for {@code searchFiles}. Reached when ripgrep is
 * unavailable or rejects the pattern dialect, or when the structured index throws. Extracted from
 * {@link SearchFilesTool} to keep that class under the 600-line ceiling.
 *
 * <p>Its whole reason for existing after the 2026-08-07 audit is the {@link Outcome} skip ledger: a
 * deadline-truncated, oversized-file, unreadable, or long-line skip is REPORTED rather than
 * silently dropped, so the caller never presents a partial search as a complete "found nothing".
 */
final class SearchFilesFallbackWalk {

  private static final Set<String> HIDDEN_DIRS =
      Set.of(
          "node_modules",
          "target",
          "build",
          "dist",
          ".git",
          ".next",
          ".venv",
          "__pycache__",
          ".gradle",
          ".idea",
          ".vscode",
          "worktrees");

  private static final int MATCH_CEILING = 1000;
  private static final int MAX_LINE_LEN = 240;
  private static final int MAX_MATCH_LINE_LEN = 20_000;
  private static final long MAX_SEARCH_FILE_BYTES = 8L * 1024 * 1024;
  private static final long WALK_DEADLINE_NANOS = 20L * 1_000_000_000L;

  private final ContextSanitizer contextSanitizer;

  SearchFilesFallbackWalk(ContextSanitizer contextSanitizer) {
    this.contextSanitizer = contextSanitizer;
  }

  /**
   * Outcome of a Java-walk search. Carries the matches AND an honest ledger of what the walk could
   * NOT search, so the caller never presents a deadline-truncated or skip-heavy walk as a complete
   * result. A partial search that looks identical to "found nothing" is exactly the failure the
   * 2026-08-07 audit was chasing: it makes the model re-implement code that already exists.
   */
  record Outcome(
      List<String> matches,
      boolean deadlineHit,
      boolean ceilingHit,
      int oversizedSkipped,
      int unreadableSkipped,
      int longLinesSkipped) {

    boolean isIncomplete() {
      return deadlineHit || oversizedSkipped > 0 || unreadableSkipped > 0 || longLinesSkipped > 0;
    }

    /** Human-readable "why this search may be incomplete" clause, or empty when it is complete. */
    String incompletenessNote() {
      if (!isIncomplete()) return "";
      List<String> parts = new ArrayList<>();
      if (deadlineHit)
        parts.add(
            "stopped at the "
                + (WALK_DEADLINE_NANOS / 1_000_000_000L)
                + "s deadline before finishing the tree");
      if (oversizedSkipped > 0)
        parts.add(
            oversizedSkipped
                + " file(s) >"
                + (MAX_SEARCH_FILE_BYTES / (1024 * 1024))
                + "MB not searched");
      if (unreadableSkipped > 0) parts.add(unreadableSkipped + " file(s) unreadable");
      if (longLinesSkipped > 0)
        parts.add(longLinesSkipped + " line(s) >" + MAX_MATCH_LINE_LEN + " chars not matched");
      return "search INCOMPLETE ("
          + String.join("; ", parts)
          + ") — narrow the pattern/directory or filePattern to search the rest";
    }
  }

  /** The original Java walk + regex. The reliable fallback when ripgrep isn't usable. */
  Outcome collect(Path searchRoot, PathMatcher fileMatcher, Pattern regex, Path workspaceReal)
      throws IOException {
    List<String> matches = new ArrayList<>();
    // Mutable ledger cells for the anonymous visitor. A skip that the model can't see is a lie.
    boolean[] deadlineHit = {false};
    boolean[] ceilingHit = {false};
    int[] oversized = {0};
    int[] unreadable = {0};
    int[] longLines = {0};
    long deadline = System.nanoTime() + WALK_DEADLINE_NANOS;
    Files.walkFileTree(
        searchRoot,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
            if (HIDDEN_DIRS.contains(d.getFileName().toString()))
              return FileVisitResult.SKIP_SUBTREE;
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (matches.size() >= MATCH_CEILING) {
              ceilingHit[0] = true;
              return FileVisitResult.TERMINATE;
            }
            // RR-644 — stop the whole walk once the wall-clock budget is spent. Record it: a
            // deadline-truncated walk is NOT a complete search.
            if (System.nanoTime() > deadline) {
              deadlineHit[0] = true;
              return FileVisitResult.TERMINATE;
            }
            // RR-644 — skip a file too large to decode whole before readAllLines allocates it.
            if (attrs.isRegularFile() && attrs.size() > MAX_SEARCH_FILE_BYTES) {
              oversized[0]++;
              return FileVisitResult.CONTINUE;
            }
            if (SensitiveWorkspacePathPolicy.isSensitive(file.toString())) {
              return FileVisitResult.CONTINUE;
            }
            // walkFileTree does not follow directory links, but a symlink visited as a file would
            // otherwise be opened by readAllLines. Keep the compatibility fallback subject to the
            // same workspace-realpath boundary as the structured search implementation.
            if (Files.isSymbolicLink(file) && !isInsideRealWorkspace(workspaceReal, file)) {
              return FileVisitResult.CONTINUE;
            }
            if (fileMatcher != null && !fileMatcher.matches(file.getFileName())) {
              return FileVisitResult.CONTINUE;
            }
            String name = file.getFileName().toString().toLowerCase();
            if (name.endsWith(".jar")
                || name.endsWith(".class")
                || name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".gif")
                || name.endsWith(".pdf")
                || name.endsWith(".zip")
                || name.endsWith(".gz")) {
              return FileVisitResult.CONTINUE;
            }
            try {
              List<String> lines = RepositoryTextCodec.lines(file);
              for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                // RR-644 — never feed a pathologically long line to the backtracking matcher.
                if (line.length() > MAX_MATCH_LINE_LEN) {
                  longLines[0]++;
                  continue;
                }
                if (regex.matcher(line).find()) {
                  String rel = workspaceReal.relativize(file).toString();
                  matches.add(
                      rel + ":" + (i + 1) + ":" + contextSanitizer.sanitizeOutput(clampLine(line)));
                  if (matches.size() >= MATCH_CEILING) {
                    ceilingHit[0] = true;
                    return FileVisitResult.TERMINATE;
                  }
                }
              }
            } catch (IOException ignored) {
              // A genuine I/O failure (permission denied, race). Count it — do not drop it
              // silently.
              unreadable[0]++;
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return new Outcome(
        matches, deadlineHit[0], ceilingHit[0], oversized[0], unreadable[0], longLines[0]);
  }

  private static String clampLine(String line) {
    return line.length() > MAX_LINE_LEN ? line.substring(0, MAX_LINE_LEN) + "..." : line;
  }

  private static boolean isInsideRealWorkspace(Path workspaceReal, Path file) {
    try {
      return file.toRealPath().startsWith(workspaceReal);
    } catch (IOException e) {
      return false;
    }
  }
}
