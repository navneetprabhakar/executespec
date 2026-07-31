package com.navneet.executespec.memory.chattools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.rag.CodeIndexWorkspaceMaterializer;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Provider-free project-agent view of the canonical Spring HTTP surface. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectHttpSurfaceReadTool {

  private final ProjectRepoLinkRepository links;
  private final ProjectRepoRepository repositories;
  private final CodeIndexWorkspaceMaterializer materializer;
  private final SpringHttpSurfaceExtractor extractor;
  private final ObjectMapper json;

  public ToolCallback bind(ProjectChatTools.ToolContext context) {
    ToolDefinition definition =
        DefaultToolDefinition.builder()
            .name("list_public_http_surface")
            .description(
                "List the deterministic public Spring HTTP surface of a linked repository."
                    + " Combines server.servlet.context-path, class-level mappings, and method"
                    + " mappings into exact method/path/source rows. Prefer this over manually"
                    + " inferring public endpoints from controller annotations. Read-only.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "repoFullName": {"type": "string", "description": "owner/repo; required only when multiple repositories are linked."}
  }
}
""")
            .build();
    return new Callback(definition, context);
  }

  private ObjectNode read(ProjectChatTools.ToolContext context, JsonNode args) {
    Selection selection = select(context.projectId(), text(args, "repoFullName"));
    if (selection.error() != null) return selection.error();
    ProjectRepo repository = selection.repository();
    Path root;
    try {
      Credential credential = credential(context, repository);
      root =
          materializer.materialize(
              context.projectId(), repository, credential.username(), credential.secret());
    } catch (CodeIndexWorkspaceMaterializer.MaterializationException failure) {
      ObjectNode out = error("SOURCE_UNAVAILABLE", "repository source is unavailable");
      out.put("sourceCode", failure.code().name());
      return out;
    }

    try {
      SpringHttpSurfaceExtractor.SurfaceSnapshot surface = extractor.extract(root);
      ObjectNode out = json.createObjectNode();
      out.put("ok", true);
      out.put("repo", repository.getGithubRepoFullName());
      out.put("framework", "SPRING_MVC");
      out.put("schemaVersion", surface.schemaVersion());
      out.put("digest", surface.digest());
      var routes = out.putArray("routes");
      surface
          .routes()
          .forEach(
              route -> {
                ObjectNode row = routes.addObject();
                row.put("method", route.method());
                row.put("path", route.path());
                row.put("source", route.source());
              });
      return out;
    } catch (SpringHttpSurfaceExtractor.SurfaceExtractionException failure) {
      log.warn(
          "Project HTTP surface extraction failed repo={} errorClass={}",
          repository.getGithubRepoFullName(),
          failure.getClass().getSimpleName());
      return error("SURFACE_UNAVAILABLE", failure.getMessage());
    }
  }

  private Selection select(String projectId, String requestedFullName) {
    List<ProjectRepo> linked = new ArrayList<>();
    for (ProjectRepoLink link : links.findByProjectId(projectId)) {
      repositories.findById(link.getRepoId()).ifPresent(linked::add);
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
      return selected == null
          ? Selection.error(
              error("REPOSITORY_NOT_LINKED", "requested repository is not linked to this project"))
          : Selection.success(selected);
    }
    return linked.size() == 1
        ? Selection.success(linked.get(0))
        : Selection.error(
            error(
                "REPOSITORY_REQUIRED",
                "repoFullName is required when the project links multiple repositories"));
  }

  private static Credential credential(
      ProjectChatTools.ToolContext context, ProjectRepo repository) {
    boolean bitbucket = "BITBUCKET".equalsIgnoreCase(repository.getScmProvider());
    AppUser caller = context.principal() == null ? null : context.principal().getUser();
    return bitbucket
        ? new Credential(
            caller == null ? null : caller.getBitbucketUsername(),
            caller == null ? null : caller.getBitbucketAppPassword())
        : new Credential(null, context.callerPat());
  }

  private static String text(JsonNode args, String name) {
    if (args == null || !args.hasNonNull(name) || !args.get(name).isTextual()) return null;
    String value = args.get(name).asText().trim();
    return value.isEmpty() ? null : value;
  }

  private ObjectNode error(String code, String message) {
    ObjectNode out = json.createObjectNode();
    out.put("ok", false);
    out.put("code", code);
    out.put("message", message);
    return out;
  }

  private final class Callback implements ToolCallback {
    private final ToolDefinition definition;
    private final ProjectChatTools.ToolContext context;

    private Callback(ToolDefinition definition, ProjectChatTools.ToolContext context) {
      this.definition = definition;
      this.context = context;
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return definition;
    }

    @Override
    public String call(String input) {
      try {
        JsonNode args =
            input == null || input.isBlank() ? json.createObjectNode() : json.readTree(input);
        return read(context, args).toString();
      } catch (Exception failure) {
        log.warn(
            "Project HTTP surface tool failed errorClass={}", failure.getClass().getSimpleName());
        return error("SURFACE_READ_FAILED", "public HTTP surface could not be read").toString();
      }
    }
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
}
