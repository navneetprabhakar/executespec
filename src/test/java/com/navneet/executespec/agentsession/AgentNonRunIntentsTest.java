package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.RepoContext;

import org.junit.jupiter.api.Test;

class AgentNonRunIntentsTest {

  @Test
  void localDeterministicActionStaysClientSide() {
    var decision = decideBefore("commit all files and push to git", "project-1");

    assertThat(decision.route()).isEqualTo("LOCAL_TOOL_ACTION");
    assertThat(decision.reasonCode()).isEqualTo("deterministic_local_action");
    assertThat(decision.proposedAction().type()).isEqualTo("local_tool_action");
    assertThat(decision.proposedAction().requiresConfirmation()).isTrue();
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void reviewAndPlanRequestsForbidRunSubmit() {
    var review = decideBefore("Review this PR and list findings", "project-1");
    var plan = decideBefore("Write a plan to add OAuth", "project-1");

    assertThat(review.route()).isEqualTo("REVIEW_ONLY");
    assertThat(review.proposedAction()).isNull();
    assertThat(review.policy().runApiAllowed()).isFalse();
    assertThat(plan.route()).isEqualTo("PLAN_ONLY");
    assertThat(plan.proposedAction()).isNull();
    assertThat(plan.policy().runApiAllowed()).isFalse();
  }

  @Test
  void vagueMutationClarifiesBeforeCodeChangeRouting() {
    var decision = decideBefore("fix this", null);

    assertThat(decision.route()).isEqualTo("CLARIFY");
    assertThat(decision.reasonCode()).isEqualTo("mutation_request_missing_target");
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void readOnlyCodeHelpStaysNonRunAfterCodeChangeRouting() {
    var decision = decideAfter("Explain this repo", "project-1", null);

    assertThat(decision.route()).isEqualTo("READ_ONLY_CODE_HELP");
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void costQueryUsesReadOnlyLifecycleAction() {
    var decision = decideAfter("what was the cost for this run?", "project-1", null);

    assertThat(decision.route()).isEqualTo("RUN_LIFECYCLE_ACTION");
    assertThat(decision.reasonCode()).isEqualTo("cost_or_billing_query");
    assertThat(decision.proposedAction().type()).isEqualTo("cost_query");
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void projectResolutionCarriesGitRemoteWithoutRunSubmit() {
    var repo =
        new RepoContext(
            "executespec-cli",
            "git@github.com:navneetprabhakar/executespec-cli.git",
            "main",
            List.of(),
            List.of("typescript"));
    var decision = decideAfter("which project is this repo linked to?", null, repo);

    assertThat(decision.route()).isEqualTo("LOCAL_TOOL_ACTION");
    assertThat(decision.proposedAction().type()).isEqualTo("resolve_project");
    assertThat(decision.proposedAction().arguments())
        .containsEntry("gitRemote", "git@github.com:navneetprabhakar/executespec-cli.git");
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void unrelatedPromptsAreNotClaimed() {
    var request = request("hello", "project-1", null);

    assertThat(AgentNonRunIntents.decideBeforeCodeChange("hello", "hello", request)).isEmpty();
    assertThat(AgentNonRunIntents.decideAfterCodeChange("hello", "hello", request)).isEmpty();
  }

  private static OrchestratorDecision decideBefore(String message, String projectId) {
    return AgentNonRunIntents.decideBeforeCodeChange(
            message, message.toLowerCase(Locale.ROOT), request(message, projectId, null))
        .orElseThrow();
  }

  private static OrchestratorDecision decideAfter(
      String message, String projectId, RepoContext repo) {
    return AgentNonRunIntents.decideAfterCodeChange(
            message, message.toLowerCase(Locale.ROOT), request(message, projectId, repo))
        .orElseThrow();
  }

  private static InterpretIntentRequest request(
      String message, String projectId, RepoContext repo) {
    return new InterpretIntentRequest("s1", message, projectId, repo, null);
  }
}
