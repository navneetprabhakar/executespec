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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * V2 searchFiles tool. Native (no shell subprocess) recursive content search.
 *
 * <p>Replaces V1's {@code grep}-via-shell implementation with a Java walk + regex. Faster, safer
 * (no shell injection surface), and produces structured output.
 *
 * <p><strong>ripgrep fast path (RL-2026-05-062):</strong> when the {@code rg} binary is on PATH the
 * search runs through it (much faster + gitignore-aware on large repos), parsed back into the exact
 * same {@code path:line:preview} shape. It's a direct (no-shell) {@code ProcessBuilder} with the
 * pattern passed after {@code --}, so there is still no shell-injection surface. Any problem (rg
 * missing, a pattern that isn't valid Rust-regex, a non-zero error exit, or a timeout) silently
 * falls back to the Java walk — so behaviour is never worse than before.
 *
 * <p>Always skips the same hidden directories as {@link ListFilesTool}.
 */
@Component
@Slf4j
public class SearchFilesTool implements Tool {

  private final RepositorySearchService repositorySearchService;

  public SearchFilesTool(RepositorySearchService repositorySearchService) {
    this.repositorySearchService = repositorySearchService;
  }

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

  /** Hard ceiling on matches collected per search — bounds memory + walk time on huge repos. */
  private static final int MATCH_CEILING = 1000;

  /** Default page size (matches returned per call) when the caller doesn't specify maxResults. */
  private static final int DEFAULT_PAGE = 50;

  /** Upper bound on a single page so one call can't flood the context. */
  private static final int MAX_PAGE = 200;

  private static final int MAX_LINE_LEN = 240;

  @Override
  public String name() {
    return "searchFiles";
  }

  @Override
  public String description() {
    return "Recursively searches file contents for a regex pattern. Returns matching lines with "
        + "file:line prefixes plus a total match count. Filter file types via filePattern (glob, "
        + "e.g. '*.java'). Page large result sets with maxResults + offset, or narrow the pattern. "
        + "Use this to localize the exact file:line of a symbol before reading/editing — it is "
        + "cheaper than reading whole files. Build and VCS folders are always skipped.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {
    "pattern": {
      "type": "string",
    "description": "Regular expression, or exact identifier when mode is SYMBOL"
    },
    "directory": {
      "type": "string",
      "description": "Workspace-relative root for the search; default is workspace root"
    },
    "filePattern": {
      "type": "string",
      "description": "Optional glob to filter filenames (e.g. '*.java', '*.{ts,tsx}')"
    },
    "mode": {
      "type": "string",
      "enum": ["REGEX", "EXACT", "SYMBOL"],
      "description": "Search mode; defaults to REGEX for compatibility. SYMBOL ranks exact declarations first."
    },
    "maxResults": {
      "type": "integer",
      "description": "Max matches to return this call (default 50, cap 200). Page with offset.",
      "minimum": 1
    },
    "offset": {
      "type": "integer",
      "description": "Skip the first N matches (for paging through a large result set).",
      "minimum": 0
    }
  },
  "required": ["pattern"],
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String patternStr = args.path("pattern").asText(null);
    if (patternStr == null || patternStr.isBlank()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "pattern is required");
    }
    String dir = args.path("directory").asText(null);
    String filePattern = args.path("filePattern").asText(null);
    RepositorySearchService.SearchMode mode;
    try {
      mode = parseMode(args.path("mode").asText(null));
    } catch (IllegalArgumentException e) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, e.getMessage());
    }
    int maxResults = args.hasNonNull("maxResults") ? args.get("maxResults").asInt() : DEFAULT_PAGE;
    maxResults = Math.max(1, Math.min(MAX_PAGE, maxResults));
    final int offset = args.hasNonNull("offset") ? Math.max(0, args.get("offset").asInt()) : 0;
    try {
      RepositorySearchService.SearchResult structured =
          repositorySearchService.search(
              ctx.workspaceRoot(),
              new RepositorySearchService.SearchRequest(
                  patternStr, mode, true, dir, filePattern, maxResults, offset));
      if (structured.hits().isEmpty()) return ToolResult.ok("(no matches)");
      StringBuilder typed = new StringBuilder();
      typed
          .append(structured.totalMatches())
          .append(structured.hasMore() ? "+" : "")
          .append(" match(es); repositorySha=")
          .append(structured.repositorySha())
          .append("; inventory=")
          .append(structured.inventoryFingerprint())
          .append(
              mode == RepositorySearchService.SearchMode.SYMBOL
                  ? "; repositoryFingerprint=" + structured.repositoryFingerprint()
                  : "")
          .append('\n');
      for (RepositorySearchService.SearchHit hit : structured.hits()) {
        typed
            .append(hit.path())
            .append(':')
            .append(hit.line())
            .append(':')
            .append(hit.preview())
            .append(
                mode == RepositorySearchService.SearchMode.SYMBOL
                    ? " [" + hit.matchType() + "] {reason=" + hit.relevanceReason() + '}'
                    : "")
            .append('\n');
      }
      return ToolResult.ok(typed.toString().trim());
    } catch (IllegalArgumentException e) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, e.getMessage());
    } catch (SecurityException e) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, e.getMessage());
    } catch (RuntimeException e) {
      log.warn("structured repository search failed; retaining compatibility fallback", e);
    }
    Pattern regex;
    try {
      regex = Pattern.compile(patternStr);
    } catch (PatternSyntaxException pse) {
      return ToolResult.err(
          ToolResult.ErrorKind.INVALID_ARGS, "invalid regex: " + pse.getMessage());
    }
    try {
      Path searchRoot =
          (dir == null || dir.isBlank() || ".".equals(dir))
              ? ctx.workspaceRoot().toRealPath()
              : PathSafety.safeResolve(ctx.workspaceRoot(), dir);
      if (!Files.exists(searchRoot)) {
        return ToolResult.err(ToolResult.ErrorKind.NOT_FOUND, "directory not found: " + dir);
      }
      PathMatcher fileMatcher =
          filePattern == null
              ? null
              : searchRoot.getFileSystem().getPathMatcher("glob:" + filePattern);
      Path workspaceReal = ctx.workspaceRoot().toRealPath();

      // Fast path: ripgrep (faster + gitignore-aware on large repos). Falls back to the Java walk
      // when rg isn't on PATH, the pattern isn't valid Rust-regex, or rg errors (RL-2026-05-062).
      List<String> matches = null;
      if (ripgrepAvailable()) {
        matches = collectViaRipgrep(patternStr, searchRoot, workspaceReal, filePattern);
      }
      if (matches == null) {
        matches = collectViaJavaWalk(searchRoot, fileMatcher, regex, workspaceReal);
      }

      if (matches.isEmpty()) return ToolResult.ok("(no matches)");
      int total = matches.size();
      boolean ceilingHit = total >= MATCH_CEILING;
      int from = Math.min(offset, total);
      int to = Math.min(from + maxResults, total);
      List<String> page = matches.subList(from, to);
      StringBuilder sb = new StringBuilder();
      sb.append(total).append(ceilingHit ? "+" : "").append(" match(es)");
      if (total > maxResults || offset > 0) {
        sb.append("; showing ").append(from + 1).append('–').append(to);
        if (to < total) {
          sb.append(" — use offset=").append(to).append(" for more, or narrow the pattern");
        }
      }
      sb.append('\n').append(String.join("\n", page));
      return ToolResult.ok(sb.toString());
    } catch (SecurityException se) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
    } catch (IOException ioe) {
      log.warn("searchFiles IO error pattern={}", patternStr, ioe);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    }
  }

  private static RepositorySearchService.SearchMode parseMode(String raw) {
    if (raw == null || raw.isBlank()) return RepositorySearchService.SearchMode.REGEX;
    try {
      return RepositorySearchService.SearchMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("mode must be REGEX, EXACT, or SYMBOL");
    }
  }

  /** The original Java walk + regex. The reliable fallback when ripgrep isn't usable. */
  private List<String> collectViaJavaWalk(
      Path searchRoot, PathMatcher fileMatcher, Pattern regex, Path workspaceReal)
      throws IOException {
    List<String> matches = new ArrayList<>();
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
            if (matches.size() >= MATCH_CEILING) return FileVisitResult.TERMINATE;
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
              List<String> lines = Files.readAllLines(file);
              for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (regex.matcher(line).find()) {
                  String rel = workspaceReal.relativize(file).toString();
                  matches.add(rel + ":" + (i + 1) + ":" + clampLine(line));
                  if (matches.size() >= MATCH_CEILING) return FileVisitResult.TERMINATE;
                }
              }
            } catch (IOException ignored) {
              // skip files we can't read (binary, permission denied)
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return matches;
  }

  /**
   * ripgrep fast path. Runs {@code rg} as a direct (no-shell) subprocess from the workspace root so
   * output paths are workspace-relative — identical {@code path:line:preview} shape to the Java
   * walk. Returns {@code null} (signalling fallback) on ANY problem: rg missing, non-zero error
   * exit (2, incl. an incompatible Rust-regex pattern), timeout, or IO error. Exit 1 = no matches =
   * empty list (a valid result, not a fallback). The pattern is passed after {@code --} so a
   * leading {@code -} can't be parsed as a flag (no shell = no injection surface).
   */
  private List<String> collectViaRipgrep(
      String patternStr, Path searchRoot, Path workspaceReal, String filePattern) {
    List<String> cmd = new ArrayList<>();
    cmd.add("rg");
    cmd.add("--line-number");
    cmd.add("--no-heading");
    cmd.add("--color");
    cmd.add("never");
    cmd.add("--no-messages");
    cmd.add("--max-count");
    cmd.add(String.valueOf(MATCH_CEILING)); // per-file cap; total still bounded below
    if (filePattern != null && !filePattern.isBlank()) {
      cmd.add("-g");
      cmd.add(filePattern);
    }
    for (String hidden : HIDDEN_DIRS) {
      cmd.add("-g");
      cmd.add("!**/" + hidden + "/**");
    }
    // Search path relative to the workspace root so rg prints workspace-relative paths.
    String relRoot = workspaceReal.relativize(searchRoot).toString();
    cmd.add("--");
    cmd.add(patternStr);
    cmd.add(relRoot.isEmpty() ? "." : relRoot);

    Process proc = null;
    try {
      proc =
          new ProcessBuilder(cmd)
              .directory(workspaceReal.toFile())
              .redirectErrorStream(false)
              .start();
      List<String> matches = new ArrayList<>();
      try (java.io.BufferedReader r =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(
                  proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
        String out;
        while ((out = r.readLine()) != null && matches.size() < MATCH_CEILING) {
          String parsed = toMatchLine(out);
          if (parsed != null) matches.add(parsed);
        }
      }
      boolean done = proc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
      if (!done) {
        proc.destroyForcibly();
        return null; // timeout -> fall back to the Java walk
      }
      int exit = proc.exitValue();
      // 0 = matches, 1 = no matches (both valid); anything else (2 = error / bad regex) ->
      // fallback.
      if (exit == 0 || exit == 1) return matches;
      return null;
    } catch (IOException ioe) {
      return null; // rg not installed / not on PATH
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      if (proc != null) proc.destroyForcibly();
      return null;
    }
  }

  /**
   * Parse an {@code rg} output line {@code path:line:content} into our {@code path:line:preview}.
   */
  private static String toMatchLine(String rgLine) {
    String[] parts = rgLine.split(":", 3);
    if (parts.length < 3) return null;
    String path = parts[0].startsWith("./") ? parts[0].substring(2) : parts[0];
    String lineNo = parts[1];
    if (!lineNo.chars().allMatch(Character::isDigit) || lineNo.isEmpty()) return null;
    return path + ":" + lineNo + ":" + clampLine(parts[2]);
  }

  private static String clampLine(String line) {
    return line.length() > MAX_LINE_LEN ? line.substring(0, MAX_LINE_LEN) + "..." : line;
  }

  /** Lazily probes {@code rg --version} once per JVM; caches the result. */
  private static volatile Boolean ripgrepAvailable;

  private static boolean ripgrepAvailable() {
    Boolean cached = ripgrepAvailable;
    if (cached != null) return cached;
    synchronized (SearchFilesTool.class) {
      if (ripgrepAvailable != null) return ripgrepAvailable;
      boolean ok;
      try {
        Process p = new ProcessBuilder("rg", "--version").redirectErrorStream(true).start();
        ok = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        if (!ok) p.destroyForcibly();
      } catch (IOException e) {
        ok = false; // not installed
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        ok = false;
      }
      ripgrepAvailable = ok;
      return ok;
    }
  }
}
