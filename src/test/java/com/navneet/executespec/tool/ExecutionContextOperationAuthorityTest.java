package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.executioncontext.LlmExecutionOperationAuthority;
import com.navneet.executespec.executioncontext.LlmExecutionOperationClaim;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;

import org.junit.jupiter.api.Test;

class ExecutionContextOperationAuthorityTest {

  private static final LlmExecutionReference REFERENCE =
      new LlmExecutionReference("llmctx-authority", LlmExecutionScope.customer("org-a"));
  private static final UUID TOKEN = UUID.fromString("00000000-0000-0000-0000-000000000456");

  @Test
  void childPreservesTheExactOpaqueAuthorityAndDurableReference() {
    LlmExecutionOperationAuthority authority = authority(REFERENCE, "llmop-parent");
    ExecutionContext parent =
        new ExecutionContext(
            "run-a", "parent", Path.of("/tmp/authority-context"), REFERENCE, authority);

    ExecutionContext child = parent.child("child");

    assertThat(child.llmExecutionContextId()).isEqualTo(REFERENCE.contextId());
    assertThat(child.llmExecutionScope()).isEqualTo(REFERENCE.scope());
    assertThat(child.llmExecutionOperationAuthority()).isSameAs(authority);
  }

  @Test
  void authorityRequiresDurableIdentityAndCannotBeReconstructedFromIt() {
    LlmExecutionOperationAuthority authority = authority(REFERENCE, "llmop-parent");

    assertThatThrownBy(
            () ->
                new ExecutionContext(
                    "run-a", "task-a", Path.of("/tmp/authority-context"), null, null, authority))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires a durable execution reference");

    ExecutionContext identityOnly =
        new ExecutionContext("run-a", "task-a", Path.of("/tmp/authority-context"), REFERENCE);
    assertThat(identityOnly.llmExecutionOperationAuthority()).isNull();
  }

  @Test
  void serializationAndDiagnosticRenderingOmitAuthority() throws Exception {
    LlmExecutionOperationAuthority authority = authority(REFERENCE, "llmop-parent");
    ExecutionContext context =
        new ExecutionContext(
            "run-a", "task-a", Path.of("/tmp/authority-context"), REFERENCE, authority);

    String json = new ObjectMapper().writeValueAsString(context);

    assertThat(json)
        .doesNotContain(
            "llmExecutionOperationAuthority",
            "owner-secret",
            TOKEN.toString(),
            "llmop-parent",
            "REDACTED");
    assertThat(context.toString())
        .doesNotContain(
            "llmExecutionOperationAuthority",
            "owner-secret",
            TOKEN.toString(),
            "llmop-parent",
            "REDACTED");
  }

  private static LlmExecutionOperationAuthority authority(
      LlmExecutionReference reference, String operationId) {
    return LlmExecutionOperationAuthority.issue(
        new LlmExecutionOperationClaim(reference, operationId, 1, 1, TOKEN), "owner-secret");
  }
}
