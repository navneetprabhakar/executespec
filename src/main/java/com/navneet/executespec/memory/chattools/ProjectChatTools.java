package com.navneet.executespec.memory.chattools;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.ProjectDocGeneratorService;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;
import com.navneet.executespec.memory.ProjectMemoryProvider;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Project-chat tool factory (V169 companion, 2026-05-02). Builds a list of {@link ToolCallback}s
 * that the LLM in {@link com.navneet.executespec.memory.ProjectChatService} can call when the user
 * asks the chat to act on the project — generate / regenerate architecture, generate explanation /
 * setup docs, list linked repos, recent runs, recent PRs, or read project memory.
 *
 * <p><b>Scope.</b> Tools exposed here only operate on PROJECT-level state. Run-level mutations
 * (start a run, retry a task, approve a plan) deliberately stay out of chat — those have their own
 * gates and approval flows. Settings (PAT, AI keys, billing) likewise stay out per the user's
 * direction (2026-05-02): "all project-related actions, apart from settings".
 *
 * <p><b>Latency.</b> Some tools (notably {@code regenerate_architecture} and {@code
 * generate_project_doc}) trigger the same long-running LLM pipelines the dashboard buttons call —
 * typically 30-90s. The chat call holds the HTTP request open for the full duration; the frontend
 * pairs that with the existing {@code spring.mvc.async.request-timeout: 900000}.
 *
 * <p><b>Errors.</b> Every tool returns a JSON envelope ({@code {"ok": true|false, ...}}). The LLM
 * sees the envelope as the tool result and narrates accordingly. We never throw out of {@code
 * call()} — that would surface as a 500 to the chat caller and abort the entire turn.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectChatTools {

  private final ProjectRepository projectRepo;
  private final ProjectArchitectureService architectureService;
  private final ProjectDocGeneratorService docGeneratorService;
  private final ProjectRepoRepository repoRepo;
  private final ProjectRepoLinkRepository linkRepo;
  private final PipelineRunRepository pipelineRunRepo;
  private final PullRequestRecordRepository prRepo;
  private final ProjectMemoryProvider memoryProvider;
  private final ObjectMapper json;

  /** Caller-scoped state every tool needs. {@code callerPat} may be null for public-repo orgs. */
  public record ToolContext(String projectId, String userId, String callerPat) {}

  /**
   * Build the per-turn tool list. Pass the result to {@code LlmRequest.toolsOverride(...)} so
   * Spring AI exposes the tools to the model and dispatches calls back into this class.
   */
  public List<ToolCallback> bind(ToolContext ctx) {
    return List.of(
        regenerateArchitecture(ctx),
        generateProjectDoc(ctx),
        listRepos(ctx),
        listRecentRuns(ctx),
        listPullRequests(ctx),
        getProjectMemory(ctx),
        getLatestArchitecture(ctx));
  }

  // ── Tool 1: regenerate architecture ───────────────────────────────────────

  private ToolCallback regenerateArchitecture(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("regenerate_architecture")
            .description(
                "Re-run the 3-step architecture pipeline for this project: per-repo codemap, "
                    + "per-repo HLD/LLD, then a combined project architecture doc. Opens a PR per "
                    + "linked repo with the updated `.executespec/arch/codemap.md`. Slow — "
                    + "typically 30-90 seconds. Use when the user asks to refresh / regenerate / "
                    + "update / re-analyse the project's architecture.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "branchOverride": {
      "type": "string",
      "description": "Optional branch to read source from. Defaults to each repo's default branch."
    }
  }
}
""")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          String branchOverride =
              args.has("branchOverride") && !args.get("branchOverride").isNull()
                  ? args.get("branchOverride").asText(null)
                  : null;
          ProjectArchitectureDoc doc =
              architectureService.generate(ctx.projectId(), branchOverride, ctx.callerPat());
          ObjectNode out = json.createObjectNode();
          out.put("ok", true);
          out.put("version", doc.getVersion());
          out.put(
              "summary",
              "Architecture regenerated as v"
                  + doc.getVersion()
                  + ". A PR has been opened on every linked repo with the refreshed codemap. "
                  + "It also appears in the project dashboard's Pull requests tab.");
          return out;
        });
  }

  // ── Tool 2: generate project doc ──────────────────────────────────────────

  private ToolCallback generateProjectDoc(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("generate_project_doc")
            .description(
                "Generate or refresh a user-facing project document. EXPLANATION = plain-English "
                    + "narrative for onboarding a new contributor (\"what is this project\"); "
                    + "SETUP = actionable local-setup README (clone, env vars, run commands). "
                    + "Synthesised from the latest project architecture doc — call "
                    + "regenerate_architecture first if the architecture is stale. Versioned: "
                    + "calling again creates v+1.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "docType": {
      "type": "string",
      "enum": ["EXPLANATION", "SETUP"],
      "description": "EXPLANATION for narrative onboarding doc; SETUP for runnable instructions."
    }
  },
  "required": ["docType"]
}
""")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          String docTypeStr =
              args.has("docType") && !args.get("docType").isNull()
                  ? args.get("docType").asText(null)
                  : null;
          if (docTypeStr == null) {
            return errorEnvelope("docType is required (EXPLANATION or SETUP)");
          }
          DocType docType;
          try {
            docType = DocType.valueOf(docTypeStr.toUpperCase());
          } catch (IllegalArgumentException ex) {
            return errorEnvelope(
                "Unknown docType '" + docTypeStr + "'. Accepted: EXPLANATION, SETUP.");
          }
          ProjectGeneratedDoc doc =
              docGeneratorService.generate(ctx.projectId(), docType, ctx.userId(), null);
          ObjectNode out = json.createObjectNode();
          out.put("ok", true);
          out.put("docType", doc.getDocType().name());
          out.put("version", doc.getVersion());
          out.put(
              "wordCount",
              doc.getContent() == null ? 0 : doc.getContent().trim().split("\\s+").length);
          out.put(
              "summary",
              "Generated "
                  + doc.getDocType().name()
                  + " doc v"
                  + doc.getVersion()
                  + ". Visible on the project dashboard's Generated Docs panel.");
          return out;
        });
  }

  // ── Tool 3: list repos ────────────────────────────────────────────────────

  private ToolCallback listRepos(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("list_repos")
            .description(
                "List every repository linked to this project — full name (owner/repo), default "
                    + "branch, last-synced SHA, and `.executespec/` folder status. Read-only.")
            .inputSchema("{ \"type\": \"object\", \"properties\": {} }")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          List<ProjectRepoLink> links = linkRepo.findByProjectId(ctx.projectId());
          ArrayNode arr = json.createArrayNode();
          for (ProjectRepoLink link : links) {
            Optional<ProjectRepo> r = repoRepo.findById(link.getRepoId());
            if (r.isEmpty()) continue;
            ProjectRepo repo = r.get();
            ObjectNode row = arr.addObject();
            row.put("repoId", repo.getId().toString());
            row.put("fullName", repo.getGithubRepoFullName());
            row.put("defaultBranch", repo.getDefaultBranch());
            row.put(
                "lastSyncedSha", repo.getLastSyncedSha() == null ? "" : repo.getLastSyncedSha());
            row.put(
                "executespecFolderStatus",
                repo.getExecutespecFolderStatus() == null
                    ? "MISSING"
                    : repo.getExecutespecFolderStatus());
          }
          ObjectNode out = json.createObjectNode();
          out.put("ok", true);
          out.put("count", arr.size());
          out.set("repos", arr);
          return out;
        });
  }

  // ── Tool 4: list recent runs ──────────────────────────────────────────────

  private ToolCallback listRecentRuns(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("list_recent_runs")
            .description(
                "List the most recent runs for this project — id, status, statusReason, plan "
                    + "strategy, verifiedScore, started/completed timestamps, and total cost. "
                    + "Read-only. Use to summarise project history or to find a specific run.")
            .inputSchema(
                """
                {
                  "type": "object",
                  "properties": {
                    "limit": {
                      "type": "integer",
                      "description": "Max rows to return (default 10, capped at 50).",
                      "minimum": 1,
                      "maximum": 50
                    }
                  }
                }
                """)
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          int limit = clampLimit(args, 10, 50);
          List<PipelineRun> runs =
              pipelineRunRepo.findByProjectIdOrderByStartedAtDesc(
                  ctx.projectId(), PageRequest.of(0, limit));
          ArrayNode arr = json.createArrayNode();
          for (PipelineRun run : runs) {
            ObjectNode row = arr.addObject();
            row.put("runId", run.getRunId());
            row.put("status", run.getStatus());
            row.put("statusReason", run.getStatusReason() == null ? "" : run.getStatusReason());
            row.put("planStrategy", run.getPlanStrategy() == null ? "" : run.getPlanStrategy());
            if (run.getStartedAt() != null) row.put("startedAt", run.getStartedAt().toString());
            if (run.getCompletedAt() != null)
              row.put("completedAt", run.getCompletedAt().toString());
            if (run.getActualCostUsd() != null) row.put("actualCostUsd", run.getActualCostUsd());
          }
          ObjectNode out = json.createObjectNode();
          out.put("ok", true);
          out.put("count", arr.size());
          out.set("runs", arr);
          return out;
        });
  }

  // ── Tool 5: list pull requests ────────────────────────────────────────────

  private ToolCallback listPullRequests(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("list_pull_requests")
            .description(
                "List pull requests opened by ExecuteSpec for this project — both run-scoped (RUN) "
                    + "and system-opened (BOOTSTRAP / ARCHITECTURE / CONTRACT / CHAT_TOOL). "
                    + "Includes URL, repo, status (OPEN / MERGED / CLOSED), branch, kind, and "
                    + "creation timestamp. Read-only.")
            .inputSchema(
                """
                {
                  "type": "object",
                  "properties": {
                    "limit": {
                      "type": "integer",
                      "description": "Max rows to return (default 20, capped at 100).",
                      "minimum": 1,
                      "maximum": 100
                    }
                  }
                }
                """)
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          int limit = clampLimit(args, 20, 100);
          ArrayNode arr = json.createArrayNode();

          // System PRs (kind != RUN) — direct lookup by project_id.
          List<PullRequestRecord> systemPrs =
              prRepo.findByProjectIdAndKindNotOrderByCreatedAtDesc(
                  ctx.projectId(), PullRequestRecord.Kind.RUN);
          for (PullRequestRecord pr : systemPrs) {
            if (arr.size() >= limit) break;
            arr.add(prRowJson(pr));
          }

          // Run-scoped PRs — every pipeline_run row in this project, then PRs by runId.
          if (arr.size() < limit) {
            List<PipelineRun> runs =
                pipelineRunRepo.findByProjectIdOrderByStartedAtDesc(
                    ctx.projectId(), PageRequest.of(0, 50));
            for (PipelineRun run : runs) {
              if (arr.size() >= limit) break;
              for (PullRequestRecord pr : prRepo.findByRunIdOrderByCreatedAtAsc(run.getRunId())) {
                if (arr.size() >= limit) break;
                arr.add(prRowJson(pr));
              }
            }
          }

          ObjectNode out = json.createObjectNode();
          out.put("ok", true);
          out.put("count", arr.size());
          out.set("pullRequests", arr);
          return out;
        });
  }

  private ObjectNode prRowJson(PullRequestRecord pr) {
    ObjectNode row = json.createObjectNode();
    row.put("prUrl", pr.getPrUrl());
    if (pr.getPrNumber() != null) row.put("prNumber", pr.getPrNumber());
    row.put("repoFullName", pr.getRepoFullName() == null ? "" : pr.getRepoFullName());
    row.put("status", pr.getStatus() == null ? "OPEN" : pr.getStatus().name());
    row.put("kind", pr.getKind() == null ? "RUN" : pr.getKind().name());
    if (pr.getHeadBranch() != null) row.put("headBranch", pr.getHeadBranch());
    if (pr.getRunId() != null) row.put("runId", pr.getRunId());
    if (pr.getCreatedAt() != null) row.put("createdAt", pr.getCreatedAt().toString());
    return row;
  }

  // ── Tool 6: get project memory ────────────────────────────────────────────

  private ToolCallback getProjectMemory(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("get_project_memory")
            .description(
                "Read the project's compacted memory snapshot — a compressed history of what's "
                    + "been built, key decisions, and known debt. Auto-maintained by the "
                    + "post-run ledger + 10-run compaction. Read-only.")
            .inputSchema("{ \"type\": \"object\", \"properties\": {} }")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          String memory = memoryProvider.buildPlannerContext(ctx.projectId());
          ObjectNode out = json.createObjectNode();
          out.put("ok", true);
          out.put("memory", memory == null ? "" : memory);
          return out;
        });
  }

  // ── Tool 7: get latest architecture ───────────────────────────────────────

  private ToolCallback getLatestArchitecture(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("get_latest_architecture")
            .description(
                "Return the latest combined architecture document for this project, plus the "
                    + "list of per-repo architecture summaries. Use to answer questions about "
                    + "the project's structure / data flow / module boundaries without "
                    + "regenerating. Read-only.")
            .inputSchema("{ \"type\": \"object\", \"properties\": {} }")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          Optional<ProjectArchitectureDoc> latest = architectureService.latest(ctx.projectId());
          ObjectNode out = json.createObjectNode();
          if (latest.isEmpty()) {
            out.put("ok", true);
            out.put(
                "summary",
                "No architecture document yet. Call regenerate_architecture first if the project "
                    + "has linked repos.");
            return out;
          }
          ProjectArchitectureDoc doc = latest.get();
          out.put("ok", true);
          out.put("version", doc.getVersion());
          out.put("content", doc.getContent());
          if (doc.getGeneratedAt() != null) out.put("generatedAt", doc.getGeneratedAt().toString());

          // Per-repo summaries — best-effort; we hit the per-repo arch repo via service helper.
          ArrayNode repoArchs = out.putArray("repos");
          for (ProjectRepoLink link : linkRepo.findByProjectId(ctx.projectId())) {
            Optional<RepoArchitectureDoc> repoArch =
                safeLatestRepoArch(ctx.projectId(), link.getRepoId());
            if (repoArch.isEmpty()) continue;
            ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
            ObjectNode r = repoArchs.addObject();
            r.put(
                "fullName",
                repo == null ? link.getRepoId().toString() : repo.getGithubRepoFullName());
            r.put("version", repoArch.get().getVersion());
            r.put("content", repoArch.get().getContent());
          }
          return out;
        });
  }

  /**
   * Safe shim around {@code architectureService.latestRepoArch} — that method only exists if the
   * companion repo wiring is on the classpath. Guarded so a missing helper doesn't crash the tool.
   */
  private Optional<RepoArchitectureDoc> safeLatestRepoArch(String projectId, UUID repoId) {
    try {
      return architectureService.latestRepoArch(projectId, repoId);
    } catch (Throwable t) {
      log.debug("latestRepoArch unavailable for {}/{}: {}", projectId, repoId, t.getMessage());
      return Optional.empty();
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static int clampLimit(JsonNode args, int dflt, int max) {
    if (args == null || !args.has("limit") || args.get("limit").isNull()) return dflt;
    int v = args.get("limit").asInt(dflt);
    if (v < 1) return 1;
    if (v > max) return max;
    return v;
  }

  private ObjectNode errorEnvelope(String message) {
    ObjectNode out = json.createObjectNode();
    out.put("ok", false);
    out.put("message", message);
    return out;
  }

  /**
   * Adapter that turns a lambda into a Spring AI {@link ToolCallback}. Centralises JSON parsing +
   * error handling so each tool body only needs the happy path. Mirrors the V2 {@code
   * ContextBoundToolCallback} pattern but without the per-run execution context.
   */
  private final class SimpleToolCallback implements ToolCallback {
    private final ToolDefinition def;
    private final ToolBody body;

    SimpleToolCallback(ToolDefinition def, ToolBody body) {
      this.def = def;
      this.body = body;
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return def;
    }

    @Override
    public String call(String toolInput) {
      JsonNode args;
      try {
        args =
            (toolInput == null || toolInput.isBlank())
                ? json.createObjectNode()
                : json.readTree(toolInput);
      } catch (Exception parseEx) {
        return errorEnvelope("invalid JSON input: " + parseEx.getMessage()).toString();
      }
      try {
        ObjectNode result = body.run(args);
        return result.toString();
      } catch (Exception ex) {
        log.warn(
            "Project chat tool '{}' threw {}: {}",
            def.name(),
            ex.getClass().getSimpleName(),
            ex.getMessage());
        return errorEnvelope(ex.getClass().getSimpleName() + ": " + ex.getMessage()).toString();
      }
    }
  }

  @FunctionalInterface
  private interface ToolBody {
    ObjectNode run(JsonNode args) throws Exception;
  }

  /** Resolve the project entity. Currently unused outside the bind helpers but kept for parity. */
  @SuppressWarnings("unused")
  private Project requireProject(ToolContext ctx) {
    return projectRepo
        .findById(ctx.projectId())
        .orElseThrow(() -> new IllegalArgumentException("project not found: " + ctx.projectId()));
  }
}
