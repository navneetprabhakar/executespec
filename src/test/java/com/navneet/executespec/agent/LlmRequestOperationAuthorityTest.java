package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.executioncontext.LlmExecutionOperationAuthority;
import com.navneet.executespec.executioncontext.LlmExecutionOperationClaim;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

class LlmRequestOperationAuthorityTest {

  private static final LlmExecutionReference REFERENCE =
      new LlmExecutionReference("llmctx-authority", LlmExecutionScope.customer("org-a"));
  private static final UUID TOKEN = UUID.fromString("00000000-0000-0000-0000-000000000789");

  @Test
  void derivesAuthorityAndDurableReferenceFromToolContextAndPreservesThemInToBuilder() {
    LlmExecutionOperationAuthority authority = authority(REFERENCE, "llmop-request");
    ExecutionContext context =
        new ExecutionContext(
            "run-a", "task-a", Path.of("/tmp/authority-request"), REFERENCE, authority);

    LlmRequest request = request(context).build();
    LlmRequest copied = request.toBuilder().stepId("copy").build();

    assertThat(request.llmExecutionContextId()).isEqualTo(REFERENCE.contextId());
    assertThat(request.llmExecutionScope()).isEqualTo(REFERENCE.scope());
    assertThat(request.llmExecutionOperationAuthority()).isSameAs(authority);
    assertThat(copied.llmExecutionOperationAuthority()).isSameAs(authority);
    assertThat(copied.context()).isSameAs(context);
  }

  @Test
  void rejectsDurableIdentityThatDisagreesWithToolContext() {
    ExecutionContext context =
        new ExecutionContext("run-a", "task-a", Path.of("/tmp/authority-request"), REFERENCE);

    assertThatThrownBy(
            () ->
                request(context)
                    .llmExecutionContextId("llmctx-other")
                    .llmExecutionScope(REFERENCE.scope())
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must match its tool context");
  }

  @Test
  void rejectsAuthorityInjectionInsteadOfReconstructingItFromRequestFields() {
    LlmExecutionOperationAuthority expected = authority(REFERENCE, "llmop-expected");
    LlmExecutionOperationAuthority injected = authority(REFERENCE, "llmop-injected");
    ExecutionContext context =
        new ExecutionContext(
            "run-a", "task-a", Path.of("/tmp/authority-request"), REFERENCE, expected);

    assertThatThrownBy(() -> request(context).llmExecutionOperationAuthority(injected).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be derived from its tool context");

    ExecutionContext identityOnly =
        new ExecutionContext("run-a", "task-a", Path.of("/tmp/authority-request"), REFERENCE);
    assertThatThrownBy(() -> request(identityOnly).llmExecutionOperationAuthority(expected).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be derived from its tool context");
  }

  @Test
  void diagnosticRenderingOmitsAuthorityAndPromptContents() {
    LlmExecutionOperationAuthority authority = authority(REFERENCE, "llmop-request");
    ExecutionContext context =
        new ExecutionContext(
            "run-a", "task-a", Path.of("/tmp/authority-request"), REFERENCE, authority);

    String rendered = request(context).build().toString();

    assertThat(rendered)
        .doesNotContain(
            "llmExecutionOperationAuthority",
            "owner-secret",
            TOKEN.toString(),
            "llmop-request",
            "prompt-secret",
            "REDACTED");
  }

  private static LlmRequest.LlmRequestBuilder request(ExecutionContext context) {
    return LlmRequest.builder()
        .provider("openai")
        .model("gpt-test")
        .messages(List.of(new UserMessage("prompt-secret")))
        .context(context)
        .agentRole("test")
        .stepId("primary");
  }

  private static LlmExecutionOperationAuthority authority(
      LlmExecutionReference reference, String operationId) {
    return LlmExecutionOperationAuthority.issue(
        new LlmExecutionOperationClaim(reference, operationId, 1, 1, TOKEN), "owner-secret");
  }
}
