package com.navneet.executespec.engine;

import java.util.HashMap;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;

final class WorkerFailureStreaks {

  private final Map<String, Integer> streaks = new HashMap<>();

  void record(AssistantMessage.ToolCall call, String envelope) {
    String ordinary = WorkerAgent.callSignature(call);
    if (WorkerAgent.isFailureEnvelope(envelope)) {
      streaks.merge(signature(call, envelope), 1, Integer::sum);
      return;
    }
    streaks.remove(ordinary);
    streaks.remove(call.name() + "|FORBIDDEN");
    streaks.remove(call.name() + "|UNAVAILABLE");
  }

  int worst() {
    return streaks.values().stream().mapToInt(Integer::intValue).max().orElse(0);
  }

  static String signature(AssistantMessage.ToolCall call, String envelope) {
    if (envelope != null && envelope.startsWith("{\"ok\":false")) {
      if (envelope.contains("\"kind\":\"FORBIDDEN\"")) return call.name() + "|FORBIDDEN";
      if (envelope.contains("\"kind\":\"UNAVAILABLE\"")) return call.name() + "|UNAVAILABLE";
    }
    return WorkerAgent.callSignature(call);
  }
}
