package com.navneet.executespec.v2.tool;

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
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReadFileTool implements Tool {

  /** Truncation cap applied to full-file reads. Range reads are not truncated. */
  static final int MAX_RESULT_CHARS = 5_000;

  private final ProjectWorkspaceService projectWorkspace;
  private final PipelineRunRepository pipelineRunRepo;
  private final ProjectArchitectureService projectArchService;
  private final ProjectCodeMapRepository codeMapRepo;
  private final ProjectRepoLinkRepository repoLinkRepo;
  private final ProjectRepoRepository repoRepo;

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
        if (content.length() > MAX_RESULT_CHARS) {
          int tail = Math.min(1_500, MAX_RESULT_CHARS / 4);
          int head = MAX_RESULT_CHARS - tail;
          int omitted = content.length() - MAX_RESULT_CHARS;
          content =
              content.substring(0, head)
                  + "\n...["
                  + omitted
                  + " chars omitted]...\n"
                  + content.substring(content.length() - tail);
        }
        return ToolResult.ok(content);
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
      if (start != null || end != null) {
        List<String> lines = Files.readAllLines(resolved);
        int s = start == null ? 1 : start;
        int e = end == null ? lines.size() : Math.min(end, lines.size());
        if (s > lines.size()) {
          return ToolResult.ok("(file has " + lines.size() + " lines; range starts past EOF)");
        }
        String slice = String.join("\n", lines.subList(s - 1, e));
        return ToolResult.ok(slice);
      }
      String content = Files.readString(resolved);
      if (content.length() > MAX_RESULT_CHARS) {
        int tail = Math.min(1_500, MAX_RESULT_CHARS / 4);
        int head = MAX_RESULT_CHARS - tail;
        int omitted = content.length() - MAX_RESULT_CHARS;
        content =
            content.substring(0, head)
                + "\n...["
                + omitted
                + " chars omitted — use startLine/endLine for full content]...\n"
                + content.substring(content.length() - tail);
      }
      return ToolResult.ok(content);
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
