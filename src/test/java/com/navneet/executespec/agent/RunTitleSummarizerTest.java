package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class RunTitleSummarizerTest {

  @Test
  void usesCanonicalTextRouterWithoutBindingLegacyFallbackRouter() {
    LlmRouter llmRouter = mock(LlmRouter.class);
    ModelResolver modelResolver = mock(ModelResolver.class);
    when(modelResolver.resolve("RunTitleSummarizer", LlmProvider.OPENAI))
        .thenReturn(
            ResolvedModel.builder()
                .actualProvider(LlmProvider.OPENAI)
                .model("gpt-5.4-mini")
                .build());
    when(llmRouter.callText(
            eq("openai"),
            eq("gpt-5.4-mini"),
            anyList(),
            eq("run-1"),
            eq("RunTitleSummarizer"),
            eq("title")))
        .thenReturn(ok("Add audit logging"));

    RunTitleSummarizer summarizer = new RunTitleSummarizer(llmRouter, modelResolver);

    assertThat(summarizer.summarize("Please add audit logging", LlmProvider.OPENAI, "run-1"))
        .isEqualTo("Add audit logging");
    verify(llmRouter)
        .callText(
            eq("openai"),
            eq("gpt-5.4-mini"),
            anyList(),
            eq("run-1"),
            eq("RunTitleSummarizer"),
            eq("title"));
  }

  private static ChatResponse ok(String text) {
    return new ChatResponse(
        List.of(new Generation(AssistantMessage.builder().content(text).build())));
  }
}
