package com.navneet.executespec.memory.chattools;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.rag.CodeIndexWorkspaceMaterializer;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.security.GeneratedSecretScanner;
import com.navneet.executespec.security.SafeWorkspacePath;
import com.navneet.executespec.security.SensitiveWorkspacePathPolicy;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pure read-only Project Agent tool for expanding a lexical source hit into bounded line context.
 * Repository selection remains project-linked, source materialization remains revision-pinned, and
 * every filesystem read is relative, symlink-free, credential-filtered, and size bounded.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectRepositorySourceReadTools {

  static final int MAX_RANGE_LINES = 200;
  // Leave headroom beneath ProjectChatTools' 24k serialized-envelope ceiling for metadata,
  // untrusted-data labels, and sanitizer output. The outer cap must never cut content behind an
  // already-advertised nextStartLine.
  static final int MAX_CONTENT_CHARS = 20_000;
  static final long MAX_SOURCE_FILE_BYTES = 5L * 1024 * 1024;

  private final ProjectRepoLinkRepository linkRepository;
  private final ProjectRepoRepository repoRepository;
  private final CodeIndexWorkspaceMaterializer workspaceMaterializer;
  private final GeneratedSecretScanner secretScanner;
  private final ObjectMapper json;

  public ToolCallback bind(ProjectChatTools.ToolContext context) {
    ToolDefinition definition =
        DefaultToolDefinition.builder()
            .name("read_repo_file_range")
            .description(
                "Read a bounded 1-indexed inclusive line range from a linked repository's actual"
                    + " revision-pinned source. Use after search_repo_files to expand a file:line"
                    + " hit into its method, signature, imports, or nearby call-site context. Both"
                    + " startLine and endLine are required; at most 200 lines are returned. If the"
                    + " project links multiple repos, pass repoFullName. Read-only.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "filePath": {"type": "string", "description": "Repository-relative source path returned by search_repo_files."},
    "startLine": {"type": "integer", "minimum": 1, "description": "First line to read (1-indexed, inclusive)."},
    "endLine": {"type": "integer", "minimum": 1, "description": "Last line to read (1-indexed, inclusive; max 200 lines)."},
    "repoFullName": {"type": "string", "description": "owner/repo to read. Required only when multiple repos are linked."}
  },
  "required": ["filePath", "startLine", "endLine"]
}
""")
            .build();
    return new Callback(definition, args -> read(context, args));
  }

  private ObjectNode read(ProjectChatTools.ToolContext context, JsonNode args) {
    String filePath = text(args, "filePath");
    if (filePath == null) return error("INVALID_ARGUMENT", "filePath is required");
    Integer startLine = integer(args, "startLine");
    Integer endLine = integer(args, "endLine");
    if (startLine == null || endLine == null) {
      return error("INVALID_ARGUMENT", "startLine and endLine are required integers");
    }
    if (startLine < 1 || endLine < startLine) {
      return error("INVALID_RANGE", "line range must satisfy 1 <= startLine <= endLine");
    }
    if ((long) endLine - startLine + 1 > MAX_RANGE_LINES) {
      return error("RANGE_TOO_LARGE", "a source read may include at most 200 lines");
    }

    String pathFailure = validateRelativeSourcePath(filePath);
    if (pathFailure != null) return error(pathFailure, "repository source path was rejected");

    Selection selection = selectRepository(context.projectId(), text(args, "repoFullName"));
    if (selection.error() != null) return selection.error();
    ProjectRepo repository = selection.repository();

    Path root;
    try {
      Credential credential = credential(context, repository);
      root =
          workspaceMaterializer.materialize(
              context.projectId(), repository, credential.username(), credential.secret());
    } catch (CodeIndexWorkspaceMaterializer.MaterializationException failure) {
      log.warn(
          "Project source range materialization failed repo={} code={}",
          repository.getGithubRepoFullName(),
          failure.code());
      ObjectNode result =
          error("SOURCE_UNAVAILABLE", "repository source is unavailable for bounded reading");
      result.put("sourceCode", failure.code().name());
      return result;
    }

    try {
      Path source = SafeWorkspacePath.resolveWithin(root, filePath);
      if (!Files.isRegularFile(source, NOFOLLOW_LINKS)) {
        return error("FILE_NOT_FOUND", "repository source file was not found");
      }
      if (Files.size(source) > MAX_SOURCE_FILE_BYTES) {
        return error("FILE_TOO_LARGE", "repository source file exceeds the bounded read limit");
      }
      RangeRead range = readRange(source, startLine, endLine);
      if (range.errorCode() != null) {
        return error(range.errorCode(), range.errorMessage());
      }
      var secret = secretScanner.scan(range.content());
      if (secret.isPresent()) {
        return error(
            "SOURCE_CONTENT_SENSITIVE",
            "credential content cannot be returned from repository source");
      }

      ObjectNode result = json.createObjectNode();
      result.put("ok", true);
      result.put("repo", repository.getGithubRepoFullName());
      result.put("filePath", filePath);
      result.put("requestedStartLine", startLine);
      result.put("requestedEndLine", endLine);
      if (range.returnedEndLine() != null) {
        result.put("returnedStartLine", startLine);
        result.put("returnedEndLine", range.returnedEndLine());
      } else {
        result.putNull("returnedStartLine");
        result.putNull("returnedEndLine");
      }
      result.put("linesReturned", range.linesReturned());
      result.put("content", range.content());
      result.put("truncated", range.truncated());
      result.put("hasMore", range.hasMore());
      if (range.nextStartLine() != null) result.put("nextStartLine", range.nextStartLine());
      return result;
    } catch (SecurityException rejected) {
      return error("PATH_REJECTED", "repository source path was rejected");
    } catch (IOException failure) {
      log.warn(
          "Project source range read failed repo={} errorClass={}",
          repository.getGithubRepoFullName(),
          failure.getClass().getSimpleName());
      return error("SOURCE_READ_FAILED", "repository source could not be read");
    }
  }

  private RangeRead readRange(Path source, int startLine, int endLine) throws IOException {
    StringBuilder content = new StringBuilder();
    int lineNumber = 0;
    int linesReturned = 0;
    Integer returnedEndLine = null;
    boolean truncated = false;
    boolean hasMore = false;
    Integer nextStartLine = null;

    try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (lineNumber < startLine) continue;
        if (lineNumber > endLine) {
          hasMore = true;
          nextStartLine = lineNumber;
          break;
        }
        if (line.indexOf('\0') >= 0) {
          return RangeRead.error("BINARY_FILE", "binary files cannot enter model context");
        }
        int addition = line.length() + (content.isEmpty() ? 0 : 1);
        if (addition > MAX_CONTENT_CHARS && content.isEmpty()) {
          return RangeRead.error(
              "SOURCE_LINE_TOO_LARGE", "a single source line exceeds the result limit");
        }
        if (content.length() + addition > MAX_CONTENT_CHARS) {
          truncated = true;
          hasMore = true;
          nextStartLine = lineNumber;
          break;
        }
        if (!content.isEmpty()) content.append('\n');
        content.append(line);
        linesReturned++;
        returnedEndLine = lineNumber;
      }
    }
    return new RangeRead(
        content.toString(),
        returnedEndLine,
        linesReturned,
        truncated,
        hasMore,
        nextStartLine,
        null,
        null);
  }

  private Selection selectRepository(String projectId, String requestedFullName) {
    List<ProjectRepo> linked = new ArrayList<>();
    for (ProjectRepoLink link : linkRepository.findByProjectId(projectId)) {
      repoRepository.findById(link.getRepoId()).ifPresent(linked::add);
    }
    if (linked.isEmpty()) {
      return Selection.error(
          error("NO_REPOSITORIES", "no repositories are linked to this project"));
    }
    if (requestedFullName != null) {
      ProjectRepo selected =
          linked.stream()
              .filter(repo -> requestedFullName.equalsIgnoreCase(repo.getGithubRepoFullName()))
              .findFirst()
              .orElse(null);
      if (selected == null) {
        return Selection.error(
            error("REPOSITORY_NOT_LINKED", "requested repository is not linked to this project"));
      }
      return Selection.success(selected);
    }
    if (linked.size() != 1) {
      return Selection.error(
          error(
              "REPOSITORY_REQUIRED",
              "repoFullName is required when the project links multiple repositories"));
    }
    return Selection.success(linked.get(0));
  }

  private static Credential credential(
      ProjectChatTools.ToolContext context, ProjectRepo repository) {
    boolean bitbucket = "BITBUCKET".equalsIgnoreCase(repository.getScmProvider());
    AppUser caller = context.principal() == null ? null : context.principal().getUser();
    if (bitbucket) {
      return new Credential(
          caller == null ? null : caller.getBitbucketUsername(),
          caller == null ? null : caller.getBitbucketAppPassword());
    }
    return new Credential(null, context.callerPat());
  }

  private static String validateRelativeSourcePath(String filePath) {
    try {
      SafeWorkspacePath.requireSafeRelativePosixPath(filePath);
    } catch (SecurityException rejected) {
      return "PATH_REJECTED";
    }
    if (filePath.equals(".git") || filePath.startsWith(".git/")) return "GIT_METADATA_REJECTED";
    if (SensitiveWorkspacePathPolicy.isSensitive(filePath)) return "SENSITIVE_PATH";
    return null;
  }

  private static String text(JsonNode args, String name) {
    if (args == null || !args.hasNonNull(name) || !args.get(name).isTextual()) return null;
    String value = args.get(name).asText().trim();
    return value.isEmpty() ? null : value;
  }

  private static Integer integer(JsonNode args, String name) {
    return args != null && args.hasNonNull(name) && args.get(name).isIntegralNumber()
        ? args.get(name).intValue()
        : null;
  }

  private ObjectNode error(String code, String message) {
    ObjectNode result = json.createObjectNode();
    result.put("ok", false);
    result.put("code", code);
    result.put("message", message);
    return result;
  }

  private final class Callback implements ToolCallback {
    private final ToolDefinition definition;
    private final ToolBody body;

    private Callback(ToolDefinition definition, ToolBody body) {
      this.definition = definition;
      this.body = body;
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return definition;
    }

    @Override
    public String call(String input) {
      JsonNode args;
      try {
        args = input == null || input.isBlank() ? json.createObjectNode() : json.readTree(input);
      } catch (Exception invalidJson) {
        return error("INVALID_JSON", "tool input must be valid JSON").toString();
      }
      try {
        return body.read(args).toString();
      } catch (RuntimeException failure) {
        log.warn(
            "Project source read tool failed errorClass={}", failure.getClass().getSimpleName());
        return error("SOURCE_READ_FAILED", "repository source could not be read").toString();
      }
    }
  }

  @FunctionalInterface
  private interface ToolBody {
    ObjectNode read(JsonNode args);
  }

  private record Credential(String username, String secret) {}

  private record Selection(ProjectRepo repository, ObjectNode error) {
    private static Selection success(ProjectRepo repository) {
      return new Selection(repository, null);
    }

    private static Selection error(ObjectNode error) {
      return new Selection(null, error);
    }
  }

  private record RangeRead(
      String content,
      Integer returnedEndLine,
      int linesReturned,
      boolean truncated,
      boolean hasMore,
      Integer nextStartLine,
      String errorCode,
      String errorMessage) {
    private static RangeRead error(String code, String message) {
      return new RangeRead("", null, 0, false, false, null, code, message);
    }
  }
}
