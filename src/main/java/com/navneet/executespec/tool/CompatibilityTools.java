package com.navneet.executespec.tool;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Compatibility tools for the CLI/local-hands contract. */
final class ReadFileCompatibilityTool implements Tool {
  private final ReadFileTool delegate;

  ReadFileCompatibilityTool(ReadFileTool delegate) {
    this.delegate = delegate;
  }

  @Override
  public String name() {
    return "read_file";
  }

  @Override
  public String description() {
    return "Reads a workspace-relative file. CLI-compatible alias of readFile; offset is 0-based"
        + " line offset and limit is number of lines.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {
    "path": {"type": "string", "description": "Workspace-relative path to the file"},
    "offset": {"type": "integer", "minimum": 0, "description": "0-based first line offset"},
    "limit": {"type": "integer", "minimum": 1, "description": "Maximum lines to return"}
  },
  "required": ["path"],
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    ObjectNode forwarded = JsonSupport.JSON.createObjectNode();
    forwarded.put("path", args.path("path").asText(null));
    if (args.hasNonNull("offset") || args.hasNonNull("limit")) {
      int offset = Math.max(0, args.path("offset").asInt(0));
      int limit = Math.max(1, args.path("limit").asInt(200));
      forwarded.put("startLine", offset + 1);
      forwarded.put("endLine", offset + limit);
    }
    return delegate.execute(forwarded, ctx);
  }
}

final class WriteFileCompatibilityTool implements Tool {
  private final WriteFileTool delegate;

  WriteFileCompatibilityTool(WriteFileTool delegate) {
    this.delegate = delegate;
  }

  @Override
  public String name() {
    return "write_file";
  }

  @Override
  public String description() {
    return "Creates a file. Refuses to overwrite an existing file unless overwrite_policy is set;"
        + " server-side overwrite remains intentionally unavailable, use edit_file/editFile.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {
    "path": {"type": "string", "description": "Workspace-relative file path"},
    "content": {"type": "string", "description": "File contents"},
    "overwrite_policy": {
      "type": "string",
      "description": "fail_if_exists (default) or overwrite. Server-side overwrite is refused; local-hands clients may support it."
    },
    "overwritePolicy": {"type": "string", "description": "camelCase alias for overwrite_policy"}
  },
  "required": ["path", "content"],
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String policy = text(args, "overwrite_policy");
    if (policy == null) policy = text(args, "overwritePolicy");
    if (policy != null
        && List.of("overwrite", "replace", "clobber", "allow").contains(policy.toLowerCase())) {
      return ToolResult.err(
          ToolResult.ErrorKind.UNAVAILABLE,
          "server-side write_file overwrite is not enabled; use edit_file after read_file");
    }
    ToolResult result = delegate.execute(args, ctx);
    if (result instanceof ToolResult.Err err
        && err.kind() == ToolResult.ErrorKind.INVALID_ARGS
        && err.message().contains("file already exists")) {
      return ToolResult.err(ToolResult.ErrorKind.ALREADY_EXISTS, err.message());
    }
    return result;
  }

  private static String text(JsonNode args, String field) {
    if (args == null || !args.hasNonNull(field)) return null;
    String s = args.get(field).asText(null);
    return s == null || s.isBlank() ? null : s;
  }
}

final class EditFileCompatibilityTool implements Tool {
  private final EditFileTool delegate;

  EditFileCompatibilityTool(EditFileTool delegate) {
    this.delegate = delegate;
  }

  @Override
  public String name() {
    return "edit_file";
  }

  @Override
  public String description() {
    return "Applies one exact replacement to an existing file. Requires a prior read_file/readFile;"
        + " refuses stale or ambiguous edits.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {
    "path": {"type": "string", "description": "Workspace-relative existing file path"},
    "old_str": {"type": "string", "description": "Text to replace"},
    "new_str": {"type": "string", "description": "Replacement text"},
    "oldString": {"type": "string", "description": "camelCase alias for old_str"},
    "newString": {"type": "string", "description": "camelCase alias for new_str"},
    "expected_count": {"type": "integer", "minimum": 1, "description": "Required exact match count"},
    "expectedCount": {"type": "integer", "minimum": 1, "description": "camelCase alias for expected_count"},
    "replaceAll": {"type": "boolean", "description": "Replace all occurrences"}
  },
  "required": ["path"],
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String path = args.path("path").asText(null);
    String oldStr = text(args, "old_str");
    if (oldStr == null) oldStr = text(args, "oldString");
    String newStr = text(args, "new_str");
    if (newStr == null) newStr = text(args, "newString");
    if (oldStr == null || newStr == null) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "old_str/new_str are required");
    }
    Integer expected = null;
    if (args.hasNonNull("expected_count")) {
      expected = args.get("expected_count").asInt();
    } else if (args.hasNonNull("expectedCount")) {
      expected = args.get("expectedCount").asInt();
    }
    if (expected != null) {
      try {
        String current = Files.readString(PathSafety.safeResolve(ctx.workspaceRoot(), path));
        int count = count(current, oldStr);
        if (count != expected) {
          return ToolResult.err(
              ToolResult.ErrorKind.INVALID_ARGS,
              "expected " + expected + " matches in " + path + ", found " + count);
        }
      } catch (SecurityException se) {
        return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
      } catch (IOException ioe) {
        return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
      }
    }
    ObjectNode forwarded = JsonSupport.JSON.createObjectNode();
    forwarded.put("path", path);
    ArrayNode edits = forwarded.putArray("edits");
    ObjectNode edit = edits.addObject();
    edit.put("oldStr", oldStr);
    edit.put("newStr", newStr);
    if (args.path("replaceAll").asBoolean(false)) edit.put("replaceAll", true);
    ToolResult result = delegate.execute(forwarded, ctx);
    if (result instanceof ToolResult.Err err
        && err.kind() == ToolResult.ErrorKind.INVALID_ARGS
        && (err.message().contains("read-before-edit")
            || err.message().contains("view is stale"))) {
      return ToolResult.err(ToolResult.ErrorKind.STALE_READ, err.message());
    }
    return result;
  }

  private static int count(String haystack, String needle) {
    if (needle == null || needle.isEmpty()) return 0;
    int n = 0;
    int at = 0;
    while ((at = haystack.indexOf(needle, at)) >= 0) {
      n++;
      at += needle.length();
    }
    return n;
  }

  private static String text(JsonNode args, String field) {
    if (args == null || !args.hasNonNull(field)) return null;
    String s = args.get(field).asText(null);
    return s == null ? null : s;
  }
}

class GrepCompatibilityTool implements Tool {
  private final SearchFilesTool delegate;

  GrepCompatibilityTool(SearchFilesTool delegate) {
    this.delegate = delegate;
  }

  @Override
  public String name() {
    return "grep";
  }

  @Override
  public String description() {
    return "Searches workspace file contents for a regex. CLI-compatible alias of searchFiles.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {
    "pattern": {"type": "string", "description": "Regex to search for"},
    "query": {"type": "string", "description": "Alias for pattern"},
    "scope": {"type": "string", "description": "Workspace-relative directory scope"},
    "path": {"type": "string", "description": "Alias for scope"},
    "flags": {"type": "string", "description": "Reserved for client parity"},
    "maxResults": {"type": "integer", "minimum": 1}
  },
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    ObjectNode forwarded = JsonSupport.JSON.createObjectNode();
    String pattern = text(args, "pattern");
    if (pattern == null) pattern = text(args, "query");
    forwarded.put("pattern", pattern);
    String scope = text(args, "scope");
    if (scope == null) scope = text(args, "path");
    if (scope != null) forwarded.put("directory", scope);
    if (args.hasNonNull("maxResults")) forwarded.put("maxResults", args.get("maxResults").asInt());
    return delegate.execute(forwarded, ctx);
  }

  private static String text(JsonNode args, String field) {
    if (args == null || !args.hasNonNull(field)) return null;
    String s = args.get(field).asText(null);
    return s == null || s.isBlank() ? null : s;
  }
}

final class MultiEditCompatibilityTool implements Tool {
  private final String name;
  private final EditFileTool delegate;

  MultiEditCompatibilityTool(String name, EditFileTool delegate) {
    this.name = name;
    this.delegate = delegate;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String description() {
    return "Applies a batch of string edits. Each item contains path plus oldString/old_str and"
        + " newString/new_str. Server-side execution preflights the batch before writing.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {
    "edits": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "properties": {
          "path": {"type": "string"},
          "oldString": {"type": "string"},
          "newString": {"type": "string"},
          "old_str": {"type": "string"},
          "new_str": {"type": "string"},
          "replaceAll": {"type": "boolean"}
        },
        "required": ["path"],
        "additionalProperties": false
      }
    }
  },
  "required": ["edits"],
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    JsonNode editsNode = args.path("edits");
    if (!editsNode.isArray() || editsNode.isEmpty()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "edits must be a non-empty array");
    }
    for (JsonNode edit : editsNode) {
      ToolResult preflight = preflightOne(edit, ctx);
      if (preflight instanceof ToolResult.Err) return preflight;
    }
    int applied = 0;
    for (JsonNode edit : editsNode) {
      ObjectNode forwarded = JsonSupport.JSON.createObjectNode();
      forwarded.put("path", edit.path("path").asText(null));
      ArrayNode batch = forwarded.putArray("edits");
      ObjectNode item = batch.addObject();
      String oldStr = text(edit, "oldString");
      if (oldStr == null) oldStr = text(edit, "old_str");
      String newStr = text(edit, "newString");
      if (newStr == null) newStr = text(edit, "new_str");
      item.put("oldStr", oldStr);
      item.put("newStr", newStr);
      if (edit.path("replaceAll").asBoolean(false)) item.put("replaceAll", true);
      ToolResult result = delegate.execute(forwarded, ctx);
      if (result instanceof ToolResult.Err err) return remapStaleRead(err);
      applied++;
    }
    return ToolResult.ok("applied " + applied + " edit(s)");
  }

  private static ToolResult preflightOne(JsonNode edit, ExecutionContext ctx) {
    String path = edit.path("path").asText(null);
    String oldStr = text(edit, "oldString");
    if (oldStr == null) oldStr = text(edit, "old_str");
    String newStr = text(edit, "newString");
    if (newStr == null) newStr = text(edit, "new_str");
    if (path == null || path.isBlank() || oldStr == null || newStr == null) {
      return ToolResult.err(
          ToolResult.ErrorKind.INVALID_ARGS, "each edit requires path and old/new text");
    }
    try {
      Path file = PathSafety.safeResolve(ctx.workspaceRoot(), path);
      if (!Files.exists(file))
        return ToolResult.err(ToolResult.ErrorKind.NOT_FOUND, "file not found: " + path);
      String content = Files.readString(file);
      if (!content.contains(oldStr)) {
        return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "oldString not found in " + path);
      }
      return ToolResult.ok("ok");
    } catch (SecurityException se) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
    } catch (IOException ioe) {
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    }
  }

  private static String text(JsonNode args, String field) {
    if (args == null || !args.hasNonNull(field)) return null;
    String s = args.get(field).asText(null);
    return s == null ? null : s;
  }

  private static ToolResult.Err remapStaleRead(ToolResult.Err err) {
    if (err.kind() == ToolResult.ErrorKind.INVALID_ARGS
        && (err.message().contains("read-before-edit")
            || err.message().contains("view is stale"))) {
      return new ToolResult.Err(err.message(), ToolResult.ErrorKind.STALE_READ);
    }
    return err;
  }
}

final class GlobTool implements Tool {
  private static final int MAX_RESULTS = 500;

  @Override
  public String name() {
    return "glob";
  }

  @Override
  public String description() {
    return "Returns workspace-relative paths matching a glob pattern. Build/VCS folders and simple"
        + " .gitignore entries are skipped.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {
    "pattern": {"type": "string", "description": "Glob pattern, e.g. **/*.java"},
    "base": {"type": "string", "description": "Workspace-relative search root; default ."}
  },
  "required": ["pattern"],
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String pattern = args.path("pattern").asText(null);
    if (pattern == null || pattern.isBlank()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "pattern is required");
    }
    try {
      Path root = ctx.workspaceRoot().toRealPath();
      Path base =
          text(args, "base") == null || ".".equals(text(args, "base"))
              ? root
              : PathSafety.safeResolve(ctx.workspaceRoot(), text(args, "base"));
      PathMatcher matcher = root.getFileSystem().getPathMatcher("glob:" + pattern);
      List<Pattern> ignores = gitignorePatterns(root);
      List<String> out = new ArrayList<>();
      Files.walkFileTree(
          base,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
              if (List.of(".git", "node_modules", "target", "build", "dist", ".next")
                  .contains(name)) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              String rel = root.relativize(dir).toString().replace('\\', '/');
              return ignored(rel, ignores)
                  ? FileVisitResult.SKIP_SUBTREE
                  : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              String rel = root.relativize(file).toString().replace('\\', '/');
              if (out.size() < MAX_RESULTS
                  && !ignored(rel, ignores)
                  && matcher.matches(Path.of(rel))) {
                out.add(rel);
              }
              return out.size() < MAX_RESULTS
                  ? FileVisitResult.CONTINUE
                  : FileVisitResult.TERMINATE;
            }
          });
      return ToolResult.ok(out.isEmpty() ? "no matches" : String.join("\n", out));
    } catch (SecurityException se) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
    } catch (IOException ioe) {
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    }
  }

  private static String text(JsonNode args, String field) {
    if (args == null || !args.hasNonNull(field)) return null;
    String s = args.get(field).asText(null);
    return s == null || s.isBlank() ? null : s;
  }

  private static List<Pattern> gitignorePatterns(Path root) {
    Path file = root.resolve(".gitignore");
    if (!Files.isRegularFile(file)) return List.of();
    try {
      List<Pattern> patterns = new ArrayList<>();
      for (String raw : Files.readAllLines(file)) {
        String line = raw.trim();
        if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) continue;
        boolean dir = line.endsWith("/");
        if (dir) line = line.substring(0, line.length() - 1);
        String quoted = Pattern.quote(line).replace("\\*", "\\E[^/]*\\Q");
        patterns.add(Pattern.compile("(^|.*/)" + quoted + (dir ? "(/.*)?$" : "$")));
      }
      return patterns;
    } catch (IOException e) {
      return List.of();
    }
  }

  private static boolean ignored(String rel, List<Pattern> patterns) {
    return patterns.stream().anyMatch(p -> p.matcher(rel).matches());
  }
}

final class MovePathTool implements Tool {
  @Override
  public String name() {
    return "move";
  }

  @Override
  public String description() {
    return "Moves/renames a file or directory within the workspace. Refuses destination"
        + " collisions.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {
    "src": {"type": "string", "description": "Workspace-relative source path"},
    "dst": {"type": "string", "description": "Workspace-relative destination path"}
  },
  "required": ["src", "dst"],
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    try {
      String srcRel = args.path("src").asText(null);
      String dstRel = args.path("dst").asText(null);
      Path src = PathSafety.safeResolve(ctx.workspaceRoot(), srcRel);
      Path dst = PathSafety.safeResolve(ctx.workspaceRoot(), dstRel);
      if (!Files.exists(src))
        return ToolResult.err(ToolResult.ErrorKind.NOT_FOUND, "not found: " + srcRel);
      if (Files.exists(dst)) {
        return ToolResult.err(ToolResult.ErrorKind.ALREADY_EXISTS, "destination exists: " + dstRel);
      }
      Path parent = dst.getParent();
      if (parent != null) Files.createDirectories(parent);
      Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
      return ToolResult.ok("moved " + srcRel + " to " + dstRel);
    } catch (SecurityException se) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
    } catch (IOException ioe) {
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    }
  }
}

final class DeletePathTool implements Tool {
  @Override
  public String name() {
    return "delete";
  }

  @Override
  public String description() {
    return "Deletes a workspace-confined path. Directories require recursive=true.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {
    "path": {"type": "string", "description": "Workspace-relative path to delete"},
    "recursive": {"type": "boolean", "description": "Allow deleting a directory tree"}
  },
  "required": ["path"],
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    try {
      String rel = args.path("path").asText(null);
      Path target = PathSafety.safeResolve(ctx.workspaceRoot(), rel);
      if (!Files.exists(target))
        return ToolResult.err(ToolResult.ErrorKind.NOT_FOUND, "not found: " + rel);
      if (Files.isDirectory(target) && !args.path("recursive").asBoolean(false)) {
        return ToolResult.err(
            ToolResult.ErrorKind.INVALID_ARGS, "directory delete requires recursive=true");
      }
      if (Files.isDirectory(target)) {
        try (var stream = Files.walk(target)) {
          for (Path p : stream.sorted((a, b) -> b.compareTo(a)).toList()) Files.delete(p);
        }
      } else {
        Files.delete(target);
      }
      return ToolResult.ok("deleted " + rel);
    } catch (SecurityException se) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
    } catch (IOException ioe) {
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    }
  }
}

final class BashStructuredTool implements Tool {
  private final ShellTool shell;

  BashStructuredTool(ShellTool shell) {
    this.shell = shell;
  }

  @Override
  public String name() {
    return "bash";
  }

  @Override
  public String description() {
    return "Runs a sandboxed shell command. CLI-compatible alias returning JSON"
        + " {stdout,stderr,exit_code}; non-zero exits return COMMAND_FAILED.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {
    "command": {"type": "string", "description": "Shell command to execute"},
    "timeout": {"type": "integer", "minimum": 1, "description": "Timeout in milliseconds"},
    "timeoutSeconds": {"type": "integer", "minimum": 1, "maximum": 300},
    "cwd": {"type": "string", "description": "Reserved; server-side bash only supports workspace root"}
  },
  "required": ["command"],
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String cwd = args.path("cwd").asText(null);
    if (cwd != null && !cwd.isBlank() && !".".equals(cwd)) {
      return ToolResult.err(
          ToolResult.ErrorKind.UNAVAILABLE,
          "server-side bash does not persist cwd; use workspace-relative commands");
    }
    ObjectNode forwarded = JsonSupport.JSON.createObjectNode();
    forwarded.put("command", args.path("command").asText(null));
    if (args.hasNonNull("timeoutSeconds")) {
      forwarded.put("timeoutSeconds", args.get("timeoutSeconds").asInt());
    } else if (args.hasNonNull("timeout")) {
      forwarded.put("timeoutSeconds", Math.max(1, args.get("timeout").asInt() / 1000));
    }
    ToolResult result = shell.execute(forwarded, ctx);
    if (result instanceof ToolResult.Err err) return err;
    String content = ((ToolResult.Ok) result).content();
    int exit = parseExit(content);
    String json = JsonSupport.jsonShell(content.replaceFirst("^exit=\\d+\\n?", ""), "", exit);
    if (exit != 0) return ToolResult.err(ToolResult.ErrorKind.COMMAND_FAILED, json);
    return ToolResult.ok(json);
  }

  private static int parseExit(String content) {
    if (content == null) return 0;
    java.util.regex.Matcher m = Pattern.compile("^exit=(\\d+)").matcher(content);
    return m.find() ? Integer.parseInt(m.group(1)) : 0;
  }
}

final class BackgroundProcessCompatibilityTool implements Tool {
  private final BackgroundJobTool delegate;

  BackgroundProcessCompatibilityTool(BackgroundJobTool delegate) {
    this.delegate = delegate;
  }

  @Override
  public String name() {
    return "background_process";
  }

  @Override
  public String description() {
    return "Starts, reads, or stops a background process. Local-hands compatible alias of"
        + " backgroundJob.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {
    "action": {"type": "string", "enum": ["start", "read", "read-output", "stop"]},
    "command": {"type": "string"},
    "id": {"type": "string"},
    "jobId": {"type": "string"}
  },
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    ObjectNode forwarded = JsonSupport.JSON.createObjectNode();
    String action = args.path("action").asText("start");
    forwarded.put(
        "action",
        switch (action) {
          case "read", "read-output" -> "logs";
          case "stop" -> "kill";
          default -> action;
        });
    if (args.hasNonNull("command")) forwarded.put("command", args.get("command").asText());
    String id = args.hasNonNull("id") ? args.get("id").asText() : args.path("jobId").asText(null);
    if (id != null) forwarded.put("jobId", id);
    return delegate.execute(forwarded, ctx);
  }
}

final class WebFetchTool implements Tool {
  private static final int MAX_CHARS = 8_000;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  @Override
  public String name() {
    return "web_fetch";
  }

  @Override
  public String description() {
    return "Fetches an HTTP/HTTPS URL and returns capped text content.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {"url": {"type": "string", "description": "HTTP/HTTPS URL"}},
  "required": ["url"],
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    try {
      URI uri = URI.create(args.path("url").asText(null));
      if (!List.of("http", "https").contains(uri.getScheme())) {
        return ToolResult.err(
            ToolResult.ErrorKind.FORBIDDEN, "unsupported URL scheme: " + uri.getScheme());
      }
      HttpResponse<String> res =
          http.send(
              HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (res.statusCode() < 200 || res.statusCode() >= 300) {
        return ToolResult.err(
            ToolResult.ErrorKind.HTTP_ERROR, "fetch failed status=" + res.statusCode());
      }
      return ToolResult.ok(clamp(res.body()));
    } catch (IllegalArgumentException iae) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, iae.getMessage());
    } catch (IOException ioe) {
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return ToolResult.err(ToolResult.ErrorKind.INTERNAL, "interrupted");
    }
  }

  private static String clamp(String text) {
    return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS) + "\n...[truncated]";
  }
}

final class WebSearchTool implements Tool {
  private final WebFetchTool fetch = new WebFetchTool();

  @Override
  public String name() {
    return "web_search";
  }

  @Override
  public String description() {
    return "Performs a lightweight web search and returns stripped result text.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {"query": {"type": "string", "description": "Search query"}},
  "required": ["query"],
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String query = args.path("query").asText(null);
    if (query == null || query.isBlank()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "query is required");
    }
    ObjectNode forwarded = JsonSupport.JSON.createObjectNode();
    forwarded.put(
        "url",
        "https://duckduckgo.com/html/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
    ToolResult res = fetch.execute(forwarded, ctx);
    if (res instanceof ToolResult.Err err) return err;
    String html = ((ToolResult.Ok) res).content();
    return ToolResult.ok(html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
  }
}

final class DiagnosticsTool implements Tool {
  private final ProjectCommandTool delegate;

  DiagnosticsTool(ShellTool shell) {
    this.delegate =
        new ProjectCommandTool(
            "diagnostics",
            "Run detected tests/build and parse file:line diagnostics",
            ProjectCommandTool.Kind.TEST,
            shell);
  }

  @Override
  public String name() {
    return "diagnostics";
  }

  @Override
  public String description() {
    return "Runs a diagnostic command and returns shell output. Prefer an explicit command for"
        + " deterministic results.";
  }

  @Override
  public String inputSchema() {
    return ProjectCommandSchemas.OPTIONAL_COMMAND_SCHEMA;
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    return delegate.execute(args, ctx);
  }
}

final class AstEditCompatibilityTool implements Tool {
  private final String name;
  private final EditFileCompatibilityTool delegate;

  AstEditCompatibilityTool(String name, EditFileTool editFileTool) {
    this.name = name;
    this.delegate = new EditFileCompatibilityTool(editFileTool);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String description() {
    return "Applies a safe exact replacement through the same read-before-edit path as edit_file.";
  }

  @Override
  public String inputSchema() {
    return """
{"type":"object","properties":{"path":{"type":"string"},"oldString":{"type":"string"},"newString":{"type":"string"},"old_str":{"type":"string"},"new_str":{"type":"string"},"pattern":{"type":"string"},"replacement":{"type":"string"},"replaceAll":{"type":"boolean"},"expected_count":{"type":"integer","minimum":1},"expectedCount":{"type":"integer","minimum":1}},"required":["path"],"additionalProperties":false}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    ObjectNode forwarded = JsonSupport.JSON.createObjectNode();
    forwarded.put("path", args.path("path").asText(null));
    String oldText = firstText(args, "oldString", "old_str", "pattern");
    String newText = firstText(args, "newString", "new_str", "replacement");
    if (oldText == null || newText == null) {
      return ToolResult.err(
          ToolResult.ErrorKind.INVALID_ARGS,
          "ast_edit requires path plus oldString/newString (or pattern/replacement)");
    }
    forwarded.put("old_str", oldText);
    forwarded.put("new_str", newText);
    if (args.path("replaceAll").asBoolean(false)) forwarded.put("replaceAll", true);
    if (args.hasNonNull("expected_count"))
      forwarded.put("expected_count", args.get("expected_count").asInt());
    if (args.hasNonNull("expectedCount"))
      forwarded.put("expectedCount", args.get("expectedCount").asInt());
    return delegate.execute(forwarded, ctx);
  }

  private static String firstText(JsonNode args, String... names) {
    for (String name : names) {
      if (args.hasNonNull(name)) return args.get(name).asText();
    }
    return null;
  }
}

final class LspFallbackTool implements Tool {
  @Override
  public String name() {
    return "lsp";
  }

  @Override
  public String description() {
    return "Local lightweight LSP fallback: document_symbols, definition, references, diagnostics"
        + " note.";
  }

  @Override
  public String inputSchema() {
    return """
{"type":"object","properties":{"action":{"type":"string"},"path":{"type":"string"},"symbol":{"type":"string"},"name":{"type":"string"},"query":{"type":"string"}},"required":["action"],"additionalProperties":false}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String action = args.path("action").asText("");
    try {
      if ("document_symbols".equals(action)
          || "documentSymbols".equals(action)
          || "symbols".equals(action)) {
        return symbols(args, ctx);
      }
      if ("definition".equals(action) || "references".equals(action)) {
        return references(args, ctx, action);
      }
      if ("diagnostics".equals(action)) {
        ObjectNode node = JsonSupport.JSON.createObjectNode();
        node.put("note", "Use diagnostics/build/test tools for executable diagnostics.");
        return ToolResult.ok(node.toString());
      }
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "unsupported lsp action: " + action);
    } catch (SecurityException se) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
    } catch (IOException ioe) {
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    }
  }

  private ToolResult symbols(JsonNode args, ExecutionContext ctx) throws IOException {
    String path = args.path("path").asText(null);
    if (path == null || path.isBlank()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "path is required");
    }
    Path file = PathSafety.safeResolve(ctx.workspaceRoot(), path);
    String text = Files.readString(file);
    ArrayNode arr = JsonSupport.JSON.createArrayNode();
    Pattern pattern =
        Pattern.compile(
            "(?m)^\\s*(?:export\\s+)?(?:async\\s+)?(?:function|class|const|let|var|def)\\s+([A-Za-z_$][\\w$]*)");
    var matcher = pattern.matcher(text);
    while (matcher.find() && arr.size() < 200) {
      ObjectNode item = arr.addObject();
      item.put("name", matcher.group(1));
      item.put(
          "line",
          1 + (int) text.substring(0, matcher.start()).chars().filter(ch -> ch == '\n').count());
    }
    ObjectNode out = JsonSupport.JSON.createObjectNode();
    out.put("path", path);
    out.set("symbols", arr);
    return ToolResult.ok(out.toString());
  }

  private ToolResult references(JsonNode args, ExecutionContext ctx, String action)
      throws IOException {
    String symbol = firstText(args, "symbol", "name", "query");
    if (symbol == null || symbol.isBlank()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "symbol/name/query is required");
    }
    Path root = ctx.workspaceRoot();
    ArrayNode matches = JsonSupport.JSON.createArrayNode();
    Pattern token = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");
    try (var stream = Files.walk(root, 8)) {
      for (Path file : stream.filter(Files::isRegularFile).limit(500).toList()) {
        String rel = root.relativize(file).toString();
        if (rel.contains(".git/") || rel.contains("node_modules/") || rel.contains("target/"))
          continue;
        String text;
        try {
          text = Files.readString(file);
        } catch (Exception ignored) {
          continue;
        }
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length && matches.size() < 100; i++) {
          if (token.matcher(lines[i]).find()) {
            matches.add(rel + ":" + (i + 1) + ": " + lines[i].trim());
          }
        }
      }
    }
    ObjectNode out = JsonSupport.JSON.createObjectNode();
    out.put("action", action);
    out.put("symbol", symbol);
    out.set("matches", matches);
    return ToolResult.ok(out.toString());
  }

  private static String firstText(JsonNode args, String... names) {
    for (String name : names) {
      if (args.hasNonNull(name)) return args.get(name).asText();
    }
    return null;
  }
}

final class PlanStateTool implements Tool {
  private final String name;
  private final java.util.concurrent.ConcurrentMap<String, JsonNode> plans =
      new java.util.concurrent.ConcurrentHashMap<>();

  PlanStateTool(String name) {
    this.name = name;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String description() {
    return "Records structured plan/todo state for the current run and returns the latest plan.";
  }

  @Override
  public String inputSchema() {
    return """
{"type":"object","properties":{"plan":{"type":"array"},"items":{"type":"array"},"todos":{"type":"array"},"step":{"type":"string"},"status":{"type":"string"}},"additionalProperties":true}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    JsonNode plan = args.path("plan");
    if (plan.isMissingNode() || plan.isNull()) plan = args.path("items");
    if (plan.isMissingNode() || plan.isNull()) plan = args.path("todos");
    if (plan.isMissingNode() || plan.isNull()) {
      ObjectNode single = JsonSupport.JSON.createObjectNode();
      single.put(
          "step",
          args.path("step").asText(args.path("content").asText(args.path("task").asText(""))));
      single.put("status", args.path("status").asText("pending"));
      ArrayNode arr = JsonSupport.JSON.createArrayNode();
      arr.add(single);
      plan = arr;
    }
    plans.put(ctx.runId(), plan);
    ObjectNode out = JsonSupport.JSON.createObjectNode();
    out.set("plan", plan);
    out.put("count", plan.isArray() ? plan.size() : 1);
    return ToolResult.ok(out.toString());
  }
}

final class SpawnSubagentFallbackTool implements Tool {
  private final String name;

  SpawnSubagentFallbackTool(String name) {
    this.name = name;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String description() {
    return "Performs bounded local repo reconnaissance for the parent agent.";
  }

  @Override
  public String inputSchema() {
    return """
{"type":"object","properties":{"task":{"type":"string"},"prompt":{"type":"string"},"query":{"type":"string"},"path":{"type":"string"}},"additionalProperties":true}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String task = firstText(args, "task", "prompt", "description");
    if (task == null || task.isBlank()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "task/prompt is required");
    }
    String query = firstText(args, "query", "symbol");
    String start = args.path("path").asText(".");
    ObjectNode out = JsonSupport.JSON.createObjectNode();
    out.put("task", task);
    out.put("mode", "bounded_repo_reconnaissance");
    ArrayNode matches = out.putArray("matches");
    try {
      Path root = PathSafety.safeResolve(ctx.workspaceRoot(), start);
      try (var stream = Files.walk(root, 6)) {
        for (Path file : stream.filter(Files::isRegularFile).limit(300).toList()) {
          String rel = ctx.workspaceRoot().relativize(file).toString();
          if (rel.contains(".git/") || rel.contains("node_modules/") || rel.contains("target/"))
            continue;
          if (query == null || query.isBlank()) {
            matches.add(rel);
          } else {
            String text;
            try {
              text = Files.readString(file);
            } catch (Exception ignored) {
              continue;
            }
            if (text.contains(query)) matches.add(rel);
          }
          if (matches.size() >= 40) break;
        }
      }
      return ToolResult.ok(out.toString());
    } catch (SecurityException se) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
    } catch (IOException ioe) {
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    }
  }

  private static String firstText(JsonNode args, String... names) {
    for (String name : names) {
      if (args.hasNonNull(name)) return args.get(name).asText();
    }
    return null;
  }
}

final class McpClientFallbackTool implements Tool {
  private final String name;

  McpClientFallbackTool(String name) {
    this.name = name;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String description() {
    return "Calls an allowlisted MCP stdio server in server mode; local-hands clients may execute"
        + " local stdio MCP calls.";
  }

  @Override
  public String inputSchema() {
    return """
{"type":"object","properties":{"command":{"type":"string"},"method":{"type":"string"},"params":{"type":"object"}},"additionalProperties":true}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String command = firstText(args, "command");
    String method = firstText(args, "method");
    if (method == null || method.isBlank()) method = "tools/list";
    if (command != null && !command.isBlank()) {
      if (!isAllowed(command)) {
        return ToolResult.err(
            ToolResult.ErrorKind.FORBIDDEN,
            "Backend MCP stdio command is not allowlisted. Set EXECUTESPEC_MCP_ALLOWLIST to an"
                + " exact command or trusted prefix.");
      }
      try {
        return ToolResult.ok(callStdio(command, method, args.path("params")));
      } catch (IOException ioe) {
        return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return ToolResult.err(
            ToolResult.ErrorKind.IO_ERROR, "Interrupted while calling MCP server");
      }
    }
    ObjectNode out = JsonSupport.JSON.createObjectNode();
    out.put("mode", "server_fallback");
    out.put("method", method);
    out.put(
        "note",
        "No MCP stdio command was provided. Backend command execution is available only for"
            + " EXECUTESPEC_MCP_ALLOWLIST entries.");
    out.set("servers", JsonSupport.JSON.createArrayNode());
    return ToolResult.ok(out.toString());
  }

  private static boolean isAllowed(String command) {
    String raw =
        System.getenv()
            .getOrDefault(
                "EXECUTESPEC_MCP_ALLOWLIST", System.getProperty("executespec.mcp.allowlist", ""));
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .anyMatch(command::startsWith);
  }

  private static String firstText(JsonNode args, String name) {
    return args.hasNonNull(name) ? args.get(name).asText() : null;
  }

  private static String callStdio(String command, String method, JsonNode params)
      throws IOException, InterruptedException {
    Process process = new ProcessBuilder("/bin/sh", "-lc", command).start();
    String initialize =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"executespec-backend\",\"version\":\"1\"}}}";
    String initialized =
        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}";
    String call =
        JsonSupport.JSON
            .createObjectNode()
            .put("jsonrpc", "2.0")
            .put("id", 2)
            .put("method", method)
            .set(
                "params",
                params != null && params.isObject() ? params : JsonSupport.JSON.createObjectNode())
            .toString();
    process
        .getOutputStream()
        .write(
            (frame(initialize) + frame(initialized) + frame(call))
                .getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().close();
    boolean done = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
    if (!done) {
      process.destroyForcibly();
      throw new IOException("mcp_client timed out after 10000ms");
    }
    String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    ObjectNode out = JsonSupport.JSON.createObjectNode();
    out.put("mode", "server_allowlisted_stdio");
    out.put("method", method);
    out.put("exitCode", process.exitValue());
    out.put("stderr", stderr);
    out.set("response", parseResponse(stdout));
    return out.toString();
  }

  private static String frame(String json) {
    return "Content-Length: " + json.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + json;
  }

  private static JsonNode parseResponse(String stdout) throws IOException {
    Pattern p = Pattern.compile("(?i)Content-Length:\\s*(\\d+)\\s*\\r?\\n\\r?\\n");
    java.util.regex.Matcher m = p.matcher(stdout);
    JsonNode fallback = NullNode.getInstance();
    while (m.find()) {
      int length = Integer.parseInt(m.group(1));
      int start = m.end();
      int end = Math.min(stdout.length(), start + length);
      JsonNode node = JsonSupport.JSON.readTree(stdout.substring(start, end));
      fallback = node;
      if (node.path("id").asInt(-1) == 2) return node;
    }
    return fallback;
  }
}

final class UnavailableProtocolTool implements Tool {
  private final String name;
  private final String reason;

  UnavailableProtocolTool(String name, String reason) {
    this.name = name;
    this.reason = reason;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String description() {
    return reason;
  }

  @Override
  public String inputSchema() {
    return """
{"type":"object","properties":{},"additionalProperties":true}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    return ToolResult.err(ToolResult.ErrorKind.UNAVAILABLE, reason);
  }
}

final class JsonSupport {
  static final ObjectMapper JSON = new ObjectMapper();

  private JsonSupport() {}

  static String jsonShell(String stdout, String stderr, int exitCode) {
    ObjectNode node = JSON.createObjectNode();
    node.put("stdout", stdout == null ? "" : stdout);
    node.put("stderr", stderr == null ? "" : stderr);
    node.put("exit_code", exitCode);
    return node.toString();
  }
}
