package com.navneet.executespec.tool.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins {@link RemoteToolDispatcher}:
 *
 * <ul>
 *   <li>{@code isRemote} is false until {@code register} (inert by default — the {@code
 *       ToolRegistry} branch never fires for a server-run), and false again after {@code
 *       unregister}.
 *   <li>{@code dispatch} emits a {@code TOOL_CALL} carrying the {callId, runId, name, args} frame
 *       and returns the envelope the client supplies via {@code complete}.
 *   <li>No session ⇒ an error envelope, never a throw; stray {@code complete}s are no-ops.
 * </ul>
 *
 * <p>The blocking {@code dispatch}/{@code complete} round-trip is exercised synchronously: the
 * mocked broadcaster's {@code emit} calls {@code complete} inline (emit runs before the future
 * {@code get}), so no threads are needed. Pure Mockito; no Spring context.
 */
class RemoteToolDispatcherTest {

  private final ObjectMapper om = new ObjectMapper();
  private SseBroadcaster broadcaster;
  private RemoteToolDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    broadcaster = mock(SseBroadcaster.class);
    dispatcher = new RemoteToolDispatcher(broadcaster, om, 120_000L);
  }

  @Test
  void isRemote_togglesWithRegistration() {
    assertThat(dispatcher.isRemote("r1")).isFalse();
    dispatcher.register("r1");
    assertThat(dispatcher.isRemote("r1")).isTrue();
    dispatcher.unregister("r1");
    assertThat(dispatcher.isRemote("r1")).isFalse();
  }

  @Test
  void dispatch_withoutSession_returnsErrorEnvelope() throws Exception {
    JsonNode n = om.readTree(dispatcher.dispatch("r1", "t1", "readFile", "{\"path\":\"x\"}"));
    assertThat(n.get("ok").asBoolean()).isFalse();
    assertThat(n.get("kind").asText()).isEqualTo("INTERNAL");
  }

  @Test
  void dispatch_emitsToolCall_andReturnsClientResult() throws Exception {
    dispatcher.register("r1");
    // Simulate the client replying the instant the TOOL_CALL is emitted.
    doAnswer(
            inv -> {
              RunEvent ev = inv.getArgument(1);
              String callId = om.readTree(ev.payload()).get("callId").asText();
              dispatcher.complete("r1", callId, "{\"ok\":true,\"content\":\"hello\"}");
              return null;
            })
        .when(broadcaster)
        .emit(eq("r1"), any(RunEvent.class));

    JsonNode result = om.readTree(dispatcher.dispatch("r1", "t1", "readFile", "{\"path\":\"x\"}"));
    assertThat(result.get("ok").asBoolean()).isTrue();
    assertThat(result.get("content").asText()).isEqualTo("hello");

    ArgumentCaptor<RunEvent> cap = ArgumentCaptor.forClass(RunEvent.class);
    verify(broadcaster).emit(eq("r1"), cap.capture());
    assertThat(cap.getValue().type()).isEqualTo("TOOL_CALL");
    JsonNode frame = om.readTree(cap.getValue().payload());
    assertThat(frame.get("runId").asText()).isEqualTo("r1");
    assertThat(frame.get("name").asText()).isEqualTo("readFile");
    assertThat(frame.get("permissionKind").asText()).isEqualTo("read");
    assertThat(frame.get("args").get("path").asText()).isEqualTo("x");
    assertThat(frame.get("callId").asText()).isNotBlank();
    assertThat(cap.getValue().data().get("permissionKind")).isEqualTo("read");
  }

  @Test
  void complete_onUnknownSessionOrCall_isNoop() {
    assertThatCode(() -> dispatcher.complete("nope", "c1", "{}")).doesNotThrowAnyException();
    dispatcher.register("r1");
    assertThatCode(() -> dispatcher.complete("r1", "unknown", "{}")).doesNotThrowAnyException();
  }

  @Test
  void permissionKind_defaultsUnknownToolsToShellForFailClosedClients() throws Exception {
    dispatcher.register("r1");
    doAnswer(
            inv -> {
              RunEvent ev = inv.getArgument(1);
              String callId = om.readTree(ev.payload()).get("callId").asText();
              dispatcher.complete("r1", callId, "{\"ok\":false,\"kind\":\"INVALID_ARGS\",\"message\":\"unknown\"}");
              return null;
            })
        .when(broadcaster)
        .emit(eq("r1"), any(RunEvent.class));

    dispatcher.dispatch("r1", "t1", "futureDangerousTool", "{}");

    ArgumentCaptor<RunEvent> cap = ArgumentCaptor.forClass(RunEvent.class);
    verify(broadcaster).emit(eq("r1"), cap.capture());
    JsonNode frame = om.readTree(cap.getValue().payload());
    assertThat(frame.get("permissionKind").asText()).isEqualTo("shell");
    assertThat(cap.getValue().data().get("permissionKind")).isEqualTo("shell");
  }
}
