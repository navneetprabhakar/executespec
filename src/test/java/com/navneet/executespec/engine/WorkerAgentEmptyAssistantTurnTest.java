package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

class WorkerAgentEmptyAssistantTurnTest {

  @Test
  void outputExhaustionWithoutContentDoesNotAppendInvalidAssistantMessage() {
    List<Message> history = new ArrayList<>();

    WorkerCompletionPolicy.appendTextTurn(history, "");
    WorkerCompletionPolicy.appendTextTurn(history, "   ");

    assertThat(history).isEmpty();
  }

  @Test
  void genuineTextOnlyStatusIsPreservedForTheCorrectiveTurn() {
    List<Message> history = new ArrayList<>();

    WorkerCompletionPolicy.appendTextTurn(history, "I need one more edit.");

    assertThat(history).singleElement().isInstanceOf(AssistantMessage.class);
    assertThat(((AssistantMessage) history.getFirst()).getText())
        .isEqualTo("I need one more edit.");
  }
}
