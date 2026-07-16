package com.navneet.executespec.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.navneet.executespec.agent.OpenAiNativeToolClient;
import com.navneet.executespec.agent.ProviderDispatchAuthorization;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class NativeToolsTestControllerDispatchAuthorizationTest {

  @Test
  void directNativeSmokeCannotBypassDispatchAuthorization() {
    OpenAiNativeToolClient nativeClient = mock(OpenAiNativeToolClient.class);
    ProviderDispatchAuthorization authorization = mock(ProviderDispatchAuthorization.class);
    doThrow(new IllegalStateException("dispatch denied"))
        .when(authorization)
        .authorize("openai", "gpt-5.4-mini", "smoke-run");
    NativeToolsTestController controller =
        new NativeToolsTestController(
            nativeClient,
            mock(ObjectProvider.class),
            mock(ObjectProvider.class),
            "gpt-5.4-mini",
            "claude-haiku-4-5-20251001",
            "gemini-3-flash-preview",
            authorization);

    var result = controller.testNativeTools("openai", null, "smoke-run");

    assertThat(result.get("ok")).isEqualTo(false);
    assertThat(result.get("error").toString()).contains("dispatch denied");
    verify(nativeClient, never()).call(org.mockito.ArgumentMatchers.any());
  }
}
