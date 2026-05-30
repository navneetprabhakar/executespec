package com.navneet.executespec.memory.chattools;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.ProjectDocGeneratorService;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;
import com.navneet.executespec.codereview.CodeReviewService;
import com.navneet.executespec.codereview.entity.CodeReviewIssue;
import com.navneet.executespec.codereview.entity.CodeReviewScan;
import com.navneet.executespec.memory.ProjectMemoryProvider;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.project.rag.RetrievalService;
import com.navneet.executespec.project.rag.entity.ProjectDocument;
import com.navneet.executespec.project.rag.repository.ProjectDocumentRepository;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.techdebt.TechDebtService;
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
  private final CodeReviewService codeReviewService;
  private final AppUserRepository appUserRepository;
  private final ProjectDocumentRepository projectDocumentRepository;
  private final RetrievalService retrievalService;
  private final TechDebtService techDebtService;
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
        getLatestArchitecture(ctx),
        runCodeReview(ctx),
        getLatestCodeReview(ctx),
        listProjectDocuments(ctx),
        readGeneratedDoc(ctx),
        searchProjectDocs(ctx),
        getTechDebtSummary(ctx));
  }

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

  // Tool 2: generate project doc

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

  // Tool 4: list recent runs

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

  // Tool 5: list pull requests

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

  // Tool 6: get project memory

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

  // Tool 7: get latest architecture

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

  // Tool 8: run code review (whole-repo Code Review Agent)

  private ToolCallback runCodeReview(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("run_code_review")
            .description(
                "Run a whole-repo code review on the named repo: clone HEAD → semgrep static "
                    + "scan → LLM augmentation pass → persist findings + markdown report. PAID "
                    + "tier only (PRO/TEAM/MAX/ENTERPRISE; FREE returns an error envelope). "
                    + "Returns the new scan id and a short summary; the full markdown report is "
                    + "available via get_latest_code_review or the /api/code-reviews/{id}/report "
                    + "download endpoint. Slow — typically 30-180 seconds depending on repo "
                    + "size.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "repoFullName": {
      "type": "string",
      "description": "owner/repo identifier of a repo linked to this project, e.g. 'navneetprabhakar/executespec'"
    }
  },
  "required": ["repoFullName"]
}
""")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          String repoFullName =
              args.has("repoFullName") && !args.get("repoFullName").isNull()
                  ? args.get("repoFullName").asText(null)
                  : null;
          if (repoFullName == null || repoFullName.isBlank()) {
            return errorEnvelope("repoFullName is required");
          }
          // Resolve the repoId from the link table so we don't kick off a clone for a repo
          // that isn't actually linked to the project.
          UUID repoId = null;
          for (ProjectRepoLink link : linkRepo.findByProjectId(ctx.projectId())) {
            Optional<ProjectRepo> repo = repoRepo.findById(link.getRepoId());
            if (repo.isPresent()
                && repoFullName.equalsIgnoreCase(repo.get().getGithubRepoFullName())) {
              repoId = repo.get().getId();
              break;
            }
          }
          if (repoId == null) {
            return errorEnvelope(
                "Repo " + repoFullName + " is not linked to this project. Use list_repos.");
          }
          AppUser caller =
              ctx.userId() == null ? null : appUserRepository.findById(ctx.userId()).orElse(null);
          try {
            codeReviewService.assertTierAndQuota(caller);
          } catch (CodeReviewService.TierBlockedException tbe) {
            return errorEnvelope("tier_blocked: " + tbe.getMessage());
          } catch (CodeReviewService.QuotaExhaustedException qee) {
            return errorEnvelope("quota_exhausted: " + qee.getMessage());
          }
          CodeReviewScan scan =
              codeReviewService.submitScan(ctx.projectId(), repoId, repoFullName, caller);
          ObjectNode out = json.createObjectNode();
          out.put("ok", true);
          out.put("scanId", scan.getId().toString());
          out.put("status", scan.getStatus());
          out.put(
              "summary",
              "Code review scan queued for "
                  + repoFullName
                  + ". Status will progress through CLOSING → STATIC_RUNNING → LLM_RUNNING → "
                  + "COMPLETE. Run get_latest_code_review once it's done to read the report.");
          return out;
        });
  }

  // Tool 9: get latest code review

  private ToolCallback getLatestCodeReview(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("get_latest_code_review")
            .description(
                "Read the latest COMPLETE whole-repo code review for the named repo on this "
                    + "project. Returns the markdown report verbatim plus structured issue counts. "
                    + "Use to surface the report to the user without forcing a re-scan; combine "
                    + "with run_code_review when the SHA has moved or the user explicitly asks "
                    + "for a refresh. Read-only.")
            .inputSchema(
                """
                {
                  "type": "object",
                  "properties": {
                    "repoFullName": {
                      "type": "string",
                      "description": "owner/repo identifier of a linked repo"
                    }
                  },
                  "required": ["repoFullName"]
                }
                """)
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          String repoFullName =
              args.has("repoFullName") && !args.get("repoFullName").isNull()
                  ? args.get("repoFullName").asText(null)
                  : null;
          if (repoFullName == null || repoFullName.isBlank()) {
            return errorEnvelope("repoFullName is required");
          }
          Optional<CodeReviewScan> latest =
              codeReviewService.latestForProjectRepo(ctx.projectId(), repoFullName);
          ObjectNode out = json.createObjectNode();
          if (latest.isEmpty()) {
            out.put("ok", true);
            out.put(
                "summary",
                "No completed code review for "
                    + repoFullName
                    + " yet. Call run_code_review to start one.");
            return out;
          }
          CodeReviewScan scan = latest.get();
          List<CodeReviewIssue> issues = codeReviewService.openIssuesFor(scan.getId());
          long critical = issues.stream().filter(i -> "CRITICAL".equals(i.getSeverity())).count();
          long major = issues.stream().filter(i -> "MAJOR".equals(i.getSeverity())).count();
          out.put("ok", true);
          out.put("scanId", scan.getId().toString());
          out.put("repoFullName", scan.getRepoFullName());
          out.put("scannedSha", scan.getScannedSha());
          out.put("status", scan.getStatus());
          out.put("totalOpenIssues", issues.size());
          out.put("criticalOpen", critical);
          out.put("majorOpen", major);
          out.put(
              "reportMarkdown", scan.getReportMarkdown() == null ? "" : scan.getReportMarkdown());
          if (scan.getFinishedAt() != null) out.put("finishedAt", scan.getFinishedAt().toString());
          return out;
        });
  }

  // Tool 10: list project documents (uploaded + generated)

  private ToolCallback listProjectDocuments(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("list_project_documents")
            .description(
                "List every document available for this project: user-UPLOADED reference docs"
                    + " (PRDs, specs, design notes — with filename + indexing status) AND"
                    + " SYSTEM-GENERATED docs (EXPLANATION onboarding narrative, SETUP guide). Call"
                    + " this first to see what exists, then read_generated_doc /"
                    + " search_project_docs to read content. The architecture doc + project memory"
                    + " are already in your context. Read-only.")
            .inputSchema("{ \"type\": \"object\", \"properties\": {} }")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          ObjectNode out = json.createObjectNode();
          out.put("ok", true);
          ArrayNode uploaded = out.putArray("uploaded");
          for (ProjectDocument d :
              projectDocumentRepository.findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                  ctx.projectId())) {
            ObjectNode row = uploaded.addObject();
            row.put("filename", d.getFilename() == null ? d.getPathRelative() : d.getFilename());
            row.put("status", d.getStatus() == null ? "" : d.getStatus().name());
            if (d.getMimeType() != null) row.put("mimeType", d.getMimeType());
          }
          ArrayNode generated = out.putArray("generated");
          for (DocType t : DocType.values()) {
            docGeneratorService
                .latest(ctx.projectId(), t)
                .ifPresent(
                    g -> {
                      ObjectNode row = generated.addObject();
                      row.put("docType", g.getDocType().name());
                      row.put("version", g.getVersion());
                      if (g.getGeneratedAt() != null) {
                        row.put("generatedAt", g.getGeneratedAt().toString());
                      }
                    });
          }
          out.put("uploadedCount", uploaded.size());
          out.put("generatedCount", generated.size());
          out.put(
              "hint",
              "READY uploaded docs are searchable via search_project_docs; generated docs are "
                  + "readable verbatim via read_generated_doc.");
          return out;
        });
  }

  // Tool 11: read a generated doc (EXPLANATION / SETUP) verbatim

  private ToolCallback readGeneratedDoc(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("read_generated_doc")
            .description(
                "Read the FULL content of a system-generated project doc WITHOUT regenerating it."
                    + " EXPLANATION = plain-English onboarding narrative (\"what is this"
                    + " project\"); SETUP = runnable local-setup guide. Use to answer onboarding /"
                    + " setup questions. Read-only — use generate_project_doc only to create or"
                    + " refresh.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "docType": {
      "type": "string",
      "enum": ["EXPLANATION", "SETUP"],
      "description": "EXPLANATION for the narrative doc; SETUP for setup instructions."
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
          Optional<ProjectGeneratedDoc> doc = docGeneratorService.latest(ctx.projectId(), docType);
          ObjectNode out = json.createObjectNode();
          if (doc.isEmpty()) {
            out.put("ok", true);
            out.put(
                "summary",
                "No "
                    + docType.name()
                    + " doc exists yet. Call generate_project_doc to create it.");
            return out;
          }
          out.put("ok", true);
          out.put("docType", doc.get().getDocType().name());
          out.put("version", doc.get().getVersion());
          out.put("content", doc.get().getContent());
          return out;
        });
  }

  // Tool 12: semantic search over uploaded reference docs

  private ToolCallback searchProjectDocs(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("search_project_docs")
            .description(
                "Semantic search across the project's UPLOADED reference documents (PRDs, specs, "
                    + "design notes). Returns the most relevant passages for your query — pick a "
                    + "focused query to ground your answer in what the user uploaded. (The chat "
                    + "already auto-injects passages relevant to the user's latest message; call "
                    + "this when you need to search for something specific.) Read-only.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "query": {
      "type": "string",
      "description": "What to search the uploaded documents for."
    }
  },
  "required": ["query"]
}
""")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          String query =
              args.has("query") && !args.get("query").isNull()
                  ? args.get("query").asText(null)
                  : null;
          if (query == null || query.isBlank()) {
            return errorEnvelope("query is required");
          }
          String refs = retrievalService.retrieve(ctx.projectId(), query);
          ObjectNode out = json.createObjectNode();
          out.put("ok", true);
          out.put("query", query);
          if (refs == null || refs.isBlank()) {
            out.put("results", "");
            out.put(
                "summary",
                "No relevant passages found in the uploaded documents (or none are indexed yet).");
          } else {
            out.put("results", refs);
          }
          return out;
        });
  }

  // Tool 13: tech-debt summary (read-only suggestion grounding)

  private ToolCallback getTechDebtSummary(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("get_tech_debt_summary")
            .description(
                "Read this project's aggregated tech-debt rollup across recent runs: issue counts "
                    + "by category + by severity, the top file hotspots, the per-dimension score "
                    + "trend, and how many runs were analysed. This is the canonical 'what should "
                    + "I improve next' data source (same as the dashboard Overview tab). Use it to "
                    + "ground a prioritised list of SUGGESTIONS — it does NOT fix anything; point "
                    + "the user at the dashboard's per-category Fix buttons to act. Read-only.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "sinceDays": {
      "type": "integer",
      "description": "Only include runs from the last N days. Omit / 0 = all history.",
      "minimum": 1,
      "maximum": 365
    }
  }
}
""")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          Integer sinceDays =
              args.has("sinceDays") && !args.get("sinceDays").isNull()
                  ? args.get("sinceDays").asInt()
                  : null;
          TechDebtService.Report report = techDebtService.build(ctx.projectId(), sinceDays);
          ObjectNode out = json.createObjectNode();
          out.put("ok", true);
          out.put("totalRunsAnalyzed", report.totalRunsAnalyzed());
          out.put("averageAccuracyScore", report.averageAccuracyScore());
          out.set("issuesByCategory", json.valueToTree(report.issuesByCategory()));
          out.set("issuesBySeverity", json.valueToTree(report.issuesBySeverity()));
          ArrayNode hotspots = out.putArray("topFiles");
          for (TechDebtService.FileHotspot h : report.topFiles()) {
            ObjectNode row = hotspots.addObject();
            row.put("filePath", h.filePath());
            row.put("issueCount", h.issueCount());
          }
          if (report.totalRunsAnalyzed() == 0) {
            out.put(
                "summary",
                "No completed runs with validation reports yet — no tech debt to summarise.");
          }
          return out;
        });
  }
}
