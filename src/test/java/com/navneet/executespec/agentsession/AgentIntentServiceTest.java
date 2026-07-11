package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.RepoContext;
import com.navneet.executespec.router.ModelRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class AgentIntentServiceTest {

  private final AgentIntentService service = new AgentIntentService();

  @Test
  void codeChangeWithoutLocalToolsProposesCloudRunButRequiresConfirmation() {
    var decision =
        service.interpret(
            new InterpretIntentRequest(
                "s1",
                "fix the failing auth refresh test",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.intent()).isEqualTo("CREATE_RUN");
    assertThat(decision.needsConfirmation()).isTrue();
    assertThat(decision.proposedAction().type()).isEqualTo("create_cloud_run");
    assertThat(decision.proposedAction().billingImpact()).isEqualTo("run_credit_on_plan_approval");
    assertThat(decision.proposedAction().arguments()).containsEntry("projectId", "project-1");
  }

  @Test
  void explicitCodeChangeHasRunAllowedOnlyAfterConfirmation() {
    var decision =
        service.decide(
            new InterpretIntentRequest(
                "s1",
                "implement OAuth login",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.route()).isEqualTo("CODE_RUN_REQUIRED");
    assertThat(decision.requiresUserConfirmation()).isTrue();
    assertThat(decision.proposedAction().type()).isEqualTo("create_cloud_run");
    assertThat(decision.policy().runApiAllowed()).isTrue();
    assertThat(decision.policy().confirmationRequiredBeforeSubmit()).isTrue();
  }

  @Test
  void codeRunRequestWithFutureApprovalLanguageStillCreatesCloudRunProposal() {
    var decision =
        service.decide(
            new InterpretIntentRequest(
                "s1",
                "Submit a coding run to add README launch notes. Stop at the plan gate and do not"
                    + " code until I approve.",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.route()).isEqualTo("CODE_RUN_REQUIRED");
    assertThat(decision.reasonCode()).isEqualTo("explicit_code_mutation_request");
    assertThat(decision.requiresUserConfirmation()).isTrue();
    assertThat(decision.proposedAction().type()).isEqualTo("create_cloud_run");
    assertThat(decision.policy().runApiAllowed()).isTrue();
  }

  @Test
  void codeChangeWithLocalToolsProposesLocalTaskButStillRequiresConfirmation() {
    var decision =
        service.interpret(
            new InterpretIntentRequest(
                "s1",
                "implement retries in the webhook handler",
                "project-1",
                null,
                new ClientCapabilities(true, true, true, true)));

    assertThat(decision.intent()).isEqualTo("LOCAL_CODE_TASK");
    assertThat(decision.needsConfirmation()).isTrue();
    assertThat(decision.proposedAction().type()).isEqualTo("local_code_task");
    assertThat(decision.proposedAction().billingImpact()).isEqualTo("conversation_usage");
  }

  @Test
  void explainRepoIsReadOnlyAndForbidsRunSubmit() {
    var decision =
        service.decide(
            new InterpretIntentRequest(
                "s1",
                "Explain this repo",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.route()).isEqualTo("READ_ONLY_CODE_HELP");
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void reviewRequestIsReviewOnlyAndForbidsRunSubmit() {
    var decision =
        service.decide(
            new InterpretIntentRequest(
                "s1",
                "Review this PR and list findings",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.route()).isEqualTo("REVIEW_ONLY");
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void planRequestIsPlanOnlyAndForbidsRunSubmit() {
    var decision =
        service.decide(
            new InterpretIntentRequest(
                "s1",
                "Write a plan to add OAuth",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.route()).isEqualTo("PLAN_ONLY");
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void findBugIsInvestigationNotRun() {
    var decision =
        service.decide(
            new InterpretIntentRequest(
                "s1",
                "Find the auth bug",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.route()).isEqualTo("READ_ONLY_CODE_HELP");
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void chatOnlyPatchRequestDoesNotCreateRun() {
    var decision =
        service.decide(
            new InterpretIntentRequest(
                "s1",
                "Generate a patch in chat only",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.route()).isEqualTo("CHAT_ONLY");
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void commitAndPushIsLocalToolActionNotCloudRun() {
    var decision =
        service.decide(
            new InterpretIntentRequest(
                "s1",
                "commit all files and push to git",
                "project-1",
                null,
                new ClientCapabilities(true, true, true, true)));

    assertThat(decision.route()).isEqualTo("LOCAL_TOOL_ACTION");
    assertThat(decision.proposedAction().type()).isEqualTo("local_tool_action");
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void destructiveRequestIsRejectedAndForbidsRunSubmit() {
    var decision =
        service.decide(
            new InterpretIntentRequest(
                "s1",
                "delete everything",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.route()).isEqualTo("SECURITY_REJECT");
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void approvalWithoutRunIdAsksForClarification() {
    var decision =
        service.interpret(
            new InterpretIntentRequest(
                "s1",
                "approve it",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.intent()).isEqualTo("CHAT");
    assertThat(decision.needsConfirmation()).isFalse();
    assertThat(decision.response()).contains("Which run");
  }

  @Test
  void approvalWithRunIdReturnsConfirmationAction() {
    String runId = "9586cfee-f442-4927-90f4-ba4f5f645d0b";

    var decision =
        service.interpret(
            new InterpretIntentRequest(
                "s1",
                "approve run " + runId,
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.intent()).isEqualTo("APPROVE_GATE");
    assertThat(decision.needsConfirmation()).isTrue();
    assertThat(decision.proposedAction().type()).isEqualTo("approve_run");
    assertThat(decision.proposedAction().billingImpact()).isEqualTo("run_credit");
    assertThat(decision.proposedAction().arguments()).containsEntry("runId", runId);
  }

  @Test
  void quotedFixUsesStructuredClassifierAndDoesNotBecomeACloudRun() {
    LlmRouter router = mock(LlmRouter.class);
    ModelRegistry registry = mock(ModelRegistry.class);
    when(registry.lightweightModelFor("anthropic")).thenReturn("claude-haiku-4-5-20251001");
    when(router.callText(
            eq("anthropic"),
            eq("claude-haiku-4-5-20251001"),
            anyList(),
            eq("intent-routing-" + Integer.toHexString("Please handle \"fix\".".hashCode())),
            eq("AgentIntentService"),
            eq("orchestrator.intent.classify"),
            eq(400)))
        .thenReturn(
            ok(
                "{\"route\":\"CLARIFY\",\"confidence\":0.95,\"reasonCode\":\"ambiguous\",\"requiresUserConfirmation\":false,\"negativeEvidence\":[]}"));

    AgentIntentService routed =
        new AgentIntentService(
            router, registry, new ObjectMapper(), new OrchestratorRoutingPromptCatalog());
    var decision =
        routed.decide(
            new InterpretIntentRequest(
                "s1",
                "Please handle \"fix\".",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.route()).isEqualTo("CLARIFY");
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void structuredReadOnlyDecisionIsAcceptedThroughCanonicalTextSeam() {
    LlmRouter router = mock(LlmRouter.class);
    ModelRegistry registry = mock(ModelRegistry.class);
    when(registry.lightweightModelFor("anthropic")).thenReturn("claude-haiku-4-5-20251001");
    when(router.callText(
            eq("anthropic"),
            eq("claude-haiku-4-5-20251001"),
            anyList(),
            anyString(),
            eq("AgentIntentService"),
            eq("orchestrator.intent.classify"),
            eq(400)))
        .thenReturn(
            ok(
                "{\"route\":\"READ_ONLY_CODE_HELP\",\"confidence\":0.9,\"reasonCode\":\"inspect\",\"requiresUserConfirmation\":false,\"negativeEvidence\":[]}"));

    AgentIntentService routed =
        new AgentIntentService(
            router, registry, new ObjectMapper(), new OrchestratorRoutingPromptCatalog());
    var decision =
        routed.decide(
            new InterpretIntentRequest(
                "s1",
                "Could you tell me what changed?",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.route()).isEqualTo("READ_ONLY_CODE_HELP");
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void malformedStructuredOutputFallsBackWithoutCreatingRun() {
    LlmRouter router = mock(LlmRouter.class);
    ModelRegistry registry = mock(ModelRegistry.class);
    when(registry.lightweightModelFor("anthropic")).thenReturn("claude-haiku-4-5-20251001");
    when(router.callText(
            eq("anthropic"),
            eq("claude-haiku-4-5-20251001"),
            anyList(),
            anyString(),
            eq("AgentIntentService"),
            eq("orchestrator.intent.classify"),
            eq(400)))
        .thenReturn(ok("not-json"));

    AgentIntentService routed =
        new AgentIntentService(
            router, registry, new ObjectMapper(), new OrchestratorRoutingPromptCatalog());
    var decision =
        routed.decide(
            new InterpretIntentRequest(
                "s1",
                "What is the weather?",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.route()).isEqualTo("CHAT_ONLY");
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void explicitImplementationRemainsDeterministicAndDoesNotCallClassifier() {
    LlmRouter router = mock(LlmRouter.class);
    ModelRegistry registry = mock(ModelRegistry.class);
    AgentIntentService routed =
        new AgentIntentService(
            router, registry, new ObjectMapper(), new OrchestratorRoutingPromptCatalog());

    var decision =
        routed.decide(
            new InterpretIntentRequest(
                "s1",
                "implement OAuth login",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.route()).isEqualTo("CODE_RUN_REQUIRED");
    verify(router, never())
        .callText(
            anyString(), anyString(), anyList(), anyString(), anyString(), anyString(), eq(400));
  }

  @Test
  void unavailableClassifierFallsBackToSafeChat() {
    LlmRouter router = mock(LlmRouter.class);
    ModelRegistry registry = mock(ModelRegistry.class);
    when(registry.lightweightModelFor("anthropic")).thenReturn(null);
    AgentIntentService routed =
        new AgentIntentService(
            router, registry, new ObjectMapper(), new OrchestratorRoutingPromptCatalog());

    var decision =
        routed.decide(
            new InterpretIntentRequest(
                "s1",
                "Tell me something unrelated",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.route()).isEqualTo("CHAT_ONLY");
    verify(router, never())
        .callText(
            anyString(), anyString(), anyList(), anyString(), anyString(), anyString(), eq(400));
  }

  private static ChatResponse ok(String text) {
    return new ChatResponse(
        List.of(new Generation(AssistantMessage.builder().content(text).build())));
  }

  @Test
  void gateListingIsReadOnly() {
    var decision =
        service.interpret(
            new InterpretIntentRequest(
                "s1",
                "what runs are awaiting approval?",
                "project-1",
                null,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.intent()).isEqualTo("LIST_GATES");
    assertThat(decision.needsConfirmation()).isFalse();
    assertThat(decision.proposedAction().type()).isEqualTo("list_gates");
    assertThat(decision.proposedAction().billingImpact()).isEqualTo("none");
  }

  @Test
  void projectQuestionProposesContextResolution() {
    var repo =
        new RepoContext(
            "executespec-cli",
            "git@github.com:navneetprabhakar/executespec-cli.git",
            "main",
            List.of(),
            List.of("typescript"));

    var decision =
        service.interpret(
            new InterpretIntentRequest(
                "s1",
                "which project is this repo linked to?",
                null,
                repo,
                new ClientCapabilities(false, true, true, true)));

    assertThat(decision.intent()).isEqualTo("RESOLVE_PROJECT");
    assertThat(decision.needsConfirmation()).isFalse();
    assertThat(decision.proposedAction().type()).isEqualTo("resolve_project");
  }
}
