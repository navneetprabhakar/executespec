package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Final hierarchical-plan coherence check. It applies deterministic safety contracts, then uses a
 * LIGHTWEIGHT-tier call to patch coverage gaps, dependency errors, conflicting constraints, and
 * multi-repo path leaks. One compact 4k repair may fix a malformed or truncated tool payload;
 * repeated protocol/model failure is fatal.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoherencePlannerAgent {
  private final LlmRouter router;
  private final ObjectMapper objectMapper;
  private final ModelResolver modelResolver;
  private final PromptAssembler promptAssembler;
  private final RunEffectiveRouteService effectiveRouteService;

  private static final String PROMPT_PATH = "prompts/coherence-planner.md";
  private static final int COHERENCE_MAX_TOKENS = 4_096;
  private static final int COHERENCE_REPAIR_MAX_TOKENS = 4_096;

  public record PatchResult(
      Plan patchedPlan,
      int patchesApplied,
      List<String> patchSummaries,
      boolean validated,
      String validationFailure) {
    public PatchResult(Plan patchedPlan, int patchesApplied, List<String> patchSummaries) {
      this(patchedPlan, patchesApplied, patchSummaries, true, null);
    }
  }

  /**
   * Run the coherence pass over an assembled plan. Returns a possibly-patched plan along with
   * summary strings for each applied patch. Protocol/model failures return {@code validated=false};
   * hierarchical planning must fail closed rather than execute an unvalidated assembled plan.
   */
  public PatchResult patchIfNeeded(Plan plan, PlannerRequest req, ExecutionContext context) {
    return patchIfNeeded(plan, req, context, List.of(), null, false);
  }

  private PatchResult patchIfNeeded(
      Plan plan,
      PlannerRequest req,
      ExecutionContext context,
      List<String> contractViolations,
      ResolvedModel exactRoute,
      boolean strictProvider) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) {
      return new PatchResult(plan, 0, List.of());
    }

    PlanSafetyContractEnricher.Result deterministic =
        PlanSafetyContractEnricher.enrich(plan, req.spec());
    Plan planWithSafetyContracts = deterministic.plan();

    String systemPrompt;
    try {
      systemPrompt =
          loadPrompt()
              .replace("{{PLAN_SUMMARY}}", "(provided as untrusted evidence below)")
              .replace("{{PLAN_META}}", "(provided as untrusted evidence below)")
              .replace("{{WORKSPACE_SUMMARY}}", "(provided as untrusted evidence below)")
              .replace("{{REQUIREMENTS}}", "(provided as untrusted evidence below)")
              .replace("{{TASKS}}", "(provided as untrusted evidence below)");
    } catch (Exception promptEx) {
      log.warn(
          "CoherencePlanner prompt assembly failed — skipping pass: {}", promptEx.getMessage());
      return failed(planWithSafetyContracts, "prompt assembly failed");
    }

    List<Message> messages =
        new ArrayList<>(
            List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(
                    contractViolations.isEmpty()
                        ? CoherencePlannerEvidence.build(planWithSafetyContracts, req)
                        : CoherencePlannerEvidence.buildContractRepair(
                            planWithSafetyContracts, req, contractViolations))));

    ToolCallback patchPlan = buildPatchPlanCallback();
    LlmProvider provider = resolveProvider(req.provider());
    ResolvedModel resolved = exactRoute;
    if (resolved == null) {
      try {
        resolved = modelResolver.resolve("CoherencePlannerAgent", provider, AgentTier.LIGHTWEIGHT);
      } catch (Exception e) {
        log.warn("CoherencePlanner model resolve failed — skipping pass: {}", e.getMessage());
        return failed(planWithSafetyContracts, "model resolution failed");
      }
    }

    log.info(
        "CoherencePlanner -> {} {} (tasks={}, requirements={})",
        resolved.providerName(),
        resolved.getModel(),
        planWithSafetyContracts.tasks().size(),
        planWithSafetyContracts.requirements() == null
            ? 0
            : planWithSafetyContracts.requirements().size());

    ChatResponse resp;
    try {
      resp =
          CoherencePlannerProtocol.call(
              router,
              messages,
              resolved,
              context,
              patchPlan,
              "planner.coherence",
              COHERENCE_MAX_TOKENS,
              strictProvider);
    } catch (Exception e) {
      log.warn("CoherencePlanner LLM call failed — using unpatched plan: {}", e.getMessage());
      return failed(planWithSafetyContracts, "LLM call failed");
    }

    AssistantMessage.ToolCall patchCall = CoherencePlannerProtocol.findPatchCall(resp);
    if (patchCall == null) {
      log.warn("CoherencePlanner did not call patchPlan; attempting one compact protocol repair");
      return CoherencePlannerProtocol.repair(
          router,
          planWithSafetyContracts,
          messages,
          resolved,
          context,
          patchPlan,
          "tool was not called",
          COHERENCE_REPAIR_MAX_TOKENS,
          strictProvider,
          this::applyPatches);
    }

    PatchResult modelResult = applyPatches(planWithSafetyContracts, patchCall.arguments());
    if (isProtocolRepairable(modelResult)) {
      log.warn(
          "CoherencePlanner emitted an invalid tool payload; attempting one compact protocol"
              + " repair");
      modelResult =
          CoherencePlannerProtocol.repair(
              router,
              planWithSafetyContracts,
              messages,
              resolved,
              context,
              patchPlan,
              modelResult.validationFailure(),
              COHERENCE_REPAIR_MAX_TOKENS,
              strictProvider,
              this::applyPatches);
    }
    if (!modelResult.validated()) return modelResult;
    // A no-op model response cannot have reintroduced unsafe text. Re-enriching the already
    // canonical plan only duplicates deterministic accounting (and, for a non-idempotent defect,
    // risks changing the plan without any provider patch to justify it).
    if (modelResult.patchesApplied() == 0) {
      return finishNoOpModelPass(deterministic, modelResult);
    }
    // The model patch can itself append text that contradicts deterministic safety contracts.
    // Canonicalize once more before persistence so coherence cannot reintroduce the exact unsafe
    // instruction the pre-pass removed.
    PlanSafetyContractEnricher.Result postModel =
        PlanSafetyContractEnricher.enrich(modelResult.patchedPlan(), req.spec());
    if (deterministic.contractsApplied() == 0 && postModel.contractsApplied() == 0) {
      return modelResult;
    }

    List<String> summaries = new ArrayList<>(deterministic.summaries());
    summaries.addAll(modelResult.patchSummaries());
    summaries.addAll(postModel.summaries());
    return new PatchResult(
        postModel.plan(),
        deterministic.contractsApplied()
            + modelResult.patchesApplied()
            + postModel.contractsApplied(),
        List.copyOf(summaries));
  }

  static PatchResult finishNoOpModelPass(
      PlanSafetyContractEnricher.Result deterministic, PatchResult modelResult) {
    return deterministic.contractsApplied() == 0
        ? modelResult
        : new PatchResult(
            deterministic.plan(), deterministic.contractsApplied(), deterministic.summaries());
  }

  static boolean isProtocolRepairable(PatchResult result) {
    return CoherencePlannerProtocol.isRepairable(result);
  }

  /**
   * One bounded, targeted repair after deterministic final-plan validation. The reported violations
   * are evidence, not authority; the same provider, tool schema, and safety canonicalization used
   * by the normal coherence pass still apply.
   */
  public PatchResult repairContractViolations(
      Plan plan, PlannerRequest req, ExecutionContext context, List<String> contractViolations) {
    if (contractViolations == null || contractViolations.isEmpty()) {
      return new PatchResult(plan, 0, List.of());
    }
    return patchIfNeeded(plan, req, context, contractViolations, null, false);
  }

  /**
   * One bounded contract repair on an exact provider/model route.
   *
   * <p>This is the pre-gate recovery foundation. It deliberately bypasses the coherence agent's
   * LIGHTWEIGHT tier lookup and disables cross-provider failover; LlmRouter may still retry the
   * same physical provider/model for transient transport failures.
   */
  public PatchResult repairContractViolationsExact(
      Plan plan,
      PlannerRequest req,
      ExecutionContext context,
      List<String> contractViolations,
      ResolvedModel exactRoute) {
    if (contractViolations == null || contractViolations.isEmpty()) {
      return new PatchResult(plan, 0, List.of());
    }
    if (exactRoute == null
        || exactRoute.getActualProvider() == null
        || exactRoute.getModel() == null
        || exactRoute.getModel().isBlank()) {
      return failed(plan, "exact planner repair route unavailable");
    }
    RunEffectiveRouteService.AttemptFence fence =
        effectiveRouteService == null
            ? null
            : effectiveRouteService.captureAttemptFence(context.runId(), context.taskId(), 0L);
    PatchResult result = patchIfNeeded(plan, req, context, contractViolations, exactRoute, true);
    if (effectiveRouteService == null) return result;
    ExactPlannerRouteAttestation.verify(
        exactRoute, effectiveRouteService.successfulForTaskAfter(fence));
    return result;
  }

  private PatchResult applyPatches(Plan plan, String argsJson) {
    JsonNode root;
    try {
      root = objectMapper.readTree(argsJson);
    } catch (Exception e) {
      log.warn("patchPlan args are not valid JSON — using unpatched plan: {}", e.getMessage());
      return failed(plan, "patchPlan arguments were invalid JSON");
    }
    JsonNode patches = root.path("patches");
    PatchResult fatal =
        CoherencePlannerProtocol.fatalConflictFailure(plan, root.path("fatalConflicts"));
    if (fatal != null) return fatal;
    if (!patches.isArray()) {
      return failed(plan, "patchPlan omitted the patches array");
    }
    if (patches.size() == 0) {
      log.info("CoherencePlanner: 0 patches needed — plan is clean");
      return new PatchResult(plan, 0, List.of());
    }

    // Mutable working copy so we can apply patches one at a time.
    Map<String, CoherenceMutableTask> byId = new LinkedHashMap<>();
    for (PlannedTask t : plan.tasks()) {
      byId.put(t.id(), CoherenceMutableTask.from(t));
    }

    List<String> summaries = new ArrayList<>();
    int applied = 0;
    for (JsonNode patch : patches) {
      String kind = patch.path("kind").asText("").trim();
      String taskId = patch.path("taskId").asText("").trim();
      String reason = patch.path("reason").asText("").trim();
      // RR-1194 — the schema requires a `reason` key but did not require it to be non-blank, so a
      // blank-reason patch could still mutate the plan and be surfaced with no provenance. The
      // prompt promises reviewers read these reasons; a blank one violates that contract. Fail
      // closed: a patch with no explanation does not mutate the plan.
      if (reason.isEmpty()) {
        log.warn(
            "CoherencePlanner: patch kind={} task={} has a blank reason — skipping (RR-1194)",
            kind,
            taskId);
        continue;
      }
      CoherenceMutableTask task = byId.get(taskId);
      if (task == null) {
        log.warn(
            "CoherencePlanner: patch targets unknown taskId={} (kind={}) — skipping", taskId, kind);
        continue;
      }
      try {
        switch (kind) {
          case "ADD_MEETS_REQUIREMENT" -> {
            String reqId = patch.path("requirementId").asText("").trim();
            if (reqId.isEmpty()) break;
            if (!task.meetsRequirements.contains(reqId)) {
              task.meetsRequirements.add(reqId);
              applied++;
              summaries.add(taskId + " += meetsRequirement[" + reqId + "] · " + reason);
            }
          }
          case "ADD_DEPENDS_ON" -> {
            String dep = patch.path("dependsOnTaskId").asText("").trim();
            if (dep.isEmpty()) break;
            if (!byId.containsKey(dep)) {
              log.warn("CoherencePlanner: ADD_DEPENDS_ON targets unknown task {}, skipping", dep);
              break;
            }
            if (!task.dependsOn.contains(dep)) {
              task.dependsOn.add(dep);
              applied++;
              summaries.add(taskId + " += dependsOn[" + dep + "] · " + reason);
            }
          }
          case "REMOVE_DEPENDS_ON" -> {
            String dep = patch.path("dependsOnTaskId").asText("").trim();
            if (task.dependsOn.remove(dep)) {
              applied++;
              summaries.add(taskId + " -= dependsOn[" + dep + "] · " + reason);
            }
          }
          case "FIX_HARD_CONSTRAINT_CONFLICT" -> {
            String oldConstraint = patch.path("oldConstraint").asText("").trim();
            String newConstraint = patch.path("newConstraint").asText("").trim();
            if (oldConstraint.isEmpty() || newConstraint.isEmpty()) break;
            int oldIndex = task.hardConstraints.indexOf(oldConstraint);
            if (oldIndex < 0) {
              log.warn(
                  "CoherencePlanner: exact old constraint absent on task {} — refusing additive"
                      + " conflict patch",
                  taskId);
              break;
            }
            task.hardConstraints.remove(oldIndex);
            task.hardConstraints.add(newConstraint);
            applied++;
            summaries.add(
                taskId
                    + " hardConstraint '"
                    + truncate(oldConstraint, 40)
                    + "' → '"
                    + truncate(newConstraint, 60)
                    + "' · "
                    + reason);
          }
          case "REMOVE_HARD_CONSTRAINT" -> {
            String oldConstraint = patch.path("oldConstraint").asText("").trim();
            if (oldConstraint.isEmpty()) break;
            if (task.hardConstraints.remove(oldConstraint)) {
              applied++;
              summaries.add(
                  taskId
                      + " removed hardConstraint '"
                      + truncate(oldConstraint, 60)
                      + "' · "
                      + reason);
            } else {
              log.warn(
                  "CoherencePlanner: exact constraint absent on task {} — cannot remove", taskId);
            }
          }
          case "REPLACE_DESCRIPTION_TEXT" -> {
            String oldText = patch.path("oldText").asText("");
            String newText = patch.path("newText").asText("");
            if (oldText.isBlank() || newText.isBlank() || oldText.equals(newText)) break;
            if (!task.description.contains(oldText)) {
              log.warn(
                  "CoherencePlanner: exact description text absent on task {} — cannot replace",
                  taskId);
              break;
            }
            task.description = task.description.replace(oldText, newText);
            applied++;
            summaries.add(
                taskId
                    + " description '"
                    + truncate(oldText, 50)
                    + "' → '"
                    + truncate(newText, 70)
                    + "' · "
                    + reason);
          }
          case "MOVE_FILE_TO_REPO_SUBDIR" -> {
            String from = patch.path("from").asText("").trim();
            String to = patch.path("to").asText("").trim();
            if (from.isEmpty() || to.isEmpty()) break;
            // RR-1192 — apply the move ATOMICALLY and only when the exact source is present and the
            // target is a distinct path. Previously `to` was added unconditionally, so an
            // absent-source patch silently injected an undisclosed model-authored path into the
            // task's file hints WITHOUT appearing in the applied count/summary, and a from==to
            // no-op still incremented the count. Now: same path is a no-op, absent source is
            // rejected, and only a real move mutates the map and is ledgered.
            if (from.equals(to)) {
              log.warn(
                  "CoherencePlanner: MOVE_FILE from==to ({}) on task {} — no-op, skipping",
                  from,
                  taskId);
              break;
            }
            if (!task.touchesFiles.contains(from)) {
              log.warn(
                  "CoherencePlanner: MOVE_FILE source {} not present on task {} — skipping (no"
                      + " unrelated target injection)",
                  from,
                  taskId);
              break;
            }
            task.touchesFiles.remove(from);
            if (!task.touchesFiles.contains(to)) task.touchesFiles.add(to);
            applied++;
            summaries.add(taskId + " moved file " + from + " → " + to + " · " + reason);
          }
          default -> log.warn("CoherencePlanner: unknown patch kind '{}' — skipping", kind);
        }
      } catch (Exception applyEx) {
        log.warn(
            "CoherencePlanner: patch apply failed kind={} task={} — {}",
            kind,
            taskId,
            applyEx.getMessage());
      }
    }

    if (applied == 0) {
      log.warn("CoherencePlanner: emitted patches but none were valid — validation failed");
      return failed(plan, "patchPlan emitted no applicable patches");
    }

    List<PlannedTask> patchedTasks = new ArrayList<>(byId.size());
    for (PlannedTask original : plan.tasks()) {
      patchedTasks.add(byId.get(original.id()).toImmutable(original));
    }
    Plan patchedPlan =
        Plan.builder()
            .planTitle(plan.planTitle())
            .planSummary(plan.planSummary())
            .projectType(plan.projectType())
            .buildCommand(plan.buildCommand())
            .smokeTestStrategy(plan.smokeTestStrategy())
            .runtimeSurface(plan.runtimeSurface())
            .tasks(patchedTasks)
            .classification(plan.classification())
            .requirements(plan.requirements())
            .build();

    log.info("CoherencePlanner applied {} patches", applied);
    return new PatchResult(patchedPlan, applied, summaries);
  }

  private static PatchResult failed(Plan plan, String reason) {
    return new PatchResult(plan, 0, List.of(), false, reason);
  }

  private ToolCallback buildPatchPlanCallback() {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("patchPlan")
            .description(
                "Submit fatal requirement/contract conflicts plus zero or more minimal coherence"
                    + " patches. Non-empty fatalConflicts rejects the plan. Empty fatalConflicts"
                    + " and patches means clean. Each patch must have kind, taskId, and a reason.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "fatalConflicts": {
      "type": "array",
      "description": "Requirement/producer/consumer contradictions that cannot be repaired without rewriting a task description. Non-empty rejects the plan.",
      "maxItems": 12,
      "items": {"type": "string", "maxLength": 500}
    },
    "patches": {
      "type": "array",
      "maxItems": 12,
      "items": {
        "type": "object",
        "properties": {
          "kind": {"type": "string", "description": "ADD_MEETS_REQUIREMENT | ADD_DEPENDS_ON | REMOVE_DEPENDS_ON | FIX_HARD_CONSTRAINT_CONFLICT | REMOVE_HARD_CONSTRAINT | REPLACE_DESCRIPTION_TEXT | MOVE_FILE_TO_REPO_SUBDIR"},
          "taskId": {"type": "string"},
          "requirementId": {"type": "string", "description": "For ADD_MEETS_REQUIREMENT"},
          "dependsOnTaskId": {"type": "string", "description": "For ADD/REMOVE_DEPENDS_ON"},
          "oldConstraint": {"type": "string", "description": "Exact existing constraint to replace/remove for FIX_HARD_CONSTRAINT_CONFLICT or REMOVE_HARD_CONSTRAINT"},
          "newConstraint": {"type": "string", "description": "Replacement for FIX_HARD_CONSTRAINT_CONFLICT"},
          "oldText": {"type": "string", "description": "Exact existing description substring for REPLACE_DESCRIPTION_TEXT"},
          "newText": {"type": "string", "description": "Contract-compatible replacement substring for REPLACE_DESCRIPTION_TEXT"},
          "from": {"type": "string", "description": "For MOVE_FILE_TO_REPO_SUBDIR"},
          "to": {"type": "string", "description": "For MOVE_FILE_TO_REPO_SUBDIR"},
          "reason": {"type": "string", "maxLength": 160}
        },
        "required": ["kind", "taskId", "reason"]
      }
    }
  },
  "required": ["patches"]
}
""")
            .build();
    final String callbackId = "coherence-" + UUID.randomUUID();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return def;
      }

      @Override
      public String call(String toolInput) {
        log.debug(
            "patchPlan callback invoked unexpectedly ({}); args={}",
            callbackId,
            truncate(toolInput, 200));
        return "{\"ok\":true}";
      }
    };
  }

  String loadPrompt() {
    try {
      return promptAssembler.assemble(PROMPT_PATH.substring("prompts/".length()));
    } catch (Exception e) {
      throw new IllegalStateException("CoherencePlanner prompt missing at " + PROMPT_PATH, e);
    }
  }

  static String extractContractEvidence(String description) {
    return CoherencePlannerEvidence.extractContractEvidence(description);
  }

  static String buildCoherenceEvidence(Plan plan, PlannerRequest req) {
    return CoherencePlannerEvidence.build(plan, req);
  }

  private static LlmProvider resolveProvider(String provider) {
    if (provider == null || provider.isBlank()) return LlmProvider.OPENAI;
    try {
      return LlmProvider.valueOf(provider.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    } catch (IllegalArgumentException e) {
      return LlmProvider.OPENAI;
    }
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }
}
