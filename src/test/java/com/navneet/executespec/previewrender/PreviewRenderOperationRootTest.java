package com.navneet.executespec.previewrender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.executioncontext.LlmExecutionOperationClaim;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationService;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.Test;

class PreviewRenderOperationRootTest {

  @Test
  void claimBindsAuthorityAndDoesNotOpenANewInvocationAfterUncertainAttempt() {
    LlmExecutionPolicyFactory policies = mock(LlmExecutionPolicyFactory.class);
    LlmExecutionOperationService operations = mock(LlmExecutionOperationService.class);
    LlmExecutionReference reference =
        new LlmExecutionReference("ctx-preview", LlmExecutionScope.customer("org"));
    ResolvedModel route = route();
    when(policies.forRunPreviewSurface("run-1", "00000000-0000-0000-0000-000000000001:home", route))
        .thenReturn(reference);
    when(operations.claimOrResume(eq(reference), any(), eq("worker"), any()))
        .thenReturn(Optional.empty());
    PreviewRenderOperationRoot root =
        new PreviewRenderOperationRoot(policies, new LlmExecutionOperationCoordinator(operations));

    assertThat(
            root.claim(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "run-1",
                surface(),
                route,
                "worker",
                Duration.ofMinutes(2)))
        .isEmpty();
    verify(operations, never()).beginNewInvocation(any(), any(), any(), any());
  }

  @Test
  void successfulClaimCarriesOpaqueAuthorityIntoTheSurfaceRequest() {
    LlmExecutionPolicyFactory policies = mock(LlmExecutionPolicyFactory.class);
    LlmExecutionOperationService operations = mock(LlmExecutionOperationService.class);
    LlmExecutionReference reference =
        new LlmExecutionReference("ctx-preview", LlmExecutionScope.customer("org"));
    when(policies.forRunPreviewSurface(any(), any(), any())).thenReturn(reference);
    when(operations.claimOrResume(eq(reference), any(), eq("worker"), any()))
        .thenReturn(
            Optional.of(
                new LlmExecutionOperationClaim(
                    reference, "llmop-preview", 1, 1, UUID.randomUUID())));
    PreviewRenderOperationRoot root =
        new PreviewRenderOperationRoot(policies, new LlmExecutionOperationCoordinator(operations));

    PreviewRenderOperationRoot.Active active =
        root.claim(UUID.randomUUID(), "run-1", surface(), route(), "worker", Duration.ofMinutes(2))
            .orElseThrow();

    assertThat(active.context().llmExecutionOperationAuthority()).isNotNull();
    assertThat(active.context().llmExecutionContextId()).isEqualTo("ctx-preview");
  }

  private static PreviewSurface surface() {
    return new PreviewSurface(
        "home",
        "src/Home.tsx",
        "Home",
        PreviewRenderTarget.WEB,
        "export const Home = () => <main>Home</main>",
        List.of(),
        48);
  }

  private static ResolvedModel route() {
    return ResolvedModel.builder()
        .requestedProvider(LlmProvider.ANTHROPIC)
        .actualProvider(LlmProvider.ANTHROPIC)
        .model("claude-sonnet")
        .agentTier(AgentTier.WORKHORSE)
        .build();
  }
}
