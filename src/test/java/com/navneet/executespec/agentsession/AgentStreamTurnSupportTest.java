package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.StreamTurnRequest;

import org.junit.jupiter.api.Test;

class AgentStreamTurnSupportTest {

  @Test
  void readOnlyToolsExposeProjectAndRunInspectionOnly() {
    assertThat(AgentStreamTurnSupport.allowedReadOnlyTools())
        .contains(
            "list_recent_runs",
            "get_run_report",
            "search_repo_files",
            "list_public_http_surface",
            "read_repo_file_range",
            "search_code",
            "get_tech_debt_summary")
        .doesNotContain(
            "submit_run",
            "approve_run",
            "local_code_task",
            "spawn_subagent",
            "spawn_subagents",
            "shell",
            "writeFile");
  }

  @Test
  void conversationFallsBackToCurrentUserMessage() {
    var request = new StreamTurnRequest("hello", null, null, null, null);

    List<Map<String, Object>> conversation =
        AgentStreamTurnSupport.conversationOrCurrentMessage(request);

    assertThat(conversation).hasSize(1);
    assertThat(conversation.get(0)).containsEntry("role", "user").containsEntry("content", "hello");
  }

  @Test
  void nullRequestFallsBackToBlankUserMessage() {
    List<Map<String, Object>> conversation =
        AgentStreamTurnSupport.conversationOrCurrentMessage(null);

    assertThat(conversation).hasSize(1);
    assertThat(conversation.get(0)).containsEntry("role", "user").containsEntry("content", "");
  }

  @Test
  void existingConversationWinsOverCurrentMessage() {
    List<Map<String, Object>> prior =
        List.of(Map.of("role", "user", "content", "previous message"));
    var request = new StreamTurnRequest("current message", prior, null, null, null);

    assertThat(AgentStreamTurnSupport.conversationOrCurrentMessage(request)).isSameAs(prior);
  }

  @Test
  void routedTextFollowsTheConversationTailTheProviderWillActuallyExecute() {
    // RR-1575: the runner sends `conversation` verbatim and drops `message`, so routing/guarding
    // and the durable transcript must read the same last user entry.
    List<Map<String, Object>> prior =
        List.of(
            Map.of("role", "user", "content", "older"),
            Map.of("role", "assistant", "content", "an answer"),
            Map.of("role", "user", "content", "delete every production database"));
    var withConversation = new StreamTurnRequest("hello", prior, null, null, null);

    assertThat(AgentStreamTurnSupport.routedUserText(withConversation))
        .isEqualTo("delete every production database");

    var noConversation = new StreamTurnRequest("just this", null, null, null, null);
    assertThat(AgentStreamTurnSupport.routedUserText(noConversation)).isEqualTo("just this");

    var assistantOnly =
        new StreamTurnRequest(
            "fallback", List.of(Map.of("role", "assistant", "content", "hi")), null, null, null);
    assertThat(AgentStreamTurnSupport.routedUserText(assistantOnly)).isEqualTo("fallback");
    assertThat(AgentStreamTurnSupport.routedUserText(null)).isEmpty();
  }

  @Test
  void clientDisconnectDetectionWalksCauseChain() {
    Throwable brokenPipe = new RuntimeException(new IOException("Broken pipe"));

    assertThat(AgentStreamTurnSupport.isClientDisconnect(brokenPipe)).isTrue();
    assertThat(AgentStreamTurnSupport.isClientDisconnect(new RuntimeException("model failed")))
        .isFalse();
  }
}
