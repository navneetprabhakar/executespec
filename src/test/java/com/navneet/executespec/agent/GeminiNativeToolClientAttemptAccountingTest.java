package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.LlmUsageAttributionResolver;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.cost.RunStepUsageRecorder;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class GeminiNativeToolClientAttemptAccountingTest {

  private static final String MODEL = "gemini-test";
  private static final String SUCCESS =
      """
      {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],
       "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3}}
      """;

  private MockWebServer server;
  private FeatureFlagService flags;
  private RunCostTracker costTracker;
  private RunStepUsageRecorder recorder;
  private GeminiNativeToolClient client;
  private LlmRequest auditRequest;
  private GeminiNativeToolClient.GeminiToolCallRequest wireRequest;
  private List<Attempt> prepared;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    flags = mock(FeatureFlagService.class);
    costTracker = mock(RunCostTracker.class);
    recorder = mock(RunStepUsageRecorder.class);
    client = spy(new GeminiNativeToolClient(new ObjectMapper(), flags, costTracker, recorder));
    doNothing().when(client).rateLimitSleep(anyLong());
    ReflectionTestUtils.setField(client, "baseUrl", server.url("/").toString());

    List<Message> messages = List.of(new SystemMessage("stable system"), new UserMessage("hello"));
    ToolCallback tool = tool();
    auditRequest =
        LlmRequest.builder()
            .provider("gemini")
            .model(MODEL)
            .messages(messages)
            .context(new ExecutionContext("run-1", "task-1", Path.of("/").toAbsolutePath()))
            .agentRole("WorkerAgent")
            .stepId("worker.iteration")
            .build();
    wireRequest =
        GeminiNativeToolClient.GeminiToolCallRequest.builder()
            .model(MODEL)
            .messages(messages)
            .tools(List.of(tool))
            .apiKey("test-key")
            .build();

    prepared = new ArrayList<>();
    when(recorder.prepareAttempt(
            eq(auditRequest),
            eq("gemini"),
            eq(MODEL),
            eq("ORG"),
            anyInt(),
            anyString(),
            nullable(String.class)))
        .thenAnswer(
            invocation -> {
              int ordinal = invocation.getArgument(4);
              String kind = invocation.getArgument(5);
              String parent = invocation.getArgument(6);
              String physicalId = auditRequest.usageLogicalCallId() + ":gemini:" + ordinal;
              prepared.add(new Attempt(ordinal, kind, parent, physicalId));
              return new RunStepUsageRecorder.AttemptHandle(
                  physicalId,
                  auditRequest.usageLogicalCallId(),
                  parent,
                  kind,
                  ordinal,
                  "gemini",
                  MODEL,
                  "WORKHORSE",
                  "ORG",
                  ordinal,
                  new LlmUsageAttributionResolver.Attribution(
                      null,
                      null,
                      null,
                      null,
                      null,
                      "WorkerAgent",
                      "UNATTRIBUTED",
                      "run-1",
                      "run-1"));
            });
    when(recorder.recordSuccess(
            any(RunStepUsageRecorder.AttemptHandle.class),
            eq(auditRequest),
            anyLong(),
            any(RunCostTracker.CallUsage.class),
            nullable(ChatResponse.class)))
        .thenAnswer(
            invocation ->
                ((RunStepUsageRecorder.AttemptHandle) invocation.getArgument(0))
                    .physicalAttemptId());
    when(costTracker.record(
            eq("run-1"), eq("task-1"), eq("gemini"), eq(MODEL), any(ChatResponse.class)))
        .thenReturn(new RunCostTracker.CallUsage("gemini", MODEL, 10, 3, 0, 0, 0.01, 1, 0));
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void recordsEvery429RetryInOrderBeforeTheFinalSuccess() throws Exception {
    when(flags.isEnabled("gemini.explicit-cache.enabled")).thenReturn(false);
    server.enqueue(rateLimited());
    server.enqueue(rateLimited());
    server.enqueue(json(200, SUCCESS));

    GeminiNativeToolClient.AuditedCallResult result = callWithFreshAuditContext();

    assertThat(result.response().getResult().getOutput().getText()).isEqualTo("ok");
    assertThat(prepared)
        .extracting(Attempt::ordinal, Attempt::kind)
        .containsExactly(tuple(1, "PRIMARY"), tuple(2, "RETRY"), tuple(3, "RETRY"));
    assertParentChain(prepared);
    verify(recorder, org.mockito.Mockito.times(2))
        .recordFailure(
            any(RunStepUsageRecorder.AttemptHandle.class),
            eq(auditRequest),
            anyLong(),
            any(Throwable.class));
    verify(recorder)
        .recordSuccess(
            any(RunStepUsageRecorder.AttemptHandle.class),
            eq(auditRequest),
            anyLong(),
            any(RunCostTracker.CallUsage.class),
            any(ChatResponse.class));
    assertThat(server.getRequestCount()).isEqualTo(3);
  }

  @Test
  void recordsCached404ThenInlineFallbackWithoutAnOuterDuplicate() throws Exception {
    when(flags.isEnabled("gemini.explicit-cache.enabled")).thenReturn(true);
    server.enqueue(json(200, "{\"name\":\"cachedContents/cache-1\"}"));
    server.enqueue(json(200, SUCCESS));
    callWithFreshAuditContext();

    prepared.clear();
    clearInvocations(recorder, costTracker);
    stubRecorderAfterClear();
    server.enqueue(json(404, "{\"error\":{\"message\":\"cachedContent missing\"}}"));
    server.enqueue(json(200, SUCCESS));

    GeminiNativeToolClient.AuditedCallResult result = callWithFreshAuditContext();

    assertThat(result.response().getResult().getOutput().getText()).isEqualTo("ok");
    assertThat(prepared)
        .extracting(Attempt::ordinal, Attempt::kind)
        .containsExactly(tuple(1, "PRIMARY"), tuple(2, "RETRY"));
    assertParentChain(prepared);
    verify(recorder)
        .recordFailure(
            any(RunStepUsageRecorder.AttemptHandle.class),
            eq(auditRequest),
            anyLong(),
            any(Throwable.class));
    verify(recorder)
        .recordSuccess(
            any(RunStepUsageRecorder.AttemptHandle.class),
            eq(auditRequest),
            anyLong(),
            any(RunCostTracker.CallUsage.class),
            any(ChatResponse.class));

    RecordedRequest cached = server.takeRequest(1, TimeUnit.SECONDS);
    RecordedRequest warmGenerate = server.takeRequest(1, TimeUnit.SECONDS);
    RecordedRequest staleCached = server.takeRequest(1, TimeUnit.SECONDS);
    RecordedRequest inline = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(cached).isNotNull();
    assertThat(warmGenerate).isNotNull();
    assertThat(staleCached.getBody().readUtf8()).contains("cachedContent");
    assertThat(inline.getBody().readUtf8())
        .contains("systemInstruction")
        .doesNotContain("cachedContent");
  }

  @Test
  void recordsCacheMintFailureThenGenerateSuccess() {
    when(flags.isEnabled("gemini.explicit-cache.enabled")).thenReturn(true);
    server.enqueue(json(400, "{\"error\":{\"message\":\"prefix too small\"}}"));
    server.enqueue(json(200, SUCCESS));

    callWithFreshAuditContext();

    assertThat(prepared)
        .extracting(Attempt::ordinal, Attempt::kind)
        .containsExactly(tuple(1, "CACHE_MINT"), tuple(2, "PRIMARY"));
    assertParentChain(prepared);
    verify(recorder)
        .recordFailure(
            any(RunStepUsageRecorder.AttemptHandle.class),
            eq(auditRequest),
            anyLong(),
            any(Throwable.class));
  }

  @Test
  void recordsCacheMintSuccessThenGenerateSuccess() {
    when(flags.isEnabled("gemini.explicit-cache.enabled")).thenReturn(true);
    server.enqueue(json(200, "{\"name\":\"cachedContents/cache-2\"}"));
    server.enqueue(json(200, SUCCESS));

    callWithFreshAuditContext();

    assertThat(prepared)
        .extracting(Attempt::ordinal, Attempt::kind)
        .containsExactly(tuple(1, "CACHE_MINT"), tuple(2, "PRIMARY"));
    assertParentChain(prepared);
    verify(costTracker)
        .record(eq("run-1"), eq("task-1"), eq("gemini"), eq(MODEL), any(ChatResponse.class));
    verify(recorder, org.mockito.Mockito.times(2))
        .recordSuccess(
            any(RunStepUsageRecorder.AttemptHandle.class),
            eq(auditRequest),
            anyLong(),
            any(RunCostTracker.CallUsage.class),
            nullable(ChatResponse.class));
  }

  private GeminiNativeToolClient.AuditedCallResult callWithFreshAuditContext() {
    return client.callAudited(
        wireRequest,
        new GeminiNativeToolClient.AuditContext(
            auditRequest, "ORG", new AtomicInteger(), new AtomicReference<>()));
  }

  private void stubRecorderAfterClear() {
    when(recorder.recordSuccess(
            any(RunStepUsageRecorder.AttemptHandle.class),
            eq(auditRequest),
            anyLong(),
            any(RunCostTracker.CallUsage.class),
            nullable(ChatResponse.class)))
        .thenAnswer(
            invocation ->
                ((RunStepUsageRecorder.AttemptHandle) invocation.getArgument(0))
                    .physicalAttemptId());
    when(costTracker.record(
            eq("run-1"), eq("task-1"), eq("gemini"), eq(MODEL), any(ChatResponse.class)))
        .thenReturn(new RunCostTracker.CallUsage("gemini", MODEL, 10, 3, 0, 0, 0.01, 2, 0));
  }

  private static void assertParentChain(List<Attempt> attempts) {
    assertThat(attempts.get(0).parent()).isNull();
    for (int index = 1; index < attempts.size(); index++) {
      assertThat(attempts.get(index).parent()).isEqualTo(attempts.get(index - 1).physicalId());
    }
  }

  private static MockResponse rateLimited() {
    return json(429, "{\"error\":{\"message\":\"rate limited\"}}").addHeader("Retry-After", "0");
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse()
        .setResponseCode(code)
        .addHeader("Content-Type", "application/json")
        .setBody(body);
  }

  private static ToolCallback tool() {
    ToolDefinition definition =
        DefaultToolDefinition.builder()
            .name("read_file")
            .description("read")
            .inputSchema("{\"type\":\"object\"}")
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String input) {
        return "{}";
      }
    };
  }

  private static org.assertj.core.groups.Tuple tuple(Object... values) {
    return org.assertj.core.groups.Tuple.tuple(values);
  }

  private record Attempt(int ordinal, String kind, String parent, String physicalId) {}
}
