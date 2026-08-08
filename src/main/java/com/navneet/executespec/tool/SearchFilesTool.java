package com.navneet.executespec.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.security.SensitiveWorkspacePathPolicy;

import org.springframework.beans.factory.annotation.Autowired;
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
  private final ContextSanitizer contextSanitizer;

  public SearchFilesTool(RepositorySearchService repositorySearchService) {
    this(repositorySearchService, new ContextSanitizer());
  }

  @Autowired
  public SearchFilesTool(
      RepositorySearchService repositorySearchService, ContextSanitizer contextSanitizer) {
    this.repositorySearchService = repositorySearchService;
    this.contextSanitizer = contextSanitizer;
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

  /**
   * RR-644 — the JDK regex engine backtracks, so an unbounded pattern can pin a worker thread. Cap
   * the pattern length before it reaches the backtracking compiler. The per-line / per-file / walk-
   * deadline bounds live in {@link SearchFilesFallbackWalk}, the extracted Java-walk engine.
   */
  private static final int MAX_PATTERN_LEN = 2_000;

  @Override
  public String name() {
    return "searchFiles";
  }

  @Override
  public String description() {
    return "Recursively searches file contents for a regex pattern. Returns matching lines with"
        + " file:line prefixes plus a total match count. Filter file types via filePattern"
        + " (glob, e.g. '*.java'). Page large result sets with maxResults + offset, or"
        + " narrow the pattern. Use this to localize the exact file:line of a symbol before"
        + " reading/editing — it is cheaper than reading whole files. Use mode RELATIONSHIPS"
        + " for bounded import, test-to-source, and call-site edges. Build and VCS folders"
        + " are always skipped.";
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
      "enum": ["REGEX", "EXACT", "SYMBOL", "RELATIONSHIPS"],
      "description": "Search mode; defaults to REGEX. SYMBOL ranks declarations; RELATIONSHIPS returns bounded import/test/call-site edges for the identifier."
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
    int maxResults = args.hasNonNull("maxResults") ? args.get("maxResults").asInt() : DEFAULT_PAGE;
    maxResults = Math.max(1, Math.min(MAX_PAGE, maxResults));
    final int offset = args.hasNonNull("offset") ? Math.max(0, args.get("offset").asInt()) : 0;
    String rawMode = args.path("mode").asText(null);
    if ("RELATIONSHIPS".equalsIgnoreCase(rawMode)) {
      try {
        RepositorySearchService.RelationshipResult relationships =
            repositorySearchService.relationships(
                ctx.workspaceRoot(),
                new RepositorySearchService.RelationshipRequest(
                    patternStr, true, dir, filePattern, maxResults, offset));
        if (relationships.edges().isEmpty()) return ToolResult.ok("(no relationships)");
        StringBuilder typed =
            new StringBuilder()
                .append(relationships.totalRelationships())
                .append(relationships.hasMore() ? "+" : "")
                .append(" relationship(s); repositoryFingerprint=")
                .append(relationships.repositoryFingerprint())
                .append('\n');
        for (RepositorySearchService.RelationshipEdge edge : relationships.edges()) {
          if (SensitiveWorkspacePathPolicy.isSensitive(edge.sourcePath())
              || SensitiveWorkspacePathPolicy.isSensitive(edge.targetPath())) continue;
          typed
              .append(edge.relationshipType())
              .append(' ')
              .append(edge.sourcePath())
              .append(':')
              .append(edge.line())
              .append(" -> ")
              .append(edge.targetPath() == null ? "?" : edge.targetPath())
              .append(" [")
              .append(contextSanitizer.sanitizeOutput(edge.preview()))
              .append("]\n");
        }
        return ToolResult.ok(typed.toString().trim());
      } catch (IllegalArgumentException e) {
        return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, e.getMessage());
      } catch (SecurityException e) {
        return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, e.getMessage());
      } catch (RuntimeException e) {
        log.warn("relationship search failed", e);
        return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, "relationship search failed");
      }
    }
    RepositorySearchService.SearchMode mode;
    try {
      mode = parseMode(args.path("mode").asText(null));
    } catch (IllegalArgumentException e) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, e.getMessage());
    }

    // Exact/regex searches are the worker's hottest large-repository path. The structured Java
    // index below provides symbol classification and citation fingerprints, but it must discover
    // and content-hash the whole repository before returning a plain text match. Running that
    // path first made the documented ripgrep "fast path" unreachable on every successful search.
    // Keep SYMBOL/RELATIONSHIPS on the structured index; use ripgrep first for the two text modes
    // and fall back without changing correctness when rg is unavailable or rejects the regex.
    if (mode == RepositorySearchService.SearchMode.EXACT
        || mode == RepositorySearchService.SearchMode.REGEX) {
      ToolResult fast =
          tryRipgrepTextSearch(
              patternStr,
              dir,
              filePattern,
              mode == RepositorySearchService.SearchMode.EXACT,
              maxResults,
              offset,
              ctx);
      if (fast != null) return fast;
    }
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
        if (SensitiveWorkspacePathPolicy.isSensitive(hit.path())) continue;
        typed
            .append(hit.path())
            .append(':')
            .append(hit.line())
            .append(':')
            .append(contextSanitizer.sanitizeOutput(hit.preview()))
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
    // RR-644 — reject an over-long expression before it reaches the backtracking compiler.
    if (patternStr.length() > MAX_PATTERN_LEN) {
      return ToolResult.err(
          ToolResult.ErrorKind.INVALID_ARGS,
          "search pattern exceeds " + MAX_PATTERN_LEN + " characters");
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
      SearchFilesFallbackWalk.Outcome walk = null;
      if (ripgrepAvailable()) {
        matches = collectViaRipgrep(patternStr, searchRoot, workspaceReal, filePattern, false);
      }
      if (matches == null) {
        walk =
            new SearchFilesFallbackWalk(contextSanitizer)
                .collect(searchRoot, fileMatcher, regex, workspaceReal);
        matches = walk.matches();
      }

      if (matches.isEmpty()) {
        // "(no matches)" must mean "searched everything, found nothing" — not "gave up early".
        // If the walk skipped files or hit its deadline, say so, so the model does not conclude a
        // symbol is absent when the search never reached it.
        if (walk != null && walk.isIncomplete()) {
          return ToolResult.ok("(no matches so far) — " + walk.incompletenessNote());
        }
        return ToolResult.ok("(no matches)");
      }
      int total = matches.size();
      boolean ceilingHit = total >= MATCH_CEILING || (walk != null && walk.ceilingHit());
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
      // Surface the walk's skip ledger so a deadline-truncated or skip-heavy search is never
      // presented as a complete count.
      if (walk != null && walk.isIncomplete()) {
        sb.append("; ").append(walk.incompletenessNote());
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
      throw new IllegalArgumentException("mode must be REGEX, EXACT, SYMBOL, or RELATIONSHIPS");
    }
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
      String patternStr,
      Path searchRoot,
      Path workspaceReal,
      String filePattern,
      boolean fixedStrings) {
    List<String> cmd = new ArrayList<>();
    cmd.add("rg");
    cmd.add("--line-number");
    cmd.add("--no-heading");
    cmd.add("--color");
    cmd.add("never");
    cmd.add("--no-messages");
    // One contract, not two. By default rg honours .gitignore, .ignore, .git/info/exclude and
    // skips every dotfile/dot-directory; the Java-walk fallback honours NONE of those (it skips
    // only the explicit HIDDEN_DIRS set below). That made an identical query return different
    // results depending on whether `rg` was on the host PATH — and, because ExecuteSpec's own
    // workspace prep writes generated/vendor rules into .git/info/exclude, it made platform-ignored
    // source unsearchable on rg hosts while it stayed searchable on non-rg hosts. Force rg to match
    // the walk: search everything, and exclude ONLY the HIDDEN_DIRS below (via the -g globs) plus
    // the sensitive-path filter applied to every emitted line. Result sets are now
    // host-independent.
    cmd.add("--no-ignore");
    cmd.add("--hidden");
    if (fixedStrings) cmd.add("--fixed-strings");
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
      boolean ceilingHit = false;
      try (java.io.BufferedReader r =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(
                  proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
        String out;
        while ((out = r.readLine()) != null) {
          String outputPath = out.substring(0, Math.max(0, out.indexOf(':')));
          if (SensitiveWorkspacePathPolicy.isSensitive(outputPath)) continue;
          out = contextSanitizer.sanitizeOutput(out);
          String parsed = toMatchLine(out);
          if (parsed != null) matches.add(parsed);
          if (matches.size() >= MATCH_CEILING) {
            ceilingHit = true;
            break;
          }
        }
      }
      // Do not stop reading stdout and then wait for a producer blocked on a full pipe. Once the
      // contract ceiling is reached the remaining matches are intentionally irrelevant; terminate
      // rg and return the bounded evidence immediately.
      if (ceilingHit) {
        proc.destroyForcibly();
        return matches;
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

  /** Returns null when the caller should use the structured Java fallback. */
  private ToolResult tryRipgrepTextSearch(
      String pattern,
      String directory,
      String filePattern,
      boolean fixedStrings,
      int pageSize,
      int offset,
      ExecutionContext ctx) {
    if (!ripgrepAvailable()) return null;
    try {
      Path workspaceReal = ctx.workspaceRoot().toRealPath();
      Path searchRoot =
          (directory == null || directory.isBlank() || ".".equals(directory))
              ? workspaceReal
              : PathSafety.safeResolve(ctx.workspaceRoot(), directory);
      if (!Files.exists(searchRoot)) {
        return ToolResult.err(ToolResult.ErrorKind.NOT_FOUND, "directory not found: " + directory);
      }
      List<String> matches =
          collectViaRipgrep(pattern, searchRoot, workspaceReal, filePattern, fixedStrings);
      if (matches == null) return null;
      if (matches.isEmpty()) return ToolResult.ok("(no matches)");
      int total = matches.size();
      boolean ceilingHit = total >= MATCH_CEILING;
      int from = Math.min(offset, total);
      int to = Math.min(from + pageSize, total);
      StringBuilder out =
          new StringBuilder()
              .append(total)
              .append(ceilingHit ? "+" : "")
              .append(" match(es); engine=ripgrep");
      if (total > pageSize || offset > 0) {
        out.append("; showing ").append(from + 1).append('\u2013').append(to);
        if (to < total) {
          out.append(" — use offset=").append(to).append(" for more, or narrow the pattern");
        }
      }
      return ToolResult.ok(
          out.append('\n').append(String.join("\n", matches.subList(from, to))).toString());
    } catch (SecurityException e) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, e.getMessage());
    } catch (IOException e) {
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
