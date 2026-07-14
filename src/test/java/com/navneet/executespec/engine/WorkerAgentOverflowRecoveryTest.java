package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** task 9 — context-overflow recovery helpers (classification + deterministic hard-trim). */
class WorkerAgentOverflowRecoveryTest {

  @Test
  void classifiesProviderContextLengthErrors() {
    assertThat(
            WorkerAgent.isContextLengthError(
                new RuntimeException("400: prompt is too long: 210000 tokens > 200000 maximum")))
        .isTrue();
    assertThat(
            WorkerAgent.isContextLengthError(
                new RuntimeException(
                    "This model's maximum context length is 131072 tokens"
                        + " (context_length_exceeded)")))
        .isTrue();
    assertThat(
            WorkerAgent.isContextLengthError(
                new RuntimeException("outer", new RuntimeException("input is too long"))))
        .isTrue();
    assertThat(WorkerAgent.isContextLengthError(new RuntimeException("429 rate limited")))
        .isFalse();
    assertThat(WorkerAgent.isContextLengthError(new RuntimeException((String) null))).isFalse();
  }

  @Test
  void callSignatureIsTextSafe() {
    String signature =
        WorkerAgent.callSignature(
            new AssistantMessage.ToolCall("id", "function", "readFile", "{\"path\":\"x\"}"));
    assertThat(signature).doesNotContain("\u0000").isEqualTo("readFile|{\"path\":\"x\"}");
  }

  @Test
  void hardTrimKeepsSystemTaskAndRecentTailAtAssistantBoundary() {
    List<Message> h = new ArrayList<>();
    h.add(new SystemMessage("sys"));
    h.add(new UserMessage("the task"));
    for (int i = 0; i < 20; i++) {
      h.add(
          AssistantMessage.builder()
              .content("turn " + i)
              .toolCalls(
                  List.of(new AssistantMessage.ToolCall("id" + i, "function", "readFile", "{}")))
              .build());
      h.add(
          ToolResponseMessage.builder()
              .responses(List.of(new ToolResponseMessage.ToolResponse("id" + i, "readFile", "ok")))
              .build());
    }
    List<Message> out = WorkerAgent.hardTrimHistory(h);
    assertThat(out).isNotNull();
    assertThat(out.size()).isLessThan(h.size());
    assertThat(out.get(0)).isInstanceOf(SystemMessage.class);
    assertThat(out.get(1)).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) out.get(1)).getText()).contains("the task").contains("DROPPED");
    // first retained turn must be an AssistantMessage so tool pairs stay intact
    assertThat(out.get(2)).isInstanceOf(AssistantMessage.class);
  }

  @Test
  void hardTrimRefusesTinyHistories() {
    List<Message> h = new ArrayList<>();
    h.add(new SystemMessage("sys"));
    h.add(new UserMessage("task"));
    for (int i = 0; i < 4; i++) h.add(AssistantMessage.builder().content("t" + i).build());
    assertThat(WorkerAgent.hardTrimHistory(h)).isNull();
  }

  @Test
  void hardTrimBoundsHugeToolResultInShortHistory() {
    String huge = "x".repeat(50_000);
    List<Message> h = new ArrayList<>();
    h.add(new SystemMessage("sys"));
    h.add(new UserMessage("the task"));
    h.add(
        AssistantMessage.builder()
            .content("read the file")
            .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "readFile", "{}")))
            .build());
    h.add(
        ToolResponseMessage.builder()
            .responses(List.of(new ToolResponseMessage.ToolResponse("id", "readFile", huge)))
            .build());

    List<Message> out = WorkerAgent.hardTrimHistory(h);

    assertThat(out).isNotNull().isNotSameAs(h).hasSize(4);
    assertThat(out.get(2)).isInstanceOf(AssistantMessage.class);
    assertThat(out.get(3)).isInstanceOf(ToolResponseMessage.class);
    ToolResponseMessage response = (ToolResponseMessage) out.get(3);
    assertThat(response.getResponses().get(0).responseData())
        .hasSizeLessThan(huge.length())
        .contains("context overflow trim");
  }
}
