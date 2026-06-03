package com.navneet.executespec.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.workspace.ProjectWorkspaceService;
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
  private final ProjectArchitectureService projectArchService;
  private final ProjectCodeMapRepository codeMapRepo;
  private final ProjectRepoLinkRepository repoLinkRepo;
  private final ProjectRepoRepository repoRepo;

  /**
   * Read-before-write ledger (RL-2026-05-051, harness adoption #1). Every run-workspace read is
   * recorded so {@link EditFileTool} can enforce read-before-edit + staleness.
   */
  private final ReadFileStateStore readState;

  @Override
  public String name() {
    return "readFile";
  }

  @Override
  public String description() {
    return "Reads a file's contents. Supports optional line-range slicing — provide startLine and"
        + " endLine (1-indexed, inclusive) to read a portion of a large file without"
        + " consuming tokens on irrelevant content.";
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
    Integer start = args.hasNonNull("startLine") ? args.get("startLine").asInt() : null;
    Integer end = args.hasNonNull("endLine") ? args.get("endLine").asInt() : null;
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
        return ToolResult.ok(clampFullRead(content));
      } catch (IllegalArgumentException iae) {
        return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, iae.getMessage());
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
      // Record the read (full file content hash) so editFile can enforce read-before-write +
      // staleness. Covers both range + full reads — any read grounds the worker on this file.
      readState.recordReadFromDisk(ctx.runId(), resolved);
      if (start != null || end != null) {
        List<String> lines = Files.readAllLines(resolved);
        int s = start == null ? 1 : start;
        int e = end == null ? lines.size() : Math.min(end, lines.size());
        if (s > lines.size()) {
          return ToolResult.ok("(file has " + lines.size() + " lines; range starts past EOF)");
        }
        // Range reads are byte-exact and untruncated — this is how the model pages through a file
        // too large to return whole, and the slice it sees is exactly editable via editFile.
        String slice = String.join("\n", lines.subList(s - 1, e));
        return ToolResult.ok(slice);
      }
      String content = Files.readString(resolved);
      return ToolResult.ok(clampFullRead(content));
    } catch (NoSuchFileException nsfe) {
      return ToolResult.err(ToolResult.ErrorKind.NOT_FOUND, "file not found: " + path);
    } catch (SecurityException se) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
    } catch (IOException ioe) {
      log.warn("readFile IO error path={}", path, ioe);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    }
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
      return projectArchService
          .latest(projectId)
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
