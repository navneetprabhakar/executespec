package com.navneet.executespec.v2.tool;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

/**
 * V2 searchFiles tool. Native (no shell subprocess) recursive content search.
 *
 * <p>Replaces V1's {@code grep}-via-shell implementation with a Java walk + regex.
 * Faster, safer (no shell injection surface), and produces structured output.
 *
 * <p>Always skips the same hidden directories as {@link ListFilesTool}.
 */
@Component
@Slf4j
public class SearchFilesTool implements Tool {

    private static final Set<String> HIDDEN_DIRS = Set.of(
            "node_modules", "target", "build", "dist", ".git", ".next", ".venv",
            "__pycache__", ".gradle", ".idea", ".vscode", "worktrees");

    private static final int MAX_MATCHES = 100;
    private static final int MAX_LINE_LEN = 240;

    @Override
    public String name() {
        return "searchFiles";
    }

    @Override
    public String description() {
        return "Recursively searches file contents for a regex pattern. Returns matching lines with "
                + "file:line prefixes. Filter file types via filePattern (glob, e.g. '*.java'). Build "
                + "and VCS folders are always skipped.";
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
        Pattern regex;
        try {
            regex = Pattern.compile(patternStr);
        } catch (PatternSyntaxException pse) {
            return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "invalid regex: " + pse.getMessage());
        }
        try {
            Path searchRoot = (dir == null || dir.isBlank() || ".".equals(dir))
                    ? ctx.workspaceRoot().toRealPath()
                    : PathSafety.safeResolve(ctx.workspaceRoot(), dir);
            if (!Files.exists(searchRoot)) {
                return ToolResult.err(ToolResult.ErrorKind.NOT_FOUND, "directory not found: " + dir);
            }
            PathMatcher fileMatcher = filePattern == null
                    ? null
                    : searchRoot.getFileSystem().getPathMatcher("glob:" + filePattern);
            Path workspaceReal = ctx.workspaceRoot().toRealPath();
            List<String> matches = new ArrayList<>();

            Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (HIDDEN_DIRS.contains(d.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= MAX_MATCHES) return FileVisitResult.TERMINATE;
                    if (fileMatcher != null && !fileMatcher.matches(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                    // Skip likely binaries
                    String name = file.getFileName().toString().toLowerCase();
                    if (name.endsWith(".jar") || name.endsWith(".class") || name.endsWith(".png")
                            || name.endsWith(".jpg") || name.endsWith(".gif") || name.endsWith(".pdf")
                            || name.endsWith(".zip") || name.endsWith(".gz")) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        List<String> lines = Files.readAllLines(file);
                        for (int i = 0; i < lines.size(); i++) {
                            String line = lines.get(i);
                            if (regex.matcher(line).find()) {
                                String rel = workspaceReal.relativize(file).toString();
                                String preview = line.length() > MAX_LINE_LEN
                                        ? line.substring(0, MAX_LINE_LEN) + "..."
                                        : line;
                                matches.add(rel + ":" + (i + 1) + ":" + preview);
                                if (matches.size() >= MAX_MATCHES) return FileVisitResult.TERMINATE;
                            }
                        }
                    } catch (IOException ignored) {
                        // skip files we can't read (binary, permission denied)
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (matches.isEmpty()) return ToolResult.ok("(no matches)");
            String body = String.join("\n", matches);
            if (matches.size() == MAX_MATCHES) {
                body += "\n...[result truncated at " + MAX_MATCHES + " matches]";
            }
            return ToolResult.ok(body);
        } catch (SecurityException se) {
            return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
        } catch (IOException ioe) {
            log.warn("searchFiles IO error pattern={}", patternStr, ioe);
            return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
        }
    }
}
