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
 * <p>Always skips the same hidden directories as {@link ListFilesTool}.
 */
@Component
@Slf4j
public class SearchFilesTool implements Tool {

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
      "description": "Regular expression to search for"
    },
    "directory": {
      "type": "string",
      "description": "Workspace-relative root for the search; default is workspace root"
    },
    "filePattern": {
      "type": "string",
      "description": "Optional glob to filter filenames (e.g. '*.java', '*.{ts,tsx}')"
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
              // Skip likely binaries
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
                    String preview =
                        line.length() > MAX_LINE_LEN
                            ? line.substring(0, MAX_LINE_LEN) + "..."
                            : line;
                    matches.add(rel + ":" + (i + 1) + ":" + preview);
                    if (matches.size() >= MATCH_CEILING) return FileVisitResult.TERMINATE;
                  }
                }
              } catch (IOException ignored) {
                // skip files we can't read (binary, permission denied)
              }
              return FileVisitResult.CONTINUE;
            }
          });

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
}
