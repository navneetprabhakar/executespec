package com.navneet.executespec.continuationfixture;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.execution.model.GoalContinuationAdvisorRequest;
import com.navneet.executespec.execution.model.GoalContinuationPolicyDecision;
import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.GoalDonenessState;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.execution.service.GoalContinuationAdvisor;
import com.navneet.executespec.harness.TaskStatusPersistenceService;
import com.navneet.executespec.integration.RunLifecycleService;
import com.navneet.executespec.router.ModelProviderCapabilitySignal;
import com.navneet.executespec.router.ModelProviderScore;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Creates real run/task rows and uses the production continuation advisor, durable evidence writer,
 * SSE envelope, and normal approval URL without dispatching an LLM or worker.
 */
@Profile({"local", "dev"})
@Service
@RequiredArgsConstructor
public class ContinuationFixtureService {

  static final String SOURCE_TYPE = "CONTINUATION_FIXTURE";
  private static final String TASK_SHORT_ID = "fixture";
  private static final String HAIKU = "claude-haiku-4-5-20251001";
  private static final String SONNET = "claude-sonnet-5";
  private static final String GPT = "gpt-5.4";

  private final PipelineRunRepository runRepository;
  private final AgentTaskRepository taskRepository;
  private final GoalContinuationAdvisor advisor;
  private final TaskStatusPersistenceService taskPersistence;
  private final SseBroadcaster broadcaster;
  private final ObjectMapper objectMapper;

  public record FixtureResult(
      String runId,
      String taskId,
      ContinuationFixtureScenario scenario,
      String decision,
      String approvalRequiredReason,
      boolean providerCallsMade) {}

  @Transactional
  public FixtureResult create(ContinuationFixtureScenario scenario, AppUserDetails actor) {
    String runId = UUID.randomUUID().toString();
    String taskId = runId.substring(0, 8) + "_" + TASK_SHORT_ID;
    FixtureSpec spec = FixtureSpec.forScenario(scenario, runId);
    GoalDonenessDecision goal = goalFor(spec);
    GoalContinuationPolicyDecision advised = advisor.advise(advisorRequest(spec, goal, runId));
    GoalContinuationPolicyDecision decision = enrichPolicy(advised, spec);
    assertExpectedDecision(scenario, decision);

    Instant now = Instant.now();
    String tenantId =
        actor.getUser().getOrgId() == null || actor.getUser().getOrgId().isBlank()
            ? "default"
            : actor.getUser().getOrgId();
    PipelineRun run =
        PipelineRun.builder()
            .runId(runId)
            .userRequest("Provider-free continuation fixture: " + scenario)
            .runTitle("Continuation fixture " + scenario)
            .runSummary("Deterministic local/dev continuation evidence; no provider was called.")
            .status("PARTIAL")
            .statusReason("NO_REVIEW_RESULT")
            .harnessVersion("v2")
            .runMode("BUILD")
            .executionMode("PRODUCTION")
            .aiProvider(spec.currentProvider().toUpperCase())
            .requestedProvider(spec.currentProvider().toUpperCase())
            .requestedModel(spec.currentModel())
            .routePinned(spec.routePinned())
            .sourceType(SOURCE_TYPE)
            .sourceId(scenario.name())
            .createdByUserId(actor.getUser().getId())
            .orgId(actor.getUser().getOrgId())
            .billingOrgId(actor.getUser().getOrgId())
            .tenantId(tenantId)
            .startedAt(now)
            .completedAt(now)
            .totalInputTokens(0)
            .totalOutputTokens(0)
            .actualCostUsd(0.0)
            .build();
    AgentTask task =
        AgentTask.builder()
            .taskId(taskId)
            .runId(runId)
            .title("Continuation fixture " + scenario)
            .complexity("LOW")
            .status(
                decision.decision() == RunContinuationDecision.STOP_BLOCKED ? "BLOCKED" : "PARTIAL")
            .taskJson("{\"id\":\"fixture\",\"objective\":\"provider-free continuation fixture\"}")
            .provider(spec.currentProvider())
            .model(spec.currentModel())
            .assignedModel(spec.currentModel())
            .startedAt(now)
            .completedAt(now)
            .durationMs(0L)
            .inputTokens(0)
            .outputTokens(0)
            .tenantId(tenantId)
            .recoveryAttempt(0)
            .build();
    runRepository.saveAndFlush(run);
    taskRepository.saveAndFlush(task);

    String goalJson = write(goal);
    String continuationJson = writeContinuation(decision, spec);
    taskPersistence.persistGoalDecisionJson(runId, TASK_SHORT_ID, goalJson, continuationJson);
    emitDecisionEvents(runId, taskId, goal, decision, continuationJson);
    if (decision.decision() != RunContinuationDecision.STOP_BLOCKED) {
      appendSimulatedAction(runId, taskId, decision);
    }
    return new FixtureResult(
        runId,
        taskId,
        scenario,
        decision.decision().name(),
        approvalRequiredReason(decision, spec),
        false);
  }

  @Transactional
  public RunLifecycleService.ContinuationApproval approveProviderFreeFixture(
      String runId, String taskId) {
    PipelineRun run =
        runRepository
            .findById(runId)
            .filter(row -> SOURCE_TYPE.equals(row.getSourceType()))
            .orElseThrow(() -> new IllegalArgumentException("Continuation fixture run not found"));
    AgentTask task =
        taskRepository
            .findByRunIdAndTaskId(runId, normalizedTaskId(runId, taskId))
            .orElseThrow(() -> new IllegalArgumentException("Continuation fixture task not found"));
    try {
      var root = objectMapper.readTree(task.getContinuationDecisionJson());
      if (!"STOP_BLOCKED".equals(root.path("decision").asText())) {
        throw new IllegalStateException("Fixture continuation is not approval-blocked.");
      }
      String reason = root.path("approvalRequiredReason").asText();
      if ("ROUTE_PINNED".equals(reason)) {
        throw new IllegalStateException("Route-pinned continuation cannot be approved.");
      }
      var candidate = root.path("selectedCandidate");
      String provider = candidate.path("provider").asText();
      String model = candidate.path("model").asText();
      RunContinuationDecision action =
          RunContinuationDecision.valueOf(root.path("policy").path("proposedAction").asText());
      if (provider.isBlank() || model.isBlank()) {
        throw new IllegalStateException("Fixture continuation candidate is missing.");
      }
      appendAction(
          runId,
          shortTaskId(task.getTaskId()),
          action,
          provider,
          model,
          "APPROVED",
          "operator approved provider-free fixture");
      appendAction(
          runId,
          shortTaskId(task.getTaskId()),
          action,
          provider,
          model,
          "SIMULATED",
          "provider-free fixture completed without worker or LLM dispatch");
      task.setStatus("COMPLETE");
      task.setCompletedAt(Instant.now());
      taskRepository.saveAndFlush(task);
      run.setStatus("COMPLETE");
      run.setStatusReason("CLEAN_COMPLETION");
      run.setCompletedAt(Instant.now());
      runRepository.saveAndFlush(run);
      return new RunLifecycleService.ContinuationApproval(
          provider,
          model,
          action,
          root.path("policy")
              .path("requireBenchmarkPriorForAutomaticContinuation")
              .asBoolean(false),
          false);
    } catch (IllegalStateException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Fixture continuation proposal is corrupted.", exception);
    }
  }

  public boolean isFixtureRun(String runId) {
    return runId != null
        && runRepository
            .findById(runId)
            .map(row -> SOURCE_TYPE.equals(row.getSourceType()))
            .orElse(false);
  }

  private GoalContinuationAdvisorRequest advisorRequest(
      FixtureSpec spec, GoalDonenessDecision goal, String runId) {
    return GoalContinuationAdvisorRequest.builder()
        .goalDecision(goal)
        .runId(runId)
        .taskId(TASK_SHORT_ID)
        .currentProvider(spec.currentProvider())
        .currentModel(spec.currentModel())
        .taskCategory(spec.taskCategory())
        .requiredQualityThreshold(70.0)
        .providerAllowList(spec.allowedProviders())
        .modelAllowList(spec.allowedModels())
        .externalSignals(spec.signals())
        .providerAvailability(spec.providerAvailability())
        .providerPreferenceOrder(spec.allowedProviders())
        .qualityInsufficient(spec.qualityInsufficient())
        .providerBehaviorPoor(spec.providerBehaviorPoor())
        .taskSimplerThanExpected(spec.taskSimplerThanExpected())
        .routePinned(spec.routePinned())
        .expensiveContinuationRequiresConfirmation(spec.confirmationRequired())
        .benchmarkQualityPriorRequiredForAutomaticContinuation(spec.benchmarkRequired())
        .maxProviderSwitches(1)
        .maxModelStepUps(1)
        .maxModelStepDowns(1)
        .build();
  }

  private GoalContinuationPolicyDecision enrichPolicy(
      GoalContinuationPolicyDecision advised, FixtureSpec spec) {
    Map<String, Object> policy = new LinkedHashMap<>(advised.policy());
    policy.put("source", "CONTINUATION_FIXTURE");
    policy.put("taskCategory", spec.taskCategory());
    policy.put("requiredQualityThreshold", 70.0);
    policy.put("providerBehaviorPoor", spec.providerBehaviorPoor());
    policy.put("taskSimplerThanExpected", spec.taskSimplerThanExpected());
    policy.put("requireConfirmationBeforeExpensiveContinuation", spec.confirmationRequired());
    policy.put("requireBenchmarkPriorForAutomaticContinuation", spec.benchmarkRequired());
    policy.put("routePinned", spec.routePinned());
    policy.put("providerCallsMade", false);
    return new GoalContinuationPolicyDecision(
        advised.decision(), advised.selectedCandidate(), advised.reasons(), policy);
  }

  private void emitDecisionEvents(
      String runId,
      String taskId,
      GoalDonenessDecision goal,
      GoalContinuationPolicyDecision decision,
      String continuationJson) {
    broadcaster.emit(
        runId,
        RunEvent.goalDecision(
            runId,
            taskId,
            goal.state() + " -> " + goal.recommendedDecision(),
            objectMapper.convertValue(goal, Map.class)));
    broadcaster.emit(
        runId,
        RunEvent.continuationDecision(
            runId,
            taskId,
            "Continuation fixture: " + decision.decision(),
            readMap(continuationJson)));
  }

  private void appendSimulatedAction(
      String runId, String taskId, GoalContinuationPolicyDecision decision) {
    ModelProviderScore selected = decision.selectedCandidate().orElseThrow();
    appendAction(
        runId,
        shortTaskId(taskId),
        decision.decision(),
        selected.provider(),
        selected.model(),
        "SIMULATED",
        "provider-free fixture selected the action; no worker or LLM was dispatched");
  }

  private void appendAction(
      String runId,
      String taskShortId,
      RunContinuationDecision action,
      String provider,
      String model,
      String status,
      String reason) {
    Map<String, Object> data =
        Map.of(
            "status",
            status,
            "decision",
            action.name(),
            "provider",
            provider,
            "model",
            model,
            "reason",
            reason,
            "limitName",
            "fixture",
            "usedLimit",
            0,
            "maxLimit",
            0);
    taskPersistence.appendContinuationAction(runId, taskShortId, write(data), 0);
    broadcaster.emit(
        runId,
        RunEvent.continuationAction(
            runId,
            runId.substring(0, 8) + "_" + taskShortId,
            reason,
            action.name(),
            provider,
            model,
            reason,
            1,
            1,
            "fixture",
            0,
            0,
            status));
  }

  private String writeContinuation(GoalContinuationPolicyDecision decision, FixtureSpec spec) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("decision", decision.decision().name());
    decision
        .selectedCandidate()
        .ifPresent(candidate -> out.put("selectedCandidate", candidateMap(candidate)));
    String approvalReason = approvalRequiredReason(decision, spec);
    if (approvalReason != null) out.put("approvalRequiredReason", approvalReason);
    out.put("reasons", decision.reasons());
    out.put("policy", decision.policy());
    return write(out);
  }

  private static Map<String, Object> candidateMap(ModelProviderScore candidate) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("provider", candidate.provider());
    out.put("model", candidate.model());
    out.put("taskCategory", candidate.taskCategory());
    out.put("qualityScore", candidate.qualityScore());
    out.put("requiredQualityThreshold", candidate.requiredQualityThreshold());
    out.put("costPerMillionTokens", candidate.costPerMillionTokens());
    out.put("latencyP50Ms", candidate.latencyP50Ms());
    out.put("latencyP95Ms", candidate.latencyP95Ms());
    out.put("benchmarkQualityPriorAvailable", candidate.benchmarkQualityPriorAvailable());
    out.put("reasons", candidate.reasons());
    return out;
  }

  private static String approvalRequiredReason(
      GoalContinuationPolicyDecision decision, FixtureSpec spec) {
    if (decision.decision() != RunContinuationDecision.STOP_BLOCKED) return null;
    if (spec.routePinned()) return "ROUTE_PINNED";
    if (spec.benchmarkRequired()
        && decision.selectedCandidate().stream()
            .noneMatch(ModelProviderScore::benchmarkQualityPriorAvailable)) {
      return "BENCHMARK_PRIOR_REQUIRED";
    }
    if (spec.confirmationRequired()) {
      return "EXPENSIVE_CONTINUATION_CONFIRMATION_REQUIRED";
    }
    return "APPROVAL_REQUIRED";
  }

  private static GoalDonenessDecision goalFor(FixtureSpec spec) {
    return new GoalDonenessDecision(
        GoalDonenessState.UNCERTAIN,
        RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
        "deterministic provider-free continuation fixture",
        "Exercise the selected continuation path without provider spend.",
        "fixture goal",
        List.of("run and task rows persisted"),
        List.of("provider dispatch intentionally omitted"),
        List.of("synthetic deterministic quality signals"),
        List.of(),
        "",
        0,
        0,
        0,
        0,
        0,
        0,
        0.0,
        0L,
        false,
        spec.qualityInsufficient() || spec.providerBehaviorPoor());
  }

  private static void assertExpectedDecision(
      ContinuationFixtureScenario scenario, GoalContinuationPolicyDecision decision) {
    RunContinuationDecision expected =
        switch (scenario) {
          case STEP_UP_QUALITY -> RunContinuationDecision.STEP_UP_MODEL;
          case STEP_DOWN_SIMPLE -> RunContinuationDecision.STEP_DOWN_MODEL;
          case CHANGE_PROVIDER_BREAKDOWN -> RunContinuationDecision.CHANGE_PROVIDER;
          case BLOCK_BENCHMARK, BLOCK_CONFIRMATION, ROUTE_PINNED ->
              RunContinuationDecision.STOP_BLOCKED;
        };
    if (decision.decision() != expected) {
      throw new IllegalStateException(
          "Fixture scenario "
              + scenario
              + " expected "
              + expected
              + " but advisor returned "
              + decision.decision());
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Could not serialize continuation fixture evidence", exception);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readMap(String json) {
    try {
      return objectMapper.readValue(json, Map.class);
    } catch (Exception exception) {
      throw new IllegalStateException("Could not read continuation fixture evidence", exception);
    }
  }

  private static String normalizedTaskId(String runId, String taskId) {
    String prefix = runId.substring(0, 8) + "_";
    return taskId.startsWith(prefix) ? taskId : prefix + taskId;
  }

  private static String shortTaskId(String taskId) {
    int separator = taskId.indexOf('_');
    return separator < 0 ? taskId : taskId.substring(separator + 1);
  }

  private record FixtureSpec(
      String currentProvider,
      String currentModel,
      List<String> allowedProviders,
      List<String> allowedModels,
      Map<String, Boolean> providerAvailability,
      List<ModelProviderCapabilitySignal> signals,
      String taskCategory,
      boolean qualityInsufficient,
      boolean providerBehaviorPoor,
      boolean taskSimplerThanExpected,
      boolean benchmarkRequired,
      boolean confirmationRequired,
      boolean routePinned) {

    static FixtureSpec forScenario(ContinuationFixtureScenario scenario, String runId) {
      String category = "CONTINUATION_FIXTURE_" + runId;
      boolean stepDown = scenario == ContinuationFixtureScenario.STEP_DOWN_SIMPLE;
      boolean providerChange = scenario == ContinuationFixtureScenario.CHANGE_PROVIDER_BREAKDOWN;
      String currentModel = stepDown || providerChange ? SONNET : HAIKU;
      List<String> providers =
          providerChange ? List.of("anthropic", "openai") : List.of("anthropic");
      List<String> models = providerChange ? List.of(SONNET, GPT) : List.of(HAIKU, SONNET);
      boolean benchmarkBlocked = scenario == ContinuationFixtureScenario.BLOCK_BENCHMARK;
      List<ModelProviderCapabilitySignal> signals =
          providerChange
              ? List.of(
                  signal("anthropic", SONNET, category, 75.0, true),
                  signal("openai", GPT, category, 96.0, true))
              : stepDown
                  ? List.of(
                      signal("anthropic", SONNET, category, 96.0, true),
                      signal("anthropic", HAIKU, category, 82.0, true))
                  : List.of(
                      signal("anthropic", HAIKU, category, 72.0, true),
                      signal("anthropic", SONNET, category, 96.0, !benchmarkBlocked));
      return new FixtureSpec(
          "anthropic",
          currentModel,
          providers,
          models,
          providers.stream().collect(java.util.stream.Collectors.toMap(p -> p, p -> true)),
          signals,
          category,
          !stepDown,
          providerChange,
          stepDown,
          benchmarkBlocked,
          scenario == ContinuationFixtureScenario.BLOCK_CONFIRMATION,
          scenario == ContinuationFixtureScenario.ROUTE_PINNED);
    }

    private static ModelProviderCapabilitySignal signal(
        String provider, String model, String category, double quality, boolean benchmark) {
      return ModelProviderCapabilitySignal.builder()
          .provider(provider)
          .model(model)
          .taskCategory(category)
          .benchmarkQualityScore(benchmark ? quality : null)
          .validationPassRateScore(benchmark ? null : quality)
          .inputCostPerMillionTokens(1.0)
          .outputCostPerMillionTokens(
              HAIKU.equals(model) ? 1.0 : SONNET.equals(model) ? 10.0 : 12.0)
          .latencyP50Ms(100)
          .latencyP95Ms(200)
          .contextWindowTokens(200_000)
          .providerAvailable(true)
          .requiredQualityThreshold(70.0)
          .fallbackQualityScore(quality)
          .reasons(List.of("deterministic provider-free continuation fixture signal"))
          .build();
    }
  }
}
