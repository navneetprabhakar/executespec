package com.navneet.executespec.execution.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.entity.ExecutionSolutionRepo;
import com.navneet.executespec.execution.event.ExecutionEventTypes;
import com.navneet.executespec.execution.model.DependencyEdge;
import com.navneet.executespec.execution.model.ExecutionPlan;
import com.navneet.executespec.execution.model.FileConflict;
import com.navneet.executespec.execution.model.SolutionSpec;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import com.navneet.executespec.memory.ProjectMemoryProvider;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Decomposes an approved requirement + unified architecture doc into a sequence of {@link
 * SolutionSpec}s (one per repo). Persists the decomposition as {@link ExecutionSolution} rows, plus
 * the overall JSON plan onto {@link ExecutionRun#setExecutionPlan(String)}.
 *
 * <p>First iteration uses a single PLANNING-tier LLM call with a JSON-in-text output contract (not
 * a tool call) to keep the implementation compact. Migrating to a {@code submitExecutionPlan}
 * native tool call is a follow-up.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionPlannerService {

  private final LlmRouter llmRouter;
  private final ModelResolver modelResolver;
  private final ObjectMapper objectMapper;
  private final ExecutionRunRepository runRepo;
  private final ExecutionSolutionRepository solutionRepo;
  private final ProjectCodeMapRepository codeMapRepo;
  private final ProjectRepoRepository projectRepoRepo;
  private final ProjectMemoryProvider memoryProvider;
  private final FileConflictDetector conflictDetector;
  private final SseBroadcaster broadcaster;
  private final ExecutionRunCasUpdater runCas;

  @Async
  public void generatePlan(String executionRunId) {
    ExecutionRun run = runRepo.findById(executionRunId).orElse(null);
    if (run == null) return;
    // 2026-04-26 hotfix — covered by ExecutionRunCasUpdater. The
    // long-running planner LLM call below (~30s) straddles concurrent
    // writes (SSE update path, abort callback). The original code held
    // the in-memory `run` reference across the whole call and saved it
    // after parsePlan/persistPlan, OOMing when @Version had moved on
    // and leaving the row wedged at PLANNING. Wrap each save through
    // the CAS bean.
    boolean markedPlanning = runCas.casUpdate(executionRunId, r -> r.setStatus("PLANNING"));
    if (!markedPlanning) {
      log.warn(
          "generatePlan: failed to mark exec {} as PLANNING (stale row); aborting", executionRunId);
      return;
    }
    emit(run.getId(), ExecutionEventTypes.EXEC_PLANNING_STARTED, null);

    try {
      String template =
          new ClassPathResource("prompts/execution_planner.txt")
              .getContentAsString(StandardCharsets.UTF_8);
      // R-324 — scope the planner's codemap view to the user's
      // launch-time repo selection when one was made. NULL/empty
      // means "every linked project repo is in scope" (today's
      // pre-R-324 default), so this is a no-op for legacy runs.
      java.util.Set<UUID> scopedRepoIds = parseSelectedRepoIds(run.getSelectedRepoIds());
      String codemapsText = buildCodemapsText(run.getProjectId(), scopedRepoIds);
      String filled =
          template
              .replace("{{REQUIREMENT}}", run.getRequirement() == null ? "" : run.getRequirement())
              .replace(
                  "{{UNIFIED_ARCH_DOC}}",
                  run.getUnifiedArchDoc() == null ? "(no arch doc)" : run.getUnifiedArchDoc())
              .replace("{{CODEMAPS}}", codemapsText)
              .replace(
                  "{{PROJECT_MEMORY}}", memoryProvider.buildPlannerContext(run.getProjectId()));

      // v2.5.9 Bug #1a fix — honour the user's selected provider instead of
      // hardcoding OPENAI. Falls back to ANTHROPIC only when the run has no
      // provider set (shouldn't happen after v2.5.1 backfill but kept as a
      // belt-and-suspenders default).
      LlmProvider requested;
      try {
        requested =
            run.getAiProvider() != null
                ? LlmProvider.valueOf(run.getAiProvider().toUpperCase())
                : LlmProvider.ANTHROPIC;
      } catch (IllegalArgumentException ex) {
        log.warn(
            "ExecutionPlannerService run={} has invalid aiProvider='{}' — defaulting to ANTHROPIC",
            executionRunId,
            run.getAiProvider());
        requested = LlmProvider.ANTHROPIC;
      }
      ResolvedModel resolved = modelResolver.resolve("PlannerAgentV2", requested);
      log.info(
          "ExecutionPlannerService run={} provider={} model={}",
          executionRunId,
          resolved.providerName(),
          resolved.getModel());
      Prompt prompt =
          new Prompt(
              List.of(
                  new SystemMessage(
                      filled
                          + "\n\nRespond with a JSON object: "
                          + "{\"solutions\":[...], \"dependencyEdges\":[{\"from\":1,\"to\":2}]}"
                          + ". No prose, no code fences."),
                  new UserMessage("Emit the plan now.")));
      // v2.5.9 Bug #1b fix — NO_FALLBACK so a provider failure surfaces to the
      // user instead of silently switching providers. Users must retry with a
      // different provider explicitly.
      ChatResponse resp =
          llmRouter.callWithFallback(
              resolved.providerName(),
              resolved.getModel(),
              prompt,
              "exec-plan-" + executionRunId,
              "execution-planner",
              LlmRouter.FallbackMode.NO_FALLBACK,
              0,
              false);
      String body = resp.getResult().getOutput().getText();
      ExecutionPlan plan = parsePlan(body);
      persistPlan(run, plan);
      emit(run.getId(), ExecutionEventTypes.EXEC_PLAN_READY, null);
    } catch (Exception e) {
      log.error("ExecutionPlannerService run={} failed: {}", executionRunId, e.getMessage(), e);
      // 2026-04-26 hotfix — covered by ExecutionRunCasUpdater. We're in
      // the catch after a long LLM call; the in-memory `run` reference
      // is definitely stale.
      runCas.casUpdate(executionRunId, r -> r.setStatus("ABORTED"));
      emit(run.getId(), ExecutionEventTypes.EXEC_ABORTED, e.getMessage());
    }
  }

  /**
   * Package-private for unit-test access — see {@link
   * com.navneet.executespec.execution.service.ExecutionPlannerCodemapFilterTest}.
   */
  String buildCodemapsText(String projectId, java.util.Set<UUID> scopedRepoIds) {
    var maps = codeMapRepo.findByProjectId(projectId);
    if (maps.isEmpty()) return "(no codemaps)";
    // R-324 — when the user picked a subset of project repos at Launch
    // time, restrict the planner's view to those repos. Empty / null
    // scope means "no filter" (every linked repo is in scope).
    boolean filtered = scopedRepoIds != null && !scopedRepoIds.isEmpty();
    StringBuilder sb = new StringBuilder();
    int included = 0;
    for (var cm : maps) {
      if (filtered && !scopedRepoIds.contains(cm.getRepoId())) continue;
      String repoName =
          projectRepoRepo
              .findById(cm.getRepoId())
              .map(ProjectRepo::getGithubRepoFullName)
              .orElse(cm.getRepoId().toString());
      sb.append("### ")
          .append(repoName)
          .append(" (id=")
          .append(cm.getRepoId())
          .append(")\n")
          .append(truncate(cm.getContent(), 3000))
          .append("\n\n");
      included++;
    }
    if (included == 0) {
      // The user selected repos that have no codemaps yet. Surface
      // that explicitly so the planner doesn't silently fall back
      // to a "(no codemaps)" empty state, which masks the cause.
      return filtered
          ? "(no codemaps for the selected repos — analyse the project to generate them)"
          : "(no codemaps)";
    }
    return sb.toString();
  }

  /**
   * R-324 — parse the {@code execution_run.selected_repo_ids} JSONB column (stored as a JSON string
   * of UUID strings) into a Set for filtering. Tolerant of malformed values: returns an empty set
   * which the caller treats as "no filter".
   */
  java.util.Set<UUID> parseSelectedRepoIds(String json) {
    if (json == null || json.isBlank() || "[]".equals(json.trim())) return java.util.Set.of();
    try {
      JsonNode node = objectMapper.readTree(json);
      if (!node.isArray()) return java.util.Set.of();
      java.util.Set<UUID> out = new java.util.LinkedHashSet<>();
      for (JsonNode el : node) {
        String s = el.asText(null);
        if (s == null || s.isBlank()) continue;
        try {
          out.add(UUID.fromString(s));
        } catch (IllegalArgumentException ignored) {
          // Not a UUID — skip rather than throw; legacy rows
          // could have any shape.
        }
      }
      return out;
    } catch (Exception e) {
      log.debug("[R-324] parseSelectedRepoIds failed for input '{}': {}", json, e.getMessage());
      return java.util.Set.of();
    }
  }

  private ExecutionPlan parsePlan(String raw) throws Exception {
    String json = raw == null ? "{}" : raw.trim();
    // Strip fenced code blocks if the model wrapped the response
    if (json.startsWith("```")) {
      int nl = json.indexOf('\n');
      if (nl > 0) json = json.substring(nl + 1);
      if (json.endsWith("```")) json = json.substring(0, json.length() - 3);
    }
    JsonNode tree = objectMapper.readTree(json);
    List<SolutionSpec> specs = new ArrayList<>();
    JsonNode solsNode = tree.path("solutions");
    int seq = 0;
    for (JsonNode n : solsNode) {
      seq++;
      specs.add(
          new SolutionSpec(
              n.path("sequenceNumber").asInt(seq),
              n.path("title").asText("Solution " + seq),
              n.path("description").asText(""),
              n.path("repoId").asText(""),
              n.path("repoName").asText(""),
              asList(n.path("targetFiles")),
              asIntList(n.path("dependsOn")),
              n.path("estimatedTokens").asLong(30_000L),
              n.path("complexity").asText("MEDIUM")));
    }
    List<DependencyEdge> edges = new ArrayList<>();
    for (JsonNode n : tree.path("dependencyEdges")) {
      edges.add(new DependencyEdge(n.path("from").asInt(), n.path("to").asInt()));
    }
    List<FileConflict> conflicts = conflictDetector.detectConflicts(specs);
    long totalTok = specs.stream().mapToLong(SolutionSpec::estimatedTokens).sum();
    BigDecimal totalCost =
        BigDecimal.valueOf(totalTok)
            .multiply(new BigDecimal("0.00001"))
            .setScale(4, RoundingMode.HALF_UP);
    return new ExecutionPlan(specs, edges, conflicts, totalTok, totalCost);
  }

  private void persistPlan(ExecutionRun run, ExecutionPlan plan) throws Exception {
    // 2026-04-26 hotfix — covered by ExecutionRunCasUpdater. The in-memory
    // `run` reference here is stale by ~30s (the planner LLM call upstream
    // in generatePlan straddles concurrent writes from the SSE / abort
    // paths). Wrap the run save through CAS, then refetch a fresh entity
    // to use as the FK reference for the new ExecutionSolution rows. The
    // FK only needs the row id; using a stale entity for the
    // @ManyToOne write is safe but using one for save() is not.
    final String planJson = objectMapper.writeValueAsString(plan);
    final long totalTok = plan.totalEstimatedTokens();
    boolean ok =
        runCas.casUpdate(
            run.getId(),
            r -> {
              r.setExecutionPlan(planJson);
              r.setTotalEstimatedTokens(totalTok);
            });
    if (!ok) {
      throw new IllegalStateException(
          "persistPlan: failed to persist plan for exec "
              + run.getId()
              + " (stale row after retries); aborting solution writes");
    }
    ExecutionRun freshRun = runRepo.findById(run.getId()).orElse(run);

    // Map sequenceNumber -> saved ExecutionSolution to wire dependency edges.
    Map<Integer, ExecutionSolution> bySeq = new java.util.HashMap<>();
    for (SolutionSpec spec : plan.solutions()) {
      ExecutionSolution sol =
          ExecutionSolution.builder()
              .executionRun(freshRun)
              .sequenceNumber(spec.sequenceNumber())
              .title(spec.title())
              .description(spec.description())
              .status("QUEUED")
              .complexity(spec.complexity())
              .estimatedTokens(spec.estimatedTokens())
              .maxRetries(3)
              .build();
      sol = solutionRepo.save(sol);

      UUID repoUuid;
      try {
        repoUuid = UUID.fromString(spec.repoId());
      } catch (Exception ex) {
        repoUuid = null;
      }
      // Fallback: LLMs frequently return the repo name or short-name in the
      // repoId field instead of a UUID. Match against the project's linked
      // repos so the clone + push paths still fire.
      if (repoUuid == null) {
        String needle =
            (spec.repoName() != null && !spec.repoName().isBlank())
                ? spec.repoName()
                : spec.repoId();
        if (needle != null && !needle.isBlank()) {
          String n = needle.toLowerCase();
          repoUuid =
              projectRepoRepo.findAll().stream()
                  .filter(
                      r -> {
                        String full =
                            r.getGithubRepoFullName() == null
                                ? ""
                                : r.getGithubRepoFullName().toLowerCase();
                        String shortName =
                            full.contains("/") ? full.substring(full.indexOf('/') + 1) : full;
                        return full.equals(n)
                            || shortName.equals(n)
                            || shortName.contains(n)
                            || n.contains(shortName);
                      })
                  .findFirst()
                  .map(r -> r.getId())
                  .orElse(null);
        }
      }
      ExecutionSolutionRepo repoMap =
          ExecutionSolutionRepo.builder()
              .solution(sol)
              .repoId(repoUuid)
              .targetFiles(safeJson(spec.targetFiles()))
              .build();
      sol.setRepoMappings(new java.util.HashSet<>(java.util.List.of(repoMap)));
      // Re-save so the cascade actually persists the ExecutionSolutionRepo row.
      // Without this second save, `solution.repoMappings` exists in-memory only
      // and the downstream V2 clone sees repoId=null.
      sol = solutionRepo.save(sol);
      bySeq.put(spec.sequenceNumber(), sol);
    }

    // v2.5.9 Bug #2 fix — LLMs routinely return duplicate dependency edges
    // (e.g. same {from, to} listed twice when multiple solutions share an
    // ancestor). The in-memory HashSet drops the dupe, but each save(to)
    // triggers a Hibernate flush that INSERTs duplicate rows into
    // execution_solution_dependency, violating the composite PK and
    // aborting the entire planning step. Dedupe upfront, then save ONCE
    // per affected solution.
    java.util.Set<String> seenEdges = new java.util.HashSet<>();
    java.util.Set<ExecutionSolution> dirty = new java.util.HashSet<>();
    for (DependencyEdge edge : plan.dependencyEdges()) {
      String key = edge.from() + "->" + edge.to();
      if (!seenEdges.add(key)) continue; // skip duplicate edge
      ExecutionSolution to = bySeq.get(edge.to());
      ExecutionSolution from = bySeq.get(edge.from());
      if (to == null || from == null) continue;
      if (to.getDependencies() == null) to.setDependencies(new java.util.HashSet<>());
      to.getDependencies().add(from);
      dirty.add(to);
    }
    // Batch the saves — one save per affected solution, not per-edge.
    for (ExecutionSolution s : dirty) solutionRepo.save(s);
  }

  private void emit(String executionRunId, String type, String payload) {
    broadcaster.emit(
        "exec:" + executionRunId,
        new com.navneet.executespec.sse.RunEvent(
            type, executionRunId, null, payload, java.time.Instant.now()));
  }

  private String safeJson(Object o) {
    try {
      return objectMapper.writeValueAsString(o);
    } catch (Exception e) {
      return "[]";
    }
  }

  private String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() > max ? s.substring(0, max) + "..." : s;
  }

  private List<String> asList(JsonNode n) {
    if (!n.isArray()) return List.of();
    List<String> out = new ArrayList<>();
    n.forEach(x -> out.add(x.asText()));
    return out;
  }

  private List<Integer> asIntList(JsonNode n) {
    if (!n.isArray()) return List.of();
    List<Integer> out = new ArrayList<>();
    n.forEach(x -> out.add(x.asInt()));
    return out;
  }
}
