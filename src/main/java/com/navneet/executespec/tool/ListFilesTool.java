package com.navneet.executespec.tool;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * V2 listFiles tool. Lists directory contents with optional recursion.
 *
 * <p>Hides build artifacts and version-control noise so the LLM doesn't waste tokens on
 * uninteresting paths ({@code node_modules/}, {@code target/}, {@code .git/}, {@code .next/},
 * {@code dist/}, {@code build/}, {@code worktrees/}).
 */
@Component
@Slf4j
public class ListFilesTool implements Tool {

  /** Directories never returned by listFiles regardless of caller request. */
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

  /** Hard cap on entries returned to keep responses bounded. */
  private static final int MAX_ENTRIES = 500;

  @Override
  public String name() {
    return "listFiles";
  }

  @Override
  public String description() {
    return "Lists files and subdirectories under the given workspace-relative path. "
        + "Set recursive=true to walk the tree (build/VCS folders are always hidden).";
  }

  @Override
  public String inputSchema() {
    return """
           {
             "type": "object",
             "properties": {
               "path": {
                 "type": "string",
                 "description": "Workspace-relative directory path; use \\".\\" for the root"
               },
               "recursive": {
                 "type": "boolean",
                 "description": "If true, walk all subdirectories (default false)"
               }
             },
             "required": ["path"],
             "additionalProperties": false
           }
           """;
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String path = args.path("path").asText(null);
    if (path == null || path.isBlank()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "path is required");
    }
    boolean recursive = args.path("recursive").asBoolean(false);
    try {
      // listFiles allows "." which safeResolve rejects (blank). Map "." to the root explicitly.
      Path resolved =
          ".".equals(path)
              ? ctx.workspaceRoot().toRealPath()
              : PathSafety.safeResolve(ctx.workspaceRoot(), path);
      if (!Files.exists(resolved)) {
        return ToolResult.err(ToolResult.ErrorKind.NOT_FOUND, "directory not found: " + path);
      }
      if (!Files.isDirectory(resolved)) {
        return ToolResult.err(
            ToolResult.ErrorKind.INVALID_ARGS, "path is a file, not a directory: " + path);
      }
      List<String> entries = new ArrayList<>();
      Path rootReal = ctx.workspaceRoot().toRealPath();
      if (recursive) {
        Files.walkFileTree(
            resolved,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (HIDDEN_DIRS.contains(dir.getFileName().toString())) {
                  return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (entries.size() < MAX_ENTRIES) {
                  entries.add(rootReal.relativize(file).toString());
                }
                return entries.size() < MAX_ENTRIES
                    ? FileVisitResult.CONTINUE
                    : FileVisitResult.TERMINATE;
              }
            });
      } else {
        try (var stream = Files.list(resolved)) {
          stream
              .sorted()
              .forEach(
                  p -> {
                    if (HIDDEN_DIRS.contains(p.getFileName().toString())) return;
                    if (entries.size() >= MAX_ENTRIES) return;
                    String rel = rootReal.relativize(p).toString();
                    entries.add(Files.isDirectory(p) ? rel + "/" : rel);
                  });
        }
      }
      String body = String.join("\n", entries);
      if (entries.size() == MAX_ENTRIES) {
        body += "\n...[result truncated at " + MAX_ENTRIES + " entries]";
      }
      return ToolResult.ok(entries.isEmpty() ? "(empty)" : body);
    } catch (SecurityException se) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
    } catch (IOException ioe) {
      log.warn("listFiles IO error path={}", path, ioe);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    }
  }
}
