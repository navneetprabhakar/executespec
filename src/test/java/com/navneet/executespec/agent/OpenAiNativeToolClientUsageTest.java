package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.test.util.ReflectionTestUtils;

class OpenAiNativeToolClientUsageTest {

  @Test
  void exposesNestedReasoningTokensWithoutRetainingProviderPayload() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    OpenAiNativeToolClient client = new OpenAiNativeToolClient(mapper);
    var responseJson =
        mapper.readTree(
            """
            {
              "id":"resp-1",
              "model":"gpt-test",
              "choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
              "usage":{
                "prompt_tokens":120,
                "completion_tokens":80,
                "prompt_tokens_details":{"cached_tokens":20},
                "completion_tokens_details":{"reasoning_tokens":55}
              }
            }
            """);

    ChatResponse response =
        ReflectionTestUtils.invokeMethod(client, "parseResponse", responseJson, "gpt-test");

    assertThat(response).isNotNull();
    assertThat(response.getMetadata().<Number>get("reasoning_tokens").longValue()).isEqualTo(55L);
    assertThat(response.getMetadata().<Number>get("cache_read_input_tokens").longValue())
        .isEqualTo(20L);
  }

  @Test
  void acceptsDeepSeekTopLevelCacheCountersWithoutDoubleCounting() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    OpenAiNativeToolClient client = new OpenAiNativeToolClient(mapper);
    var responseJson =
        mapper.readTree(
            """
            {
              "id":"resp-cache",
              "model":"deepseek-test",
              "choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
              "usage":{
                "prompt_tokens":120,
                "completion_tokens":10,
                "prompt_tokens_details":{"cached_tokens":20},
                "prompt_cache_hit_tokens":80,
                "prompt_cache_miss_tokens":40
              }
            }
            """);

    ChatResponse response =
        ReflectionTestUtils.invokeMethod(client, "parseResponse", responseJson, "deepseek-test");

    assertThat(response.getMetadata().<Number>get("cache_read_input_tokens").longValue())
        .isEqualTo(80L);
    assertThat(response.getMetadata().<Number>get("cache_miss_input_tokens").longValue())
        .isEqualTo(40L);
    assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(120);
  }
}
