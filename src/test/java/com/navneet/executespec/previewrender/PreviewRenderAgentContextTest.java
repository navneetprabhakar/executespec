package com.navneet.executespec.previewrender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.executioncontext.LlmDispatchPolicyException;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class PreviewRenderAgentContextTest {

  @Test
  void admissionFailuresBecomeStableCustomerSafeReasons() {
    assertThat(
            PreviewRenderAgent.failureReason(
                new LlmDispatchPolicyException(
                    LlmDispatchPolicyException.Reason.BUDGET_EXHAUSTED, "internal detail")))
        .isEqualTo("BUDGET_EXHAUSTED");
    assertThat(
            PreviewRenderAgent.failureReason(
                new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYMENT_REQUIRED, "internal detail")))
        .isEqualTo("AI_CREDITS_REQUIRED");
  }

  @Test
  void renderUsesDedicatedPostRunContextInsteadOfTerminalPipelineBudget() {
    LlmRouter router = mock(LlmRouter.class);
    ModelResolver resolver = mock(ModelResolver.class);
    PromptAssembler prompts = mock(PromptAssembler.class);
    LlmExecutionPolicyFactory policies = mock(LlmExecutionPolicyFactory.class);
    LlmExecutionReference reference =
        new LlmExecutionReference("preview-context", LlmExecutionScope.customer("org-1"));
    when(resolver.resolve("PreviewRenderAgent", LlmProvider.ANTHROPIC, AgentTier.WORKHORSE))
        .thenReturn(
            ResolvedModel.builder()
                .requestedProvider(LlmProvider.ANTHROPIC)
                .actualProvider(LlmProvider.ANTHROPIC)
                .model("claude-sonnet")
                .agentTier(AgentTier.WORKHORSE)
                .build());
    when(prompts.assemble(eq("preview-render.md"), any())).thenReturn("render safely");
    when(policies.forRunPreviewSurface("run-1", "job-1:home")).thenReturn(reference);
    when(router.callText(
            eq("anthropic"),
            eq("claude-sonnet"),
            any(),
            eq("run-1"),
            eq("PreviewRenderAgent"),
            eq("render:home"),
            eq(8192),
            eq(LlmRequest.PromptCacheStrategy.DEFAULT),
            eq(reference)))
        .thenReturn(
            ChatResponse.builder()
                .generations(
                    List.of(
                        new Generation(
                            new AssistantMessage("<!doctype html><html><body>Home</body></html>"))))
                .build());
    PreviewRenderAgent agent =
        new PreviewRenderAgent(
            router,
            resolver,
            prompts,
            new PreviewRenderGuard(new com.navneet.executespec.security.GeneratedSecretScanner()),
            policies);
    PreviewSurface surface =
        new PreviewSurface(
            "home",
            "src/Home.tsx",
            "Home",
            PreviewRenderTarget.WEB,
            "export const Home = () => <main>Home</main>",
            List.of(),
            100);

    PreviewRenderAgent.RenderedSurface result =
        agent.render(surface, LlmProvider.ANTHROPIC, "run-1", "job-1:home");

    assertThat(result.ok()).isTrue();
    verify(policies).forRunPreviewSurface("run-1", "job-1:home");
  }
}
