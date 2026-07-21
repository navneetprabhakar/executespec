package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.executioncontext.LlmExecutionContext;
import com.navneet.executespec.executioncontext.LlmExecutionContextService;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

class LlmUsageAttributionResolverTest {

  private final LlmExecutionContextService contextService = mock(LlmExecutionContextService.class);
  private final PipelineRunRepository runRepository = mock(PipelineRunRepository.class);
  private final AgentSessionRepository sessionRepository = mock(AgentSessionRepository.class);
  private final LlmUsageAttributionResolver resolver =
      new LlmUsageAttributionResolver(contextService, runRepository, sessionRepository);

  @Test
  void explicitContextUsesExactTenantScopedLoadOnly() {
    LlmExecutionScope scope = LlmExecutionScope.customer("org-1");
    LlmExecutionContext durable = mock(LlmExecutionContext.class);
    when(durable.getId()).thenReturn("llmctx-1");
    when(durable.getOrgId()).thenReturn("org-1");
    when(durable.getUserId()).thenReturn("user-1");
    when(durable.getProjectId()).thenReturn("project-1");
    when(durable.getFeature()).thenReturn("PROJECT_CHAT");
    when(durable.getSourceType()).thenReturn("AGENT_TURN");
    when(durable.getSourceId()).thenReturn("turn-1");
    when(contextService.load(scope, "llmctx-1")).thenReturn(Optional.of(durable));

    var result = resolver.resolve(request("synthetic", "llmctx-1", scope));

    assertThat(result.llmExecutionContextId()).isEqualTo("llmctx-1");
    assertThat(result.orgId()).isEqualTo("org-1");
    assertThat(result.userId()).isEqualTo("user-1");
    assertThat(result.projectId()).isEqualTo("project-1");
    assertThat(result.feature()).isEqualTo("PROJECT_CHAT");
    assertThat(result.sourceType()).isEqualTo("AGENT_TURN");
    verify(contextService).load(scope, "llmctx-1");
    verifyNoInteractions(runRepository, sessionRepository);
  }

  @Test
  void missingExplicitContextDoesNotFuzzyFallback() {
    LlmExecutionScope scope = LlmExecutionScope.customer("org-1");
    when(contextService.load(scope, "llmctx-missing")).thenReturn(Optional.empty());

    var result = resolver.resolve(request("real-run-id", "llmctx-missing", scope));

    assertThat(result.llmExecutionContextId()).isNull();
    assertThat(result.sourceType()).isEqualTo("INVALID_EXECUTION_CONTEXT");
    assertThat(result.sourceId()).isEqualTo("llmctx-missing");
    verifyNoInteractions(runRepository, sessionRepository);
  }

  private static LlmRequest request(
      String runId, String contextId, LlmExecutionScope executionScope) {
    return LlmRequest.builder()
        .provider("openai")
        .model("gpt")
        .messages(List.of(new UserMessage("test")))
        .context(new ExecutionContext(runId, "task", Path.of(".").toAbsolutePath().normalize()))
        .agentRole("WorkerAgent")
        .llmExecutionContextId(contextId)
        .llmExecutionScope(executionScope)
        .build();
  }
}
