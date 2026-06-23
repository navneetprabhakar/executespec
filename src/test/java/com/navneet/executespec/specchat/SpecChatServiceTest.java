package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.RunKeyContextStore;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Unit-tests the {@link SpecChatService} per-round manual tool loop with a stubbed {@link
 * LlmRouter} and a recording {@link SpecChatService.Sink} — no Spring context, no real LLM. Pins
 * the two shapes that matter: a text-only turn streams one token then {@code done}; a tool-call
 * turn dispatches the bound tool, streams the {@code tool_call}/{@code tool_result}, then the final
 * narration.
 */
class SpecChatServiceTest {

  /** Captures every sink call so the test can assert the event stream. */
  private static final class RecordingSink implements SpecChatService.Sink {
    final List<String> tokens = new ArrayList<>();
    final List<String> toolCalls = new ArrayList<>();
    final List<String> toolResults = new ArrayList<>();
    int doneTurnCount = -1;

    @Override
    public void token(String delta) {
      tokens.add(delta);
    }

    @Override
    public void toolCall(String tool, String argsJson, String id) {
      toolCalls.add(tool);
    }

    @Override
    public void toolResult(String id, String resultJson) {
      toolResults.add(resultJson);
    }

    @Override
    public void done(int turnCount, RunCostTracker.Snapshot cost) {
      doneTurnCount = turnCount;
    }
  }

  private static AssistantMessage.ToolCall toolCall(String name, String args) {
    return new AssistantMessage.ToolCall("call_" + UUID.randomUUID(), "function", name, args);
  }

  private static ChatResponse textResponse(String text) {
    return new ChatResponse(
        List.of(new Generation(AssistantMessage.builder().content(text).build())));
  }

  private static ChatResponse toolResponse(AssistantMessage.ToolCall... calls) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
  }

  /**
   * A read-only fake tool that always returns {@code envelope} — mirrors the real callback shape.
   */
  private static ToolCallback fakeTool(String name, String envelope) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name(name)
            .description("stub")
            .inputSchema("{\"type\":\"object\"}")
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return def;
      }

      @Override
      public String call(String toolInput) {
        return envelope;
      }
    };
  }

  private static SpecChatService newService(LlmRouter router, List<ToolCallback> tools) {
    return newService(router, tools, List.of());
  }

  private static SpecChatService newService(
      LlmRouter router, List<ToolCallback> actionToolList, List<ToolCallback> projectToolList) {
    ModelResolver modelResolver = mock(ModelResolver.class);
    ResolvedModel model = mock(ResolvedModel.class);
    when(model.getModel()).thenReturn("test-model");
    when(modelResolver.resolve(any(), any(), any())).thenReturn(model);

    ProjectChatActionTools actionTools = mock(ProjectChatActionTools.class);
    when(actionTools.bind(any())).thenReturn(actionToolList);
    com.navneet.executespec.memory.chattools.ProjectChatTools projectTools =
        mock(com.navneet.executespec.memory.chattools.ProjectChatTools.class);
    when(projectTools.bind(any())).thenReturn(projectToolList);

    AgentProperties props = new AgentProperties();
    props.setWorkspace(
        System.getProperty("java.io.tmpdir")); // absolute — ExecutionContext requires it

    RunCostTracker costTracker = mock(RunCostTracker.class);
    when(costTracker.snapshot(any())).thenReturn(new RunCostTracker.Snapshot(10, 5, 0, 0, 0.01, 1));
    RunKeyContextStore keyContextStore = mock(RunKeyContextStore.class);

    com.navneet.executespec.security.ContextSanitizer contextSanitizer =
        mock(com.navneet.executespec.security.ContextSanitizer.class);
    when(contextSanitizer.sanitizeInput(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    WebSearchChatTools webSearchChatTools =
        new WebSearchChatTools(new com.fasterxml.jackson.databind.ObjectMapper(), contextSanitizer);

    return new SpecChatService(
        router,
        modelResolver,
        actionTools,
        webSearchChatTools,
        projectTools,
        props,
        costTracker,
        keyContextStore);
  }

  @Test
  void textOnlyTurn_streamsTokenThenDone_noToolCalls() {
    LlmRouter router = mock(LlmRouter.class);
    when(router.call(any())).thenReturn(textResponse("Here is the answer."));
    SpecChatService svc = newService(router, List.of());

    RecordingSink sink = new RecordingSink();
    svc.runTurn(
        sink,
        mock(AppUserDetails.class),
        "sess-1",
        null,
        "org-1",
        List.of(Map.of("role", "user", "content", "hi")),
        null);

    assertThat(sink.tokens).containsExactly("Here is the answer.");
    assertThat(sink.toolCalls).isEmpty();
    assertThat(sink.toolResults).isEmpty();
    assertThat(sink.doneTurnCount).isEqualTo(1);
  }

  @Test
  void toolCallTurn_dispatchesBoundTool_thenFinalNarration() {
    LlmRouter router = mock(LlmRouter.class);
    // Round 1: model calls list_recent_runs. Round 2: model narrates and stops.
    when(router.call(any()))
        .thenReturn(
            toolResponse(toolCall("list_recent_runs", "{\"limit\":5}")),
            textResponse("You have 5 runs."));
    ToolCallback listTool = fakeTool("list_recent_runs", "{\"ok\":true,\"count\":5}");
    SpecChatService svc = newService(router, List.of(listTool));

    RecordingSink sink = new RecordingSink();
    svc.runTurn(
        sink,
        mock(AppUserDetails.class),
        "sess-2",
        null,
        "org-1",
        List.of(Map.of("role", "user", "content", "list my runs")),
        "anthropic");

    assertThat(sink.toolCalls).containsExactly("list_recent_runs");
    assertThat(sink.toolResults).containsExactly("{\"ok\":true,\"count\":5}");
    assertThat(sink.tokens).contains("You have 5 runs.");
    assertThat(sink.doneTurnCount).isEqualTo(2);
  }

  @Test
  void unknownTool_yieldsErrorEnvelope_loopContinues() {
    LlmRouter router = mock(LlmRouter.class);
    when(router.call(any()))
        .thenReturn(
            toolResponse(toolCall("does_not_exist", "{}")), textResponse("Sorry, can't do that."));
    SpecChatService svc = newService(router, List.of()); // no tools bound

    RecordingSink sink = new RecordingSink();
    svc.runTurn(
        sink,
        mock(AppUserDetails.class),
        "sess-3",
        null,
        "org-1",
        List.of(Map.of("role", "user", "content", "do something")),
        null);

    assertThat(sink.toolResults).hasSize(1);
    assertThat(sink.toolResults.get(0)).contains("\"ok\":false").contains("unknown tool");
    assertThat(sink.doneTurnCount).isEqualTo(2);
  }

  @Test
  void linkedProject_bindsProjectCodeToolsForCommonChat() {
    LlmRouter router = mock(LlmRouter.class);
    when(router.call(any()))
        .thenReturn(
            toolResponse(toolCall("search_code", "{\"query\":\"auth flow\"}")),
            textResponse("Auth lives in the security package."));
    ToolCallback actionRunTool = fakeTool("list_recent_runs", "{\"ok\":true,\"count\":0}");
    ToolCallback duplicateProjectRunTool =
        fakeTool("list_recent_runs", "{\"ok\":false,\"message\":\"duplicate should be skipped\"}");
    ToolCallback searchCode =
        fakeTool("search_code", "{\"ok\":true,\"results\":[{\"file\":\"Auth.java\"}]}");
    SpecChatService svc =
        newService(router, List.of(actionRunTool), List.of(duplicateProjectRunTool, searchCode));

    AppUserDetails user = mock(AppUserDetails.class);
    var appUser = mock(com.navneet.executespec.auth.entity.AppUser.class);
    when(appUser.getId()).thenReturn("u1");
    when(user.getUser()).thenReturn(appUser);

    RecordingSink sink = new RecordingSink();
    svc.runTurn(
        sink,
        user,
        "sess-project",
        "proj-1",
        "org-1",
        List.of(Map.of("role", "user", "content", "where is auth implemented?")),
        null);

    assertThat(sink.toolCalls).containsExactly("search_code");
    assertThat(sink.toolResults)
        .containsExactly("{\"ok\":true,\"results\":[{\"file\":\"Auth.java\"}]}");
    assertThat(sink.tokens).contains("Auth lives in the security package.");
  }

  @Test
  void systemPromptTreatsCliLocalContextAsApprovedEvidence() {
    LlmRouter router = mock(LlmRouter.class);
    when(router.call(any())).thenReturn(textResponse("This folder is a TypeScript CLI."));
    SpecChatService svc = newService(router, List.of());

    RecordingSink sink = new RecordingSink();
    svc.runTurn(
        sink,
        mock(AppUserDetails.class),
        "sess-local-context",
        null,
        "org-1",
        List.of(
            Map.of(
                "role",
                "user",
                "content",
                "tell me about this folder\n"
                    + "EXECUTESPEC_LOCAL_CONTEXT: approved_read_only=true\n"
                    + "File tree:\n- package.json")),
        null);

    ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
    org.mockito.Mockito.verify(router).call(captor.capture());
    String system =
        captor.getValue().messages().stream()
            .filter(SystemMessage.class::isInstance)
            .map(m -> m.getText())
            .findFirst()
            .orElse("");

    assertThat(system)
        .contains("EXECUTESPEC_LOCAL_CONTEXT: approved_read_only=true")
        .contains("Use that snapshot as real local evidence")
        .contains("Do NOT answer \"I don't have access\"")
        .contains("does not grant write/shell authority");
  }
}
