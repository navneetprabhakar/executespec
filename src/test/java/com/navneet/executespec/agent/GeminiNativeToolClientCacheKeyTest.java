package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.cost.RunStepUsageRecorder;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RR-509 / RR-510 — the explicit-cache prefix key must distinguish the baked tool-call mode and the
 * authenticating credential, so a forced-ANY and an automatic-AUTO request never collide on one JVM
 * cache entry, and a resource minted with one API key is never reused under another.
 */
class GeminiNativeToolClientCacheKeyTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final GeminiNativeToolClient client =
      new GeminiNativeToolClient(
          mapper,
          mock(FeatureFlagService.class),
          mock(RunCostTracker.class),
          mock(RunStepUsageRecorder.class));

  private static ToolCallback tool(String name) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name(name)
            .description("d")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return def;
      }

      @Override
      public String call(String toolInput) {
        return "";
      }
    };
  }

  private GeminiNativeToolClient.GeminiToolCallRequest request(boolean force, String apiKey) {
    List<Message> messages = List.of(new SystemMessage("shared system prefix"));
    return GeminiNativeToolClient.GeminiToolCallRequest.builder()
        .model("gemini-3-flash-preview")
        .messages(messages)
        .tools(List.of(tool("writeFile")))
        .forceToolCall(force)
        .apiKey(apiKey)
        .build();
  }

  private String key(GeminiNativeToolClient.GeminiToolCallRequest req, String apiKey) {
    return ReflectionTestUtils.invokeMethod(client, "explicitCachePrefixKey", req, apiKey);
  }

  @Test
  void toolModeChangesTheCacheKey() {
    String forced = key(request(true, "k1"), "k1");
    String auto = key(request(false, "k1"), "k1");
    assertThat(forced).isNotEqualTo(auto);
  }

  @Test
  void credentialScopeChangesTheCacheKey() {
    String tenantA = key(request(true, "key-A"), "key-A");
    String tenantB = key(request(true, "key-B"), "key-B");
    assertThat(tenantA).isNotEqualTo(tenantB);
  }

  @Test
  void identicalPrefixModeAndCredentialShareOneKey() {
    assertThat(key(request(true, "same"), "same")).isEqualTo(key(request(true, "same"), "same"));
  }
}
