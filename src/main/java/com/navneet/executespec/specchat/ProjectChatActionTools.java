package com.navneet.executespec.specchat;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.harness.GateStore;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.report.ValidationReportService;
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
 * Write-capable action tools for the CLI/Plugin chat endpoint (RL-2026-05-052, Phase 1 step 2).
 *
 * <p>Distinct from {@link com.navneet.executespec.memory.chattools.ProjectChatTools}, which is
 * read-only and scoped to a single project. These tools mutate run state — submit, approve, reject,
 * cancel — so they live in a separate factory the controller can bind selectively (e.g. skip action
 * tools when the caller's PAT lacks write scope, ship only read tools).
 *
 * <p>Each tool returns a JSON envelope ({@code {"ok": true|false, ...}}) and never throws — tool
 * exceptions surface as a 500 and abort the LLM turn. Access guards run inside each tool body so
 * the LLM gets a "forbidden" envelope back rather than a hard error.
 *
 * <p>Phase 1 covers the 6 server-side action tools listed in the design doc. Two client-side tools
 * — {@code switch_provider} and {@code link_directory} — are handled by the CLI itself (local
 * state) and intentionally NOT mirrored here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectChatActionTools {

  private final RunOrchestrator runOrchestrator;
  private final com.navneet.executespec.integration.RunLifecycleService runLifecycleService;
  private final PipelineRunRepository pipelineRunRepo;
  private final ValidationReportService validationReportService;
  private final GateStore gateStore;
  private final RunAccessGuard runAccessGuard;
  private final ObjectMapper json;

  /** Per-turn context the chat session carries through to each tool. */
  public record ToolContext(
      AppUserDetails userDetails, String projectId, String orgId, String sessionId) {}

  /**
   * Build the per-turn tool list. Pass the result to {@code LlmRequest.toolsOverride(...)} so the
   * LLM can dispatch action tools.
   */
  public List<ToolCallback> bind(ToolContext ctx) {
    return List.of(
        submitRun(ctx),
        approvePlan(ctx),
        rejectPlan(ctx),
        cancelRun(ctx),
        listRecentRuns(ctx),
        getRunReport(ctx));
  }

  private ToolCallback submitRun(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("submit_run")
            .description(
                "Submit a new ExecuteSpec run from a natural-language spec. The run enters the V2"
                    + " pipeline (classifier → planner → plan-approval gate → worker → reviewer)."
                    + " The plan gate pauses until the user explicitly approves via approve_plan."
                    + " Use this when the user describes a change they want made.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "spec": {
      "type": "string",
      "description": "Natural-language spec describing the change. Required."
    },
    "provider": {
      "type": "string",
      "enum": ["anthropic", "openai", "gemini", "openrouter", "deepseek"],
      "description": "Optional LLM provider override; defaults to the user/org default."
    },
    "model": {
      "type": "string",
      "description": "Optional model name override; defaults to the tier-routed default for the provider."
    }
  },
  "required": ["spec"]
}
""")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          String spec = textOrNull(args, "spec");
          if (spec == null || spec.isBlank()) {
            return errorEnvelope("spec is required");
          }
          String provider = textOrNull(args, "provider");
          String model = textOrNull(args, "model");

          PipelineRun submitted =
              runOrchestrator.submitRun(
                  spec,
                  "spec-chat",
                  null, // repoUrl
                  null, // repoMode
                  null, // githubPat
                  null, // githubOwner
                  false, // skipApproval — plan gate ALWAYS runs from chat
                  false, // autoMerge
                  model,
                  provider,
                  String.valueOf(ctx.userDetails().getUser().getId()),
                  ctx.orgId(),
                  ctx.projectId(),
                  // executionMode expects PRODUCTION|PROTOTYPE. Chat runs ALWAYS hit the plan gate
                  // (skipApproval=false above), so PRODUCTION. Run LOCALITY (REMOTE/LOCAL) is a
                  // separate axis that moves to PipelineRun.executionLocality in Phase 3 — it must
                  // not be passed in the executionMode slot (design §10 defect #2).
                  "PRODUCTION");

          ObjectNode out = json.createObjectNode();
          out.put("ok", true);
          out.put("runId", submitted.getRunId());
          out.put("status", submitted.getStatus());
          out.put(
              "summary",
              "Run "
                  + submitted.getRunId()
                  + " submitted. The plan gate will open once the planner emits the plan;"
                  + " the user must call approve_plan with the runId to continue.");
          return out;
        });
  }

  private ToolCallback approvePlan(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("approve_plan")
            .description(
                "Approve the plan-approval gate of a run. The pipeline resumes with the worker."
                    + " Use only after the user has reviewed the plan and explicitly approved.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "runId": {"type": "string", "description": "Required. The run whose plan is being approved."}
  },
  "required": ["runId"]
}
""")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          String runId = textOrNull(args, "runId");
          if (runId == null) return errorEnvelope("runId is required");
          // Write/governance action — must use the WRITE guard, not the read guard (design §10
          // defect #1): a cross-org read-only member must not be able to approve another's gate.
          PipelineRun run = runAccessGuard.requireRunWriteAccess(runId, ctx.userDetails());
          gateStore.setPlanGate(runId, true);
          ObjectNode out = json.createObjectNode();
          out.put("ok", true);
          out.put("runId", run.getRunId());
          out.put("summary", "Plan approved. Worker is resuming.");
          return out;
        });
  }

  private ToolCallback rejectPlan(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("reject_plan")
            .description(
                "Reject the plan-approval gate of a run. The pipeline aborts and the run is marked"
                    + " CANCELLED. Use when the user wants to scrap the proposed plan.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "runId":    {"type": "string", "description": "Required."},
    "feedback": {"type": "string", "description": "Optional reason text — surfaced in the run log."}
  },
  "required": ["runId"]
}
""")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          String runId = textOrNull(args, "runId");
          if (runId == null) return errorEnvelope("runId is required");
          // Write/governance action — WRITE guard (design §10 defect #1).
          PipelineRun run = runAccessGuard.requireRunWriteAccess(runId, ctx.userDetails());
          // Same path as cancel — signals the plan gate to fail, persists CANCELLED.
          boolean cancelled = runLifecycleService.cancel(runId);
          ObjectNode out = json.createObjectNode();
          out.put("ok", cancelled);
          out.put("runId", run.getRunId());
          out.put(
              "summary",
              cancelled
                  ? "Plan rejected; run is being cancelled."
                  : "Run is already in a terminal state; nothing to reject.");
          return out;
        });
  }

  private ToolCallback cancelRun(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("cancel_run")
            .description(
                "Cancel an in-flight run at any phase. The plan gate is signalled, the pipeline"
                    + " future is interrupted, opened PRs are closed. Idempotent: cancelling a"
                    + " terminal run is a no-op.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "runId": {"type": "string", "description": "Required."}
  },
  "required": ["runId"]
}
""")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          String runId = textOrNull(args, "runId");
          if (runId == null) return errorEnvelope("runId is required");
          // Write/governance action — WRITE guard (design §10 defect #1).
          PipelineRun run = runAccessGuard.requireRunWriteAccess(runId, ctx.userDetails());
          boolean cancelled = runLifecycleService.cancel(runId);
          ObjectNode out = json.createObjectNode();
          out.put("ok", cancelled);
          out.put("runId", run.getRunId());
          out.put(
              "summary",
              cancelled ? "Run cancelled." : "Run was already in a terminal state — no-op.");
          return out;
        });
  }

  private ToolCallback listRecentRuns(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("list_recent_runs")
            .description(
                "List the user's recent runs (newest first). Optional filters: limit (1-50,"
                    + " default 10) and status (e.g. COMPLETE, FAILED, AWAITING_APPROVAL,"
                    + " RUNNING).")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "limit":  {"type": "integer", "description": "1-50, default 10."},
    "status": {"type": "string",  "description": "Optional status filter."}
  }
}
""")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          int limit = Math.max(1, Math.min(50, intOrDefault(args, "limit", 10)));
          String statusFilter = textOrNull(args, "status");
          String userId = String.valueOf(ctx.userDetails().getUser().getId());

          var page =
              pipelineRunRepo.findByCreatedByUserIdOrderByStartedAtDesc(
                  userId, PageRequest.of(0, limit));

          ArrayNode arr = json.createArrayNode();
          int included = 0;
          for (PipelineRun run : page) {
            if (statusFilter != null && !statusFilter.equalsIgnoreCase(run.getStatus())) continue;
            ObjectNode r = json.createObjectNode();
            r.put("runId", run.getRunId());
            r.put("status", run.getStatus());
            r.put("startedAt", String.valueOf(run.getStartedAt()));
            arr.add(r);
            if (++included >= limit) break;
          }

          ObjectNode out = json.createObjectNode();
          out.put("ok", true);
          out.set("runs", arr);
          out.put("count", included);
          return out;
        });
  }

  private ToolCallback getRunReport(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("get_run_report")
            .description(
                "Return the validation report for a terminal run — verifiedScore + per-slot"
                    + " breakdown + issues. Use after a run finishes (COMPLETE / PARTIAL /"
                    + " FAILED).")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "runId": {"type": "string", "description": "Required."}
  },
  "required": ["runId"]
}
""")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          String runId = textOrNull(args, "runId");
          if (runId == null) return errorEnvelope("runId is required");
          PipelineRun run = runAccessGuard.requireRunAccess(runId, ctx.userDetails());

          Optional<com.navneet.executespec.report.entity.ValidationReport> report =
              validationReportService.findEntityByRunId(runId);

          ObjectNode out = json.createObjectNode();
          out.put("ok", true);
          out.put("runId", run.getRunId());
          out.put("status", run.getStatus());
          out.put("statusReason", run.getStatusReason());
          if (report.isPresent()) {
            var r = report.get();
            out.put("verifiedScore", r.getVerifiedScore() == null ? -1 : r.getVerifiedScore());
            out.put("filesGenerated", r.getFilesGenerated());
            out.put(
                "summary",
                "verifiedScore=" + r.getVerifiedScore() + " statusReason=" + run.getStatusReason());
          } else {
            out.put(
                "summary",
                "no validation report yet — run may still be in flight or never reached scoring");
          }
          return out;
        });
  }

  private static String textOrNull(JsonNode args, String field) {
    if (args == null || !args.has(field) || args.get(field).isNull()) return null;
    String v = args.get(field).asText();
    return (v == null || v.isBlank()) ? null : v;
  }

  private static int intOrDefault(JsonNode args, String field, int dflt) {
    if (args == null || !args.has(field) || args.get(field).isNull()) return dflt;
    return args.get(field).asInt(dflt);
  }

  private ObjectNode errorEnvelope(String message) {
    ObjectNode out = json.createObjectNode();
    out.put("ok", false);
    out.put("error", message);
    return out;
  }

  /** Shared callback adapter — same shape as ProjectChatTools.SimpleToolCallback. */
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
            "spec-chat action tool '{}' threw {}: {}",
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
}
