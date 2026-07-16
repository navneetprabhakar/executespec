package com.navneet.executespec.agentsession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.StreamTurnRequest;

final class AgentStreamTurnSupport {

  private AgentStreamTurnSupport() {}

  static Set<String> allowedReadOnlyTools() {
    return Set.of(
        "list_recent_runs",
        "get_run_report",
        "list_repos",
        "list_pull_requests",
        "get_project_memory",
        "get_latest_architecture",
        "search_repo_files",
        "search_code",
        "get_latest_code_review",
        "list_project_documents",
        "read_generated_doc",
        "search_project_docs",
        "get_tech_debt_summary",
        "web_search",
        "web_fetch",
        "spawn_subagent",
        "spawn_subagents");
  }

  static List<Map<String, Object>> conversationOrCurrentMessage(StreamTurnRequest request) {
    if (request != null && request.conversation() != null && !request.conversation().isEmpty()) {
      return request.conversation();
    }
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "user");
    message.put("content", request == null ? "" : request.message());
    return List.of(message);
  }

  /**
   * Prepend a server-built untrusted-content block to the last user message so uploaded-file
   * context rides with the current turn. Returns the conversation unchanged when there is no block.
   */
  static List<Map<String, Object>> withAttachmentBlock(
      List<Map<String, Object>> conversation, String block) {
    if (block == null || block.isBlank() || conversation == null || conversation.isEmpty()) {
      return conversation;
    }
    List<Map<String, Object>> copy = new ArrayList<>();
    for (Map<String, Object> m : conversation) {
      copy.add(new LinkedHashMap<>(m));
    }
    for (int i = copy.size() - 1; i >= 0; i--) {
      Map<String, Object> m = copy.get(i);
      String role = m.get("role") == null ? "user" : String.valueOf(m.get("role"));
      if ("user".equalsIgnoreCase(role)) {
        Object content = m.get("content");
        m.put("content", block + "\n\n" + (content == null ? "" : content));
        return copy;
      }
    }
    Map<String, Object> injected = new LinkedHashMap<>();
    injected.put("role", "user");
    injected.put("content", block);
    copy.add(0, injected);
    return copy;
  }

  static boolean isClientDisconnect(Throwable e) {
    Throwable current = e;
    while (current != null) {
      String name = current.getClass().getName().toLowerCase(java.util.Locale.ROOT);
      String message =
          current.getMessage() == null
              ? ""
              : current.getMessage().toLowerCase(java.util.Locale.ROOT);
      if (name.contains("asyncrequestnotusableexception")
          || message.contains("broken pipe")
          || message.contains("connection reset")
          || message.contains("clientabort")
          || message.contains("servletoutputstream failed")) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
