package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.RunKeyContextStore;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.executioncontext.LlmPolicySnapshot;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.security.InputSanitizer;
import com.navneet.executespec.specchat.policy.ProjectChatPolicy;
import com.navneet.executespec.specchat.policy.ProjectChatPolicyResolver;
import com.navneet.executespec.specchat.policy.ProjectChatTurnGuardFixture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** No-provider-call corpus for hard spend, tool, and model-round ceilings. */
class SpecChatAdversarialBudgetTest {

  @Test
  void projectViewerCannotInitiateAProviderSpendingTurn() {
    LlmRouter router = mock(LlmRouter.class);
    ProjectChatPolicyResolver policyResolver = mock(ProjectChatPolicyResolver.class);
    AppUserDetails viewer = mock(AppUserDetails.class);
    ResponseStatusException denied =
        new ResponseStatusException(HttpStatus.FORBIDDEN, "viewer cannot spend");
    when(policyResolver.resolve(any())).thenThrow(denied);
    SpecChatService service =
        fixture(router, policyResolver, mock(RunCostTracker.class), List.of());

    assertThatThrownBy(
            () ->
                service.runTurn(
                    new RecordingSink(),
                    viewer,
                    "session-viewer",
                    "project-1",
                    "org-1",
                    List.of(Map.of("role", "user", "content", "inspect auth")),
                    "deepseek"))
        .isSameAs(denied);

    verifyNoInteractions(router);
  }

  @Test
  void tokenBudgetStopsBeforeTheNextProviderRound() {
    LlmRouter router = mock(LlmRouter.class);
    RunCostTracker costs = mock(RunCostTracker.class);
    RunCostTracker.Snapshot zero = snapshot(0, 0.0);
    RunCostTracker.Snapshot over = snapshot(ChatToolPolicy.MAX_TURN_TOKENS + 1, 0.0);
    when(costs.snapshot("session-token-budget")).thenReturn(zero, zero, over, over);
    RecordingSink sink = new RecordingSink();

    fixture(router, allowingPolicyResolver(), costs, List.of())
        .runTurn(
            sink,
            null,
            "session-token-budget",
            null,
            "org-1",
            List.of(Map.of("role", "user", "content", "summarise")),
            "deepseek");

    verifyNoInteractions(router);
    assertThat(sink.tokens).singleElement().asString().contains("hard token/cost budget");
    assertThat(sink.doneRounds).isZero();
  }

  @Test
  void costBudgetStopsBeforeTheNextProviderRound() {
    LlmRouter router = mock(LlmRouter.class);
    RunCostTracker costs = mock(RunCostTracker.class);
    RunCostTracker.Snapshot zero = snapshot(0, 0.0);
    RunCostTracker.Snapshot over = snapshot(1, ChatToolPolicy.MAX_TURN_COST_USD + 0.01);
    when(costs.snapshot("session-cost-budget")).thenReturn(zero, zero, over, over);
    RecordingSink sink = new RecordingSink();

    fixture(router, allowingPolicyResolver(), costs, List.of())
        .runTurn(
            sink,
            null,
            "session-cost-budget",
            null,
            "org-1",
            List.of(Map.of("role", "user", "content", "summarise")),
            "deepseek");

    verifyNoInteractions(router);
    assertThat(sink.tokens).singleElement().asString().contains("hard token/cost budget");
  }

  @Test
  void excessiveRequestedRoundsAreClampedAndEndWithOneToolFreeSynthesis() {
    LlmRouter router = mock(LlmRouter.class);
    AtomicInteger modelRound = new AtomicInteger();
    when(router.call(any()))
        .thenAnswer(
            ignored -> {
              int round = modelRound.incrementAndGet();
              return new ChatResponse(
                  List.of(
                      new Generation(
                          AssistantMessage.builder()
                              .content("")
                              .toolCalls(
                                  List.of(
                                      new AssistantMessage.ToolCall(
                                          "call-" + round,
                                          "function",
                                          "list_recent_runs",
                                          "{\"page\":" + round + "}")))
                              .build())));
            });
    AtomicInteger dispatches = new AtomicInteger();
    ToolCallback read = readTool(dispatches);
    RunCostTracker costs = mock(RunCostTracker.class);
    when(costs.snapshot("session-round-cap")).thenReturn(snapshot(0, 0.0));
    RecordingSink sink = new RecordingSink();

    fixture(router, allowingPolicyResolver(), costs, List.of(read))
        .runTurnBounded(
            sink,
            null,
            "session-round-cap",
            null,
            null,
            "org-1",
            List.of(Map.of("role", "user", "content", "list runs")),
            "deepseek",
            Set.of("list_recent_runs"),
            999,
            () -> {});

    ArgumentCaptor<LlmRequest> requests = ArgumentCaptor.forClass(LlmRequest.class);
    verify(router, org.mockito.Mockito.times(ChatToolPolicy.MAX_MODEL_ROUNDS + 1))
        .call(requests.capture());
    assertThat(requests.getAllValues().subList(0, ChatToolPolicy.MAX_MODEL_ROUNDS))
        .allSatisfy(request -> assertThat(request.toolsOverride()).isNotEmpty());
    assertThat(requests.getAllValues().get(ChatToolPolicy.MAX_MODEL_ROUNDS).toolsOverride())
        .isEmpty();
    assertThat(dispatches).hasValue(ChatToolPolicy.MAX_MODEL_ROUNDS);
    assertThat(sink.doneRounds).isEqualTo(ChatToolPolicy.MAX_MODEL_ROUNDS + 1);
  }

  @Test
  void toolCallBudgetIsIndependentFromModelRoundBudget() {
    LlmRouter router = mock(LlmRouter.class);
    List<AssistantMessage.ToolCall> calls = new ArrayList<>();
    for (int i = 0; i < ChatToolPolicy.MAX_TOOL_CALLS + 3; i++) {
      calls.add(
          new AssistantMessage.ToolCall(
              "call-" + i, "function", "list_recent_runs", "{\"limit\":" + i + "}"));
    }
    when(router.call(any()))
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        AssistantMessage.builder().content("").toolCalls(calls).build()))),
            textResponse("done"));
    AtomicInteger dispatches = new AtomicInteger();
    RunCostTracker costs = mock(RunCostTracker.class);
    when(costs.snapshot("session-tool-cap")).thenReturn(snapshot(0, 0.0));
    RecordingSink sink = new RecordingSink();

    fixture(router, allowingPolicyResolver(), costs, List.of(readTool(dispatches)))
        .runTurn(
            sink,
            null,
            "session-tool-cap",
            null,
            "org-1",
            List.of(Map.of("role", "user", "content", "list all runs")),
            "deepseek");

    assertThat(dispatches).hasValue(ChatToolPolicy.MAX_TOOL_CALLS);
    assertThat(sink.toolResults)
        .filteredOn(result -> result.contains("tool budget exhausted"))
        .hasSize(3);
    verify(router, org.mockito.Mockito.times(2)).call(any());
  }

  private static SpecChatService fixture(
      LlmRouter router,
      ProjectChatPolicyResolver policyResolver,
      RunCostTracker costs,
      List<ToolCallback> actionCallbacks) {
    ModelResolver resolver = mock(ModelResolver.class);
    when(resolver.resolve(any(), any(), any()))
        .thenReturn(
            ResolvedModel.builder()
                .requestedProvider(LlmProvider.DEEPSEEK)
                .actualProvider(LlmProvider.DEEPSEEK)
                .model("deepseek-test")
                .agentTier(AgentTier.LIGHTWEIGHT)
                .fallback(false)
                .build());
    ProjectChatActionTools actions = mock(ProjectChatActionTools.class);
    when(actions.bind(any())).thenReturn(actionCallbacks);
    WebSearchChatTools web = mock(WebSearchChatTools.class);
    when(web.bind()).thenReturn(List.of());
    SubagentChatTools subagents = mock(SubagentChatTools.class);
    com.navneet.executespec.memory.chattools.ProjectChatTools projectTools =
        mock(com.navneet.executespec.memory.chattools.ProjectChatTools.class);
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(System.getProperty("java.io.tmpdir"));

    return new SpecChatService(
        router,
        resolver,
        actions,
        web,
        subagents,
        projectTools,
        properties,
        costs,
        mock(RunKeyContextStore.class),
        new InputSanitizer(),
        new ContextSanitizer(),
        policyResolver);
  }

  private static ProjectChatPolicyResolver allowingPolicyResolver() {
    ProjectChatPolicyResolver resolver = mock(ProjectChatPolicyResolver.class);
    ProjectChatPolicy policy =
        new ProjectChatPolicy(
            "user-1",
            "org-1",
            "org-1",
            null,
            ProjectChatPolicy.EffectiveProjectRole.UNSCOPED_USER,
            Set.of(ChatToolPolicy.Capability.values()),
            Set.of("deepseek"),
            Map.of("deepseek", List.of("deepseek-test")),
            LlmPolicySnapshot.KeySourceMode.SYSTEM,
            Map.of("deepseek", LlmPolicySnapshot.CredentialSource.PLATFORM),
            new ProjectChatPolicy.TurnBudgets(20_000, BigDecimal.ONE, 8, 20, 6),
            ProjectChatPolicy.Confirmation.none(),
            Map.of(),
            new ProjectChatPolicy.AuditIdentity(
                null, null, "correlation-adversarial", "policy-adversarial"),
            new LlmExecutionReference("llmctx-adversarial", LlmExecutionScope.customer("org-1")));
    ProjectChatPolicyResolver.TurnGuard guard = ProjectChatTurnGuardFixture.allowing(policy);
    when(resolver.resolve(any())).thenReturn(policy);
    when(resolver.newGuard(any(), any())).thenReturn(guard);
    return resolver;
  }

  private static ToolCallback readTool(AtomicInteger dispatches) {
    ToolDefinition definition =
        DefaultToolDefinition.builder()
            .name("list_recent_runs")
            .description("test")
            .inputSchema("{\"type\":\"object\"}")
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String input) {
        dispatches.incrementAndGet();
        return "{\"ok\":true}";
      }
    };
  }

  private static ChatResponse textResponse(String text) {
    return new ChatResponse(
        List.of(new Generation(AssistantMessage.builder().content(text).build())));
  }

  private static RunCostTracker.Snapshot snapshot(long tokens, double cost) {
    return new RunCostTracker.Snapshot(tokens, 0, 0, 0, cost, 0, 0);
  }

  private static final class RecordingSink implements SpecChatService.Sink {
    private final List<String> tokens = new ArrayList<>();
    private final List<String> toolResults = new ArrayList<>();
    private int doneRounds = -1;

    @Override
    public void token(String delta) {
      tokens.add(delta);
    }

    @Override
    public void toolCall(String tool, String argsJson, String id) {}

    @Override
    public void toolResult(String id, String resultJson) {
      toolResults.add(resultJson);
    }

    @Override
    public void done(int turnCount, RunCostTracker.Snapshot cost) {
      doneRounds = turnCount;
    }
  }
}
