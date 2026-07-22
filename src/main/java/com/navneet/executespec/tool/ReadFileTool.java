package com.navneet.executespec.tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.workspace.ProjectWorkspaceService;
import com.navneet.executespec.security.GeneratedSecretScanner;
import com.navneet.executespec.security.SensitiveWorkspacePathPolicy;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 readFile tool. Reads file contents with optional line-range slicing.
 *
 * <p>Improvements over V1: explicit line-range support exposed in the JSON schema (V1 had a private
 * {@code readFileRange} but didn't expose it to the LLM). Reduces tokens significantly when the
 * model only needs to inspect part of a large file.
 *
 * <p><strong>RL-2026-06 (truncation+exact-match fix):</strong> the old behaviour kept a 5 KB
 * head+tail and dropped the <em>middle</em> of the file, inserting a {@code ...[N chars
 * omitted]...} marker that does not exist in the file. That made {@link EditFileTool} (which
 * matches an exact {@code oldStr}) impossible to satisfy for any region the model never saw. The
 * cap is now far larger and truncation is <em>head-first at a line boundary</em> with the notice
 * appended at the <em>end</em>, so every byte returned is contiguous and byte-exact from line 1 —
 * whatever the model reads, it can {@code editFile} against. Large files page in via {@code
 * startLine}/{@code endLine} (range reads remain untruncated), so the rest of the file is always
 * reachable byte-exact.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReadFileTool implements Tool {

  /**
   * Char ceiling for a single full-file read before head-first truncation kicks in. Sized generous
   * (~60 KB ≈ most real source files in full) so the truncation+exact-match failure mode is rare;
   * when it does fire we keep a contiguous head and tell the model to range-read the rest.
   */
  static final int MAX_FULL_READ_CHARS = 60_000;

  /** Companion line ceiling — whichever cap is hit first bounds a full read. */
  static final int MAX_FULL_READ_LINES = 2_000;

  private final ProjectWorkspaceService projectWorkspace;
  private final PipelineRunRepository pipelineRunRepo;

  /**
   * Read-only DB fallback for architecture documents. Keep this dependency at the repository
   * boundary: injecting {@code ProjectArchitectureService} here creates a bean cycle through the
   * canonical {@code LlmRouter} (router -> tools -> readFile -> architecture -> project brief ->
   * router).
   */
  private final ProjectArchitectureDocRepository projectArchRepo;

  private final ProjectCodeMapRepository codeMapRepo;
  private final ProjectRepoLinkRepository repoLinkRepo;
  private final ProjectRepoRepository repoRepo;

  /**
   * Read-before-write ledger (RL-2026-05-051, harness adoption #1). Every run-workspace read is
   * recorded so {@link EditFileTool} can enforce read-before-edit + staleness.
   */
  private final ReadFileStateStore readState;

  private final GeneratedSecretScanner secretScanner;

  @Override
  public String name() {
    return "readFile";
  }

  @Override
  public String description() {
    return "Reads a file's contents. Supports optional line-range slicing — provide startLine and"
        + " endLine (1-indexed, inclusive) to read a portion of a large file without"
        + " consuming tokens on irrelevant content. Legacy offset (0-indexed) + limit are"
        + " accepted as aliases; startLine/endLine take precedence.";
  }

  @Override
  public String inputSchema() {
    return """
           {
             "type": "object",
             "properties": {
               "path": {
                 "type": "string",
                 "description": "Workspace-relative path to the file"
               },
               "startLine": {
                 "type": "integer",
                 "description": "Optional 1-indexed first line to include",
                 "minimum": 1
               },
               "endLine": {
                 "type": "integer",
                 "description": "Optional 1-indexed last line to include (inclusive)",
                 "minimum": 1
               },
               "offset": {
                 "type": "integer",
                 "description": "Legacy alias: skip this many lines (0-indexed)",
                 "minimum": 0
               },
               "limit": {
                 "type": "integer",
                 "description": "Legacy alias: maximum lines to return with offset",
                 "minimum": 1
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
    String policyPath = path.startsWith("project:") ? path.substring("project:".length()) : path;
    if (SensitiveWorkspacePathPolicy.isSensitive(policyPath)) {
      return ToolResult.err(
          ToolResult.ErrorKind.FORBIDDEN,
          "credential-like files cannot be read into model context: " + policyPath);
    }
    Integer start = args.hasNonNull("startLine") ? args.get("startLine").asInt() : null;
    Integer end = args.hasNonNull("endLine") ? args.get("endLine").asInt() : null;
    if (start == null && end == null && (args.hasNonNull("offset") || args.hasNonNull("limit"))) {
      int offset = args.hasNonNull("offset") ? Math.max(0, args.get("offset").asInt()) : 0;
      int limit = args.hasNonNull("limit") ? Math.max(1, args.get("limit").asInt()) : 200;
      start = offset + 1;
      end = start + limit - 1;
    }
    if (start != null && end != null && start > end) {
      return ToolResult.err(
          ToolResult.ErrorKind.INVALID_ARGS, "startLine (" + start + ") > endLine (" + end + ")");
    }
    // project: prefix — read from the per-project workspace instead of the run workspace.
    if (path.startsWith("project:")) {
      String rel = path.substring("project:".length());
      String projectId =
          pipelineRunRepo.findById(ctx.runId()).map(r -> r.getProjectId()).orElse(null);
      if (projectId == null || projectId.isBlank()) {
        return ToolResult.err(
            ToolResult.ErrorKind.INVALID_ARGS, "project workspace not available for this run");
      }
      try {
        String content = projectWorkspace.readFile(projectId, rel).orElse(null);
        if (content == null) {
          // Disk cache miss — fall back to DB (VM churn safe).
          content = readFromDbFallback(projectId, rel);
        }
        if (content == null) {
          return ToolResult.err(ToolResult.ErrorKind.NOT_FOUND, "file not found: " + path);
        }
        if (start != null || end != null) {
          String[] lines = content.split("\\R", -1);
          int s = start == null ? 1 : Math.max(1, start);
          int e = end == null ? lines.length : Math.min(end, lines.length);
          if (s > lines.length) {
            return ToolResult.ok("(file has " + lines.length + " lines; range starts past EOF)");
          }
          return safeContentResult(
              String.join("\n", java.util.Arrays.copyOfRange(lines, s - 1, e)));
        }
        return safeContentResult(clampFullRead(content));
      } catch (IllegalArgumentException iae) {
        return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, "project file read was rejected");
      } catch (RuntimeException failure) {
        log.warn("project readFile failed errorClass={}", failure.getClass().getSimpleName());
        return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, "project file read failed");
      }
    }
    try {
      Path resolved = PathSafety.safeResolve(ctx.workspaceRoot(), path);
      if (!Files.exists(resolved)) {
        return ToolResult.err(ToolResult.ErrorKind.NOT_FOUND, "file not found: " + path);
      }
      if (Files.isDirectory(resolved)) {
        return ToolResult.err(
            ToolResult.ErrorKind.INVALID_ARGS, "path is a directory, not a file: " + path);
      }
      ToolResult result;
      if (start != null || end != null) {
        int s = start == null ? 1 : start;
        // Range reads are byte-exact and untruncated — this is how the model pages through a file
        // too large to return whole, and the slice it sees is exactly editable via editFile.
        result = safeContentResult(readRange(resolved, s, end == null ? Integer.MAX_VALUE : end));
      } else {
        result = safeContentResult(readFullCapped(resolved));
      }
      // RR-1024 — record the read (full file content hash) for editFile's read-before-write +
      // staleness gate ONLY when the content was actually returned to the model. A read the secret
      // scanner FORBID (or any error) must not authorize a later full-file edit/overwrite of a file
      // the worker never successfully observed.
      if (result instanceof ToolResult.Ok) {
        readState.recordReadFromDisk(ctx.runId(), resolved);
      }
      return result;
    } catch (NoSuchFileException nsfe) {
      return ToolResult.err(ToolResult.ErrorKind.NOT_FOUND, "file not found: " + path);
    } catch (SecurityException se) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, "file read was rejected");
    } catch (IOException ioe) {
      log.warn("readFile IO error errorClass={}", ioe.getClass().getSimpleName());
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, "file read failed");
    }
  }

  private ToolResult safeContentResult(String content) {
    var finding = secretScanner.scan(content);
    if (finding.isPresent()) {
      return ToolResult.err(
          ToolResult.ErrorKind.FORBIDDEN,
          "credential content cannot be read into model context ("
              + finding.get().category()
              + ")");
    }
    return ToolResult.ok(content);
  }

  /**
   * Head-first, line-boundary truncation for full-file reads. Unlike the old head+tail-with-a-hole
   * behaviour, every character returned is contiguous and byte-exact starting at line 1, so any
   * region the model sees can be matched exactly by {@link EditFileTool}. When a file is too large
   * to return whole, the notice is appended at the END (not spliced into the middle) and points the
   * model at {@code startLine}/{@code endLine} to page through the remainder byte-exact.
   */
  static String clampFullRead(String content) {
    if (content == null) return null;
    if (content.length() <= MAX_FULL_READ_CHARS && countLines(content) <= MAX_FULL_READ_LINES) {
      return content;
    }
    // Find the largest prefix that fits both caps, ending on a line boundary.
    int charLimit = Math.min(content.length(), MAX_FULL_READ_CHARS);
    int cut = content.lastIndexOf('\n', charLimit - 1);
    if (cut < 0) {
      cut = charLimit; // single huge line — hard cut at the char limit
    }
    // Enforce the line cap too.
    int lineCut = nthNewlineIndex(content, MAX_FULL_READ_LINES);
    if (lineCut >= 0 && lineCut < cut) {
      cut = lineCut;
    }
    String head = content.substring(0, cut);
    int shownLines = countLines(head);
    int totalLines = countLines(content);
    return head
        + "\n\n...[truncated: showing the first "
        + shownLines
        + " of "
        + totalLines
        + " lines ("
        + head.length()
        + " of "
        + content.length()
        + " chars). The content above is byte-exact and editable as-is; read the rest with"
        + " startLine/endLine.]...";
  }

  /**
   * Stream a disk-backed file so a multi-megabyte source file never has to be materialised just to
   * return the bounded full-read preview. The in-memory {@link #clampFullRead(String)} helper is
   * retained for project/DB-backed content and unit tests.
   */
  static String readFullCapped(Path path) throws IOException {
    StringBuilder prefix = new StringBuilder(MAX_FULL_READ_CHARS);
    long totalChars = 0;
    int newlines = 0;
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      char[] buffer = new char[8192];
      int read;
      while ((read = reader.read(buffer)) >= 0) {
        if (read == 0) continue;
        int remaining = MAX_FULL_READ_CHARS - prefix.length();
        if (remaining > 0) {
          prefix.append(buffer, 0, Math.min(remaining, read));
        }
        for (int i = 0; i < read; i++) if (buffer[i] == '\n') newlines++;
        totalChars += read;
      }
    }
    int totalLines = totalChars == 0 ? 0 : newlines + 1;
    if (totalChars <= MAX_FULL_READ_CHARS && totalLines <= MAX_FULL_READ_LINES) {
      return prefix.toString();
    }
    int cut = prefix.length();
    int lineCut = nthNewlineIndex(prefix.toString(), MAX_FULL_READ_LINES);
    if (lineCut >= 0 && lineCut < cut) cut = lineCut;
    if (cut == MAX_FULL_READ_CHARS) {
      int boundary = prefix.lastIndexOf("\n", cut - 1);
      if (boundary >= 0) cut = boundary;
    }
    String head = prefix.substring(0, cut);
    int shownLines = countLines(head);
    return head
        + "\n\n...[truncated: showing the first "
        + shownLines
        + " of "
        + totalLines
        + " lines ("
        + head.length()
        + " of "
        + totalChars
        + " chars). The content above is byte-exact and editable as-is; read the rest with"
        + " startLine/endLine.]...";
  }

  /** Stream only the requested line window; do not read the complete file into a list first. */
  static String readRange(Path path, int startLine, int endLine) throws IOException {
    int start = Math.max(1, startLine);
    int end = Math.max(start, endLine);
    StringBuilder out = new StringBuilder();
    int lineNumber = 0;
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (lineNumber < start) continue;
        if (lineNumber > end) break;
        if (out.length() > 0) out.append('\n');
        out.append(line);
      }
    }
    if (lineNumber < start) {
      return "(file has " + lineNumber + " lines; range starts past EOF)";
    }
    return out.toString();
  }

  /** Index of the {@code n}-th newline (1-indexed), or -1 if the file has fewer than {@code n}. */
  private static int nthNewlineIndex(String s, int n) {
    int idx = -1;
    for (int i = 0; i < n; i++) {
      idx = s.indexOf('\n', idx + 1);
      if (idx < 0) return -1;
    }
    return idx;
  }

  private static int countLines(String s) {
    if (s.isEmpty()) return 0;
    int lines = 1;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\n') lines++;
    }
    return lines;
  }

  /**
   * DB-authoritative fallback for project: reads. The disk cache may be missing after a VM rotation
   * or fresh deploy, but architecture docs + codemaps are always persisted to Postgres — so reads
   * survive disk loss.
   */
  private String readFromDbFallback(String projectId, String rel) {
    if (rel == null) return null;
    if (rel.equals("architecture.md")) {
      return projectArchRepo
          .findFirstByProjectIdOrderByVersionDesc(projectId)
          .map(ProjectArchitectureDoc::getContent)
          .orElse(null);
    }
    if (rel.startsWith("codemaps/") && rel.endsWith(".md")) {
      String shortName = rel.substring("codemaps/".length(), rel.length() - ".md".length());
      for (var link : repoLinkRepo.findByProjectId(projectId)) {
        ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
        if (repo == null) continue;
        String full = repo.getGithubRepoFullName();
        String candidate = full.contains("/") ? full.substring(full.indexOf('/') + 1) : full;
        candidate = candidate.replaceAll("[^A-Za-z0-9._-]", "-");
        if (candidate.equals(shortName)) {
          return codeMapRepo
              .findFirstByProjectIdAndRepoIdOrderByVersionDesc(projectId, repo.getId())
              .map(ProjectCodeMap::getContent)
              .orElse(null);
        }
      }
    }
    // summary.md has no DB-backing row — regenerate via /analyse if missing on disk.
    return null;
  }
}
