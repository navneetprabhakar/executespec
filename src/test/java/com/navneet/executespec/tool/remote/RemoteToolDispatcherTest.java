package com.navneet.executespec.tool.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.LocalHandsState;
import com.navneet.executespec.agentsession.LocalHandsStateService;
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
  private LocalHandsStateService stateService;
  private RemoteToolDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    broadcaster = mock(SseBroadcaster.class);
    stateService = mock(LocalHandsStateService.class);
    when(stateService.pending(any())).thenReturn(java.util.List.of());
    dispatcher = new RemoteToolDispatcher(broadcaster, om, stateService, 120_000L);
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
    verify(stateService)
        .record(
            eq("r1"),
            eq(frame.get("callId").asText()),
            eq(LocalHandsState.AWAITING_CLIENT),
            eq("t1"),
            eq("readFile"),
            eq("read"),
            any());
    verify(stateService)
        .record(
            eq("r1"),
            eq(frame.get("callId").asText()),
            eq(LocalHandsState.COMPLETED),
            eq("t1"),
            eq("readFile"),
            eq("read"),
            any());
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
              dispatcher.complete(
                  "r1", callId, "{\"ok\":false,\"kind\":\"INVALID_ARGS\",\"message\":\"unknown\"}");
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

  @Test
  void permissionKind_classifies_cliProtocolTools() throws Exception {
    assertPermissionKind("spawn_subagent", "read");
    assertPermissionKind("update_plan", "read");
    assertPermissionKind("todo", "read");
    assertPermissionKind("ast_edit", "edit");
    assertPermissionKind("mcp_client", "shell");
  }

  @Test
  void disconnect_keepsCallPending_andReconnectReemitsIt() throws Exception {
    dispatcher.register("r1");
    CountDownLatch firstEmission = new CountDownLatch(1);
    AtomicInteger emissionCount = new AtomicInteger();
    String[] callId = new String[1];
    doAnswer(
            inv -> {
              RunEvent event = inv.getArgument(1);
              callId[0] = om.readTree(event.payload()).get("callId").asText();
              if (emissionCount.incrementAndGet() == 1) {
                firstEmission.countDown();
              } else {
                dispatcher.complete("r1", callId[0], "{\"ok\":true,\"content\":\"resumed\"}");
              }
              return null;
            })
        .when(broadcaster)
        .emit(eq("r1"), any(RunEvent.class));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<String> result =
          executor.submit(() -> dispatcher.dispatch("r1", "t1", "readFile", "{}"));
      assertThat(firstEmission.await(2, TimeUnit.SECONDS)).isTrue();

      dispatcher.disconnect("r1");
      assertThat(result.isDone()).isFalse();
      dispatcher.reconnect("r1");

      assertThat(om.readTree(result.get(2, TimeUnit.SECONDS)).path("content").asText())
          .isEqualTo("resumed");
      assertThat(emissionCount).hasValue(2);
      verify(stateService)
          .record(
              eq("r1"),
              eq(callId[0]),
              eq(LocalHandsState.CLIENT_RECONNECTED),
              eq("t1"),
              eq("readFile"),
              eq("read"),
              any());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void timeout_returnsClientStateInsteadOfInternalToolFailure() throws Exception {
    dispatcher = new RemoteToolDispatcher(broadcaster, om, stateService, 1L);
    dispatcher.register("r1");

    JsonNode result = om.readTree(dispatcher.dispatch("r1", "t1", "shell", "{}"));

    assertThat(result.path("kind").asText()).isEqualTo("CLIENT_TIMEOUT");
    verify(stateService)
        .record(
            eq("r1"),
            any(),
            eq(LocalHandsState.CLIENT_TIMEOUT),
            eq("t1"),
            eq("shell"),
            eq("shell"),
            any());
  }

  private void assertPermissionKind(String toolName, String expected) throws Exception {
    setUp();
    dispatcher.register("r1");
    doAnswer(
            inv -> {
              RunEvent ev = inv.getArgument(1);
              String callId = om.readTree(ev.payload()).get("callId").asText();
              dispatcher.complete("r1", callId, "{\"ok\":true,\"content\":\"ok\"}");
              return null;
            })
        .when(broadcaster)
        .emit(eq("r1"), any(RunEvent.class));

    dispatcher.dispatch("r1", "t1", toolName, "{}");

    ArgumentCaptor<RunEvent> cap = ArgumentCaptor.forClass(RunEvent.class);
    verify(broadcaster).emit(eq("r1"), cap.capture());
    JsonNode frame = om.readTree(cap.getValue().payload());
    assertThat(frame.get("permissionKind").asText()).isEqualTo(expected);
  }
}
