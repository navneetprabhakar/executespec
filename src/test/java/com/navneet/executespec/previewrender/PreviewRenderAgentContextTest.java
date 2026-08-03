package com.navneet.executespec.previewrender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
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
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.ClassPathResource;

class PreviewRenderAgentContextTest {

  @Test
  void promptRequiresPopulatedRepresentativeDataAndHonestRuntimeSemantics() throws Exception {
    String prompt =
        new ClassPathResource("prompts/preview-render.md")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(prompt)
        .contains("populated successful state")
        .contains("3–6 varied entries")
        .contains("must not imply that the application")
        .contains("controls are actually running");
  }

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
    PromptAssembler prompts = mock(PromptAssembler.class);
    LlmExecutionPolicyFactory policies = mock(LlmExecutionPolicyFactory.class);
    LlmExecutionReference reference =
        new LlmExecutionReference("preview-context", LlmExecutionScope.customer("org-1"));
    ResolvedModel route =
        ResolvedModel.builder()
            .requestedProvider(LlmProvider.ANTHROPIC)
            .actualProvider(LlmProvider.ANTHROPIC)
            .model("claude-sonnet")
            .agentTier(AgentTier.WORKHORSE)
            .build();
    when(prompts.assemble(eq("preview-render.md"), any())).thenReturn("render safely");
    when(policies.forRunPreviewSurface("run-1", "job-1:home", route)).thenReturn(reference);
    when(router.call(any(LlmRequest.class)))
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
        agent.render(surface, route, "run-1", "job-1:home", null);

    assertThat(result.ok()).isTrue();
    verify(policies).forRunPreviewSurface("run-1", "job-1:home", route);
    ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
    verify(router).call(request.capture());
    assertThat(request.getValue().provider()).isEqualTo("anthropic");
    assertThat(request.getValue().model()).isEqualTo("claude-sonnet");
    assertThat(request.getValue().strictProvider()).isTrue();
  }
}
