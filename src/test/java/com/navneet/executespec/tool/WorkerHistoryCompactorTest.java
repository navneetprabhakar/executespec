package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Pins for {@link WorkerHistoryCompactor}. Its load-bearing properties: never touch the LLM when
 * the flag is off / under threshold / too short; align the cut to an {@link AssistantMessage}
 * boundary so a tool_use/tool_result pair is never split (a wrong cut 400s the provider); keep the
 * system prompt + fold the task into the summary; fail-safe back to the full history on any error.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkerHistoryCompactor")
class WorkerHistoryCompactorTest {

  @Mock private LlmRouter router;
  @Mock private ModelResolver modelResolver;
  @Mock private PromptAssembler promptAssembler;
  @Mock private FeatureFlagService featureFlagService;

  private WorkerHistoryCompactor compactor;

  private static final ExecutionContext CTX =
      new ExecutionContext(
          "run-xyz", "task-1", Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath());

  private static final long OVER = WorkerHistoryCompactor.TRIGGER_INPUT_TOKENS + 1;

  @BeforeEach
  void setUp() {
    compactor =
        new WorkerHistoryCompactor(router, modelResolver, promptAssembler, featureFlagService);
  }

  /**
   * A long, well-formed history: system, task, then 6 (assistant-toolcalls, tool-response) pairs.
   */
  private static List<Message> longHistory() {
    List<Message> h = new ArrayList<>();
    h.add(new SystemMessage("system prompt — the cache prefix"));
    h.add(new UserMessage("the original task: build a /health endpoint"));
    for (int i = 0; i < 6; i++) {
      h.add(
          AssistantMessage.builder()
              .content("turn " + i + " thinking")
              .toolCalls(
                  List.of(new AssistantMessage.ToolCall("id" + i, "function", "readFile", "{}")))
              .build());
      h.add(
          ToolResponseMessage.builder()
              .responses(
                  List.of(
                      new ToolResponseMessage.ToolResponse("id" + i, "readFile", "file body " + i)))
              .build());
    }
    return h; // 2 + 12 = 14 messages
  }

  private void stubSummaryLlm(LlmProvider actual, String model) {
    when(modelResolver.resolve(anyString(), any(LlmProvider.class)))
        .thenReturn(ResolvedModel.builder().actualProvider(actual).model(model).build());
    when(promptAssembler.assemble(anyString(), any())).thenReturn("summariser prompt");
    ChatResponse resp = Mockito.mock(ChatResponse.class);
    Generation gen = Mockito.mock(Generation.class);
    AssistantMessage msg = Mockito.mock(AssistantMessage.class);
    Mockito.when(resp.getResult()).thenReturn(gen);
    Mockito.when(gen.getOutput()).thenReturn(msg);
    Mockito.when(msg.getText()).thenReturn("Files: created Health.java. Open: none.");
    when(router.call(any(LlmRequest.class))).thenReturn(resp);
  }

  @Test
  @DisplayName("flag OFF → same list reference, no LLM")
  void skip_whenFlagOff() {
    when(featureFlagService.isEnabled(WorkerHistoryCompactor.FLAG_ENABLED)).thenReturn(false);
    List<Message> h = longHistory();
    List<Message> out = compactor.compactIfNeeded(h, "anthropic", CTX, OVER, "goal");
    assertThat(out).isSameAs(h);
    verify(router, never()).call(any());
  }

  @Test
  @DisplayName("input under threshold → same list reference, no LLM")
  void skip_whenUnderThreshold() {
    when(featureFlagService.isEnabled(WorkerHistoryCompactor.FLAG_ENABLED)).thenReturn(true);
    List<Message> h = longHistory();
    List<Message> out =
        compactor.compactIfNeeded(
            h, "anthropic", CTX, WorkerHistoryCompactor.TRIGGER_INPUT_TOKENS - 1, "goal");
    assertThat(out).isSameAs(h);
    verify(router, never()).call(any());
  }

  @Test
  @DisplayName("history too short to compact → same list reference, no LLM")
  void skip_whenTooShort() {
    when(featureFlagService.isEnabled(WorkerHistoryCompactor.FLAG_ENABLED)).thenReturn(true);
    List<Message> h = new ArrayList<>();
    h.add(new SystemMessage("sys"));
    h.add(new UserMessage("task"));
    h.add(new AssistantMessage("only one turn"));
    List<Message> out = compactor.compactIfNeeded(h, "anthropic", CTX, OVER, "goal");
    assertThat(out).isSameAs(h);
    verify(router, never()).call(any());
  }

  @Test
  @DisplayName("happy path → system kept, task folded into one summary user msg, cut on Assistant")
  void compacts_andRebuildsCleanly() {
    when(featureFlagService.isEnabled(WorkerHistoryCompactor.FLAG_ENABLED)).thenReturn(true);
    stubSummaryLlm(LlmProvider.ANTHROPIC, "claude-haiku-4-5-20251001");

    List<Message> h =
        longHistory(); // 14 messages, KEEP_RECENT=8 → targetCut=6 (an AssistantMessage)
    List<Message> out = compactor.compactIfNeeded(h, "anthropic", CTX, OVER, "goal");

    assertThat(out).isNotSameAs(h);
    // [0] system kept verbatim.
    assertThat(out.get(0)).isInstanceOf(SystemMessage.class);
    // [1] the folded user message: original task text + the compacted progress note.
    assertThat(out.get(1)).isInstanceOf(UserMessage.class);
    String folded = ((UserMessage) out.get(1)).getText();
    assertThat(folded).contains("build a /health endpoint"); // task preserved
    assertThat(folded).contains("PROGRESS SO FAR"); // summary folded in
    assertThat(folded).contains("created Health.java");
    // [2] must be an AssistantMessage — the cut aligned to a clean turn boundary so the kept
    // suffix begins a fresh model turn (no dangling tool_response).
    assertThat(out.get(2)).isInstanceOf(AssistantMessage.class);
    // The last KEEP_RECENT messages are preserved verbatim.
    assertThat(out.get(out.size() - 1)).isSameAs(h.get(h.size() - 1));
    // Net shrink.
    assertThat(out.size()).isLessThan(h.size());
    verify(router).call(any(LlmRequest.class));
  }

  @Test
  @DisplayName("never splits a tool pair → message after the summary is always an AssistantMessage")
  void cutAlignsToAssistantBoundary() {
    when(featureFlagService.isEnabled(WorkerHistoryCompactor.FLAG_ENABLED)).thenReturn(true);
    stubSummaryLlm(LlmProvider.ANTHROPIC, "claude-haiku-4-5-20251001");

    // 16 messages: sys, task, then 7 (assistant, tool-response) pairs. KEEP_RECENT=8 →
    // targetCut = 16-8 = 8. history[8] is an AssistantMessage (pairs start at even indices
    // 2,4,6,8),
    // so the cut lands cleanly and the kept suffix never starts on a ToolResponseMessage.
    List<Message> h = new ArrayList<>();
    h.add(new SystemMessage("sys"));
    h.add(new UserMessage("task"));
    for (int i = 0; i < 7; i++) {
      h.add(
          AssistantMessage.builder()
              .content("t" + i)
              .toolCalls(
                  List.of(new AssistantMessage.ToolCall("id" + i, "function", "readFile", "{}")))
              .build());
      h.add(
          ToolResponseMessage.builder()
              .responses(
                  List.of(new ToolResponseMessage.ToolResponse("id" + i, "readFile", "r" + i)))
              .build());
    }
    List<Message> out = compactor.compactIfNeeded(h, "anthropic", CTX, OVER, "goal");

    assertThat(out.get(2)).isInstanceOf(AssistantMessage.class);
    // And the message right after that Assistant turn is its ToolResponse — pair intact.
    assertThat(out.get(3)).isInstanceOf(ToolResponseMessage.class);
  }

  @Test
  @DisplayName("routes the summariser to the RUN's provider (OpenAI run → OPENAI)")
  void routesToRunProvider() {
    when(featureFlagService.isEnabled(WorkerHistoryCompactor.FLAG_ENABLED)).thenReturn(true);
    stubSummaryLlm(LlmProvider.OPENAI, "gpt-5.4-mini");

    compactor.compactIfNeeded(longHistory(), "openai", CTX, OVER, "goal");

    verify(modelResolver).resolve(WorkerHistoryCompactor.AGENT_NAME, LlmProvider.OPENAI);
  }

  @Test
  @DisplayName("LLM throws → same list reference (fail-safe: worker keeps its full transcript)")
  void failSafe_whenLlmThrows() {
    when(featureFlagService.isEnabled(WorkerHistoryCompactor.FLAG_ENABLED)).thenReturn(true);
    when(modelResolver.resolve(anyString(), any(LlmProvider.class)))
        .thenReturn(
            ResolvedModel.builder()
                .actualProvider(LlmProvider.ANTHROPIC)
                .model("claude-haiku-4-5-20251001")
                .build());
    when(promptAssembler.assemble(anyString(), any())).thenReturn("summariser prompt");
    when(router.call(any(LlmRequest.class))).thenThrow(new RuntimeException("boom"));

    List<Message> h = longHistory();
    List<Message> out = compactor.compactIfNeeded(h, "anthropic", CTX, OVER, "goal");
    assertThat(out).isSameAs(h);
  }
}
