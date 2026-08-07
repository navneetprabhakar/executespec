package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OpenAiNativeToolClientTimeoutTest {

  @Test
  void defaultKeepsOpenAiWireProvidersAtTwoMinutes() {
    OpenAiNativeToolClient client = new OpenAiNativeToolClient(mock(ObjectMapper.class), null);

    assertThat(client.effectiveReadTimeout(request(null))).isEqualTo(Duration.ofSeconds(120));
  }

  @Test
  void deepSeekPerCallOverrideCanExtendReadWindow() {
    OpenAiNativeToolClient client = new OpenAiNativeToolClient(mock(ObjectMapper.class), null);

    assertThat(client.effectiveReadTimeout(request(300))).isEqualTo(Duration.ofSeconds(300));
  }

  @Test
  void configuredDefaultIsUsedWhenNoPerCallOverrideExists() {
    OpenAiNativeToolClient client = new OpenAiNativeToolClient(mock(ObjectMapper.class), null);
    ReflectionTestUtils.setField(client, "configuredReadTimeoutSeconds", 180);

    assertThat(client.effectiveReadTimeout(request(null))).isEqualTo(Duration.ofSeconds(180));
  }

  private static OpenAiNativeToolClient.OpenAiToolCallRequest request(Integer timeoutSeconds) {
    return OpenAiNativeToolClient.OpenAiToolCallRequest.builder()
        .model("deepseek-v4-pro")
        .messages(java.util.List.of())
        .readTimeoutSeconds(timeoutSeconds)
        .build();
  }
}
