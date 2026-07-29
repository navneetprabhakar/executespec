package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.executioncontext.LlmExecutionOperationClaim;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationDescriptor;
import com.navneet.executespec.executioncontext.LlmExecutionOperationService;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;

class ArchLlmDispatchOperationTest {

  @Test
  void operationDispatchPreservesRootIssuedAuthorityOnRouterRequest() {
    LlmExecutionReference reference =
        new LlmExecutionReference("ctx-architecture", LlmExecutionScope.customer("org-1"));
    LlmExecutionOperationDescriptor descriptor =
        LlmExecutionOperationDescriptor.of(
            "project-architecture:test", "PROJECT_ARCHITECTURE", "architecture-test", 1);
    LlmExecutionOperationClaim claim =
        new LlmExecutionOperationClaim(reference, "llmop-test", 1, 1, UUID.randomUUID());
    LlmExecutionOperationService operations = mock(LlmExecutionOperationService.class);
    when(operations.claimOrResume(reference, descriptor, "worker-1", Duration.ofMinutes(2)))
        .thenReturn(Optional.of(claim));
    LlmExecutionOperationCoordinator coordinator = new LlmExecutionOperationCoordinator(operations);
    var lease =
        coordinator
            .claimOrResume(reference, descriptor, "worker-1", Duration.ofMinutes(2))
            .orElseThrow();
    ExecutionContext bound =
        coordinator.bind(
            new ExecutionContext(
                "project-architecture-test",
                "combined-architecture",
                Path.of("/tmp").toAbsolutePath(),
                reference),
            lease);

    LlmRouter router = mock(LlmRouter.class);
    ChatResponse response = mock(ChatResponse.class);
    when(router.call(any(LlmRequest.class))).thenReturn(response);
    ResolvedModel resolved =
        ResolvedModel.builder()
            .requestedProvider(LlmProvider.GEMINI)
            .actualProvider(LlmProvider.GEMINI)
            .model("gemini-test")
            .agentTier(AgentTier.PLANNING)
            .build();

    assertThat(
            ArchLlmDispatch.dispatch(
                router,
                resolved,
                List.of(new UserMessage("architecture")),
                "architecture-source",
                "combined-architecture",
                bound))
        .isSameAs(response);

    ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
    verify(router).call(request.capture());
    assertThat(request.getValue().context()).isSameAs(bound);
    assertThat(request.getValue().llmExecutionOperationAuthority()).isNotNull();
    assertThat(request.getValue().toolsOverride()).isEmpty();
  }
}
