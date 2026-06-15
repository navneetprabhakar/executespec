package com.navneet.executespec.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.navneet.executespec.cost.RunCostTracker;

import org.junit.jupiter.api.Test;

class RunEventContractTest {

  @Test
  void legacyConstructorKeepsPayloadAndAddsEmptyTypedData() {
    RunEvent event = RunEvent.log("run-1", "task-1", "hello");

    assertEquals("LOG", event.type());
    assertEquals("hello", event.payload());
    assertEquals(1, event.schemaVersion());
    assertTrue(event.data().isEmpty());
  }

  @Test
  void tokenUsageCarriesTypedUsageEnvelopeWithoutChangingPayload() {
    RunCostTracker.Snapshot snapshot = new RunCostTracker.Snapshot(100, 25, 7, 40, 0.0123, 3, 9);

    RunEvent event = RunEvent.tokenUsage("run-1", "task-1", "openai", "gpt-5.4", snapshot);

    assertEquals("TOKEN_USAGE", event.type());
    assertEquals("task-1", event.taskId());
    assertEquals("in=100,out=25,total=125", event.payload());
    assertEquals(100, event.data().get("inputTokens"));
    assertEquals(25, event.data().get("outputTokens"));
    assertEquals(40, event.data().get("cacheReadTokens"));
    assertEquals(7, event.data().get("cacheWriteTokens"));
    assertEquals(9, event.data().get("reasoningTokens"));
    assertEquals(3, event.data().get("calls"));
    assertEquals("openai", event.data().get("provider"));
    assertEquals("gpt-5.4", event.data().get("model"));
  }

  @Test
  void toolCallCarriesTypedRemoteToolFieldsWithoutChangingPayloadFrame() {
    RunEvent event =
        RunEvent.toolCall("run-1", "task-1", "{\"callId\":\"c1\"}", "c1", "readFile", "{}");

    assertEquals("TOOL_CALL", event.type());
    assertEquals("{\"callId\":\"c1\"}", event.payload());
    assertEquals("c1", event.data().get("callId"));
    assertEquals("readFile", event.data().get("toolName"));
    assertEquals("{}", event.data().get("argsJson"));
  }
}
