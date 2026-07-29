package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

class LlmExecutionOperationAdmissionPolicyTest {

  private static final LlmExecutionReference REFERENCE =
      new LlmExecutionReference("llmctx-policy", LlmExecutionScope.customer("org-a"));

  @Test
  void codeDefaultAndBlankConfigurationAreLegacy() {
    assertThat(new LlmExecutionOperationAdmissionPolicy(null).mode())
        .isEqualTo(LlmExecutionOperationAdmissionMode.LEGACY);
    assertThat(new LlmExecutionOperationAdmissionPolicy("  ").mode())
        .isEqualTo(LlmExecutionOperationAdmissionMode.LEGACY);
    assertThat(new LlmExecutionOperationAdmissionPolicy("legacy").mode())
        .isEqualTo(LlmExecutionOperationAdmissionMode.LEGACY);
  }

  @Test
  void invalidModeFailsClosedInsteadOfFallingBack() {
    assertThatThrownBy(() -> new LlmExecutionOperationAdmissionPolicy("optional"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("LEGACY, SHADOW, or ENFORCED");
  }

  @Test
  void legacyKeepsDurableAndContextFreeCallersOnVersionZero() {
    var policy = new LlmExecutionOperationAdmissionPolicy("LEGACY");

    assertDecision(
        policy.decide(REFERENCE, authority()),
        LlmExecutionOperationAdmissionPolicy.Action.ALLOW_LEGACY_V0,
        LlmExecutionOperationAdmissionPolicy.Reason.LEGACY_MODE,
        false);
    assertDecision(
        policy.decide(REFERENCE, null),
        LlmExecutionOperationAdmissionPolicy.Action.ALLOW_LEGACY_V0,
        LlmExecutionOperationAdmissionPolicy.Reason.LEGACY_MODE,
        false);
    assertDecision(
        policy.decide(null, null),
        LlmExecutionOperationAdmissionPolicy.Action.ALLOW_LEGACY_V0,
        LlmExecutionOperationAdmissionPolicy.Reason.CONTEXT_FREE_LEGACY_COMPATIBILITY,
        false);
  }

  @Test
  void shadowUsesAtomicWhenPresentAndDiagnosesTemporaryFallbacks() {
    var policy = new LlmExecutionOperationAdmissionPolicy("SHADOW");

    assertDecision(
        policy.decide(REFERENCE, authority()),
        LlmExecutionOperationAdmissionPolicy.Action.USE_ATOMIC_V1,
        LlmExecutionOperationAdmissionPolicy.Reason.AUTHORITY_PRESENT,
        false);
    assertDecision(
        policy.decide(REFERENCE, null),
        LlmExecutionOperationAdmissionPolicy.Action.ALLOW_LEGACY_V0,
        LlmExecutionOperationAdmissionPolicy.Reason.MISSING_AUTHORITY_SHADOW_FALLBACK,
        true);
    assertDecision(
        policy.decide(null, null),
        LlmExecutionOperationAdmissionPolicy.Action.ALLOW_LEGACY_V0,
        LlmExecutionOperationAdmissionPolicy.Reason.CONTEXT_FREE_SHADOW_COMPATIBILITY,
        true);
  }

  @Test
  void enforcedRequiresAuthorityForDurableAndRejectsContextFreeCallers() {
    var policy = new LlmExecutionOperationAdmissionPolicy("ENFORCED");

    assertThat(policy.decide(REFERENCE, authority()).usesAtomicV1()).isTrue();
    assertDecision(
        policy.decide(REFERENCE, null),
        LlmExecutionOperationAdmissionPolicy.Action.REJECT_BEFORE_TRANSPORT,
        LlmExecutionOperationAdmissionPolicy.Reason.MISSING_AUTHORITY_ENFORCED,
        false);
    assertDecision(
        policy.decide(null, null),
        LlmExecutionOperationAdmissionPolicy.Action.REJECT_BEFORE_TRANSPORT,
        LlmExecutionOperationAdmissionPolicy.Reason.CONTEXT_FREE_REJECTED,
        false);
  }

  @Test
  void authorityWithoutReferenceIsRejectedInEveryMode() {
    for (String mode : List.of("LEGACY", "SHADOW", "ENFORCED")) {
      assertDecision(
          new LlmExecutionOperationAdmissionPolicy(mode).decide(null, authority()),
          LlmExecutionOperationAdmissionPolicy.Action.REJECT_BEFORE_TRANSPORT,
          LlmExecutionOperationAdmissionPolicy.Reason.AUTHORITY_WITHOUT_DURABLE_REFERENCE,
          false);
    }
  }

  @Test
  void requestDecisionUsesOnlyItsValidatedDurableIdentityAndOpaqueAuthorityPresence() {
    LlmExecutionOperationAuthority authority = authority();
    ExecutionContext context =
        new ExecutionContext("run-a", "task-a", Path.of("/tmp/policy"), REFERENCE, authority);
    LlmRequest request =
        LlmRequest.builder()
            .provider("openai")
            .model("gpt-test")
            .messages(List.of(new UserMessage("test")))
            .context(context)
            .build();

    var decision = new LlmExecutionOperationAdmissionPolicy("SHADOW").decide(request);

    assertThat(decision.usesAtomicV1()).isTrue();
    assertThat(decision.diagnosticRequired()).isFalse();
  }

  private static LlmExecutionOperationAuthority authority() {
    return LlmExecutionOperationAuthority.issue(
        new LlmExecutionOperationClaim(
            REFERENCE,
            "llmop-policy",
            1,
            1,
            UUID.fromString("00000000-0000-0000-0000-000000000123")),
        "owner-a");
  }

  private static void assertDecision(
      LlmExecutionOperationAdmissionPolicy.Decision decision,
      LlmExecutionOperationAdmissionPolicy.Action action,
      LlmExecutionOperationAdmissionPolicy.Reason reason,
      boolean diagnosticRequired) {
    assertThat(decision.action()).isEqualTo(action);
    assertThat(decision.reason()).isEqualTo(reason);
    assertThat(decision.diagnosticRequired()).isEqualTo(diagnosticRequired);
    assertThat(decision.usesAtomicV1())
        .isEqualTo(action == LlmExecutionOperationAdmissionPolicy.Action.USE_ATOMIC_V1);
    assertThat(decision.allowsLegacyV0())
        .isEqualTo(action == LlmExecutionOperationAdmissionPolicy.Action.ALLOW_LEGACY_V0);
    assertThat(decision.rejectsBeforeTransport())
        .isEqualTo(action == LlmExecutionOperationAdmissionPolicy.Action.REJECT_BEFORE_TRANSPORT);
  }
}
