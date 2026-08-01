package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.RepoContext;

import org.junit.jupiter.api.Test;

class AgentIntentPatternsTest {

  @Test
  void runIdExtractionAcceptsOnlyTheCanonicalRunIdShape() {
    assertThat(AgentIntentPatterns.extractRunId("approve run 9586cfee-f442-4927-90f4-ba4f5f645d0b"))
        .isEqualTo("9586cfee-f442-4927-90f4-ba4f5f645d0b");
    assertThat(AgentIntentPatterns.extractRunId("approve it")).isNull();
    // A bare hex run is not a run id: commit SHAs, hex-looking words and long digit strings all
    // matched the old pattern and produced a credit-charging card for the wrong target.
    assertThat(AgentIntentPatterns.extractRunId("cancel deadbeef")).isNull();
    assertThat(AgentIntentPatterns.extractRunId("approve the fix in commit abc1234def")).isNull();
    assertThat(AgentIntentPatterns.extractRunId("cancel order 12345678")).isNull();
    assertThat(AgentIntentPatterns.extractRunId(null)).isNull();
  }

  @Test
  void readOnlyRoutingPrefersReviewThenPlanThenInspection() {
    assertThat(AgentIntentPatterns.routeForReadOnly("review this without edits"))
        .isEqualTo("REVIEW_ONLY");
    assertThat(AgentIntentPatterns.routeForReadOnly("write a plan but no code changes"))
        .isEqualTo("PLAN_ONLY");
    assertThat(AgentIntentPatterns.routeForReadOnly("explain this repo"))
        .isEqualTo("READ_ONLY_CODE_HELP");
    assertThat(AgentIntentPatterns.routeForReadOnly("chat only about launch"))
        .isEqualTo("CHAT_ONLY");
  }

  @Test
  void vagueMutationRequiresProjectOrChangedFileEvidence() {
    assertThat(AgentIntentPatterns.looksLikeVagueMutation("fix this", request(null, null)))
        .isTrue();
    assertThat(AgentIntentPatterns.looksLikeVagueMutation("fix this", request("project-1", null)))
        .isFalse();
    assertThat(
            AgentIntentPatterns.looksLikeVagueMutation(
                "fix this", request(null, List.of("src/App.tsx"))))
        .isFalse();
  }

  @Test
  void localActionVocabularyCatchesGitAndShellWithoutCloudRunIntent() {
    assertThat(AgentIntentPatterns.looksLikeLocalDeterministicAction("commit all files and push"))
        .isTrue();
    assertThat(AgentIntentPatterns.looksLikeLocalDeterministicAction("git diff")).isTrue();
    assertThat(AgentIntentPatterns.looksLikeLocalDeterministicAction("shell command npm test"))
        .isTrue();
    assertThat(AgentIntentPatterns.looksLikeLocalDeterministicAction("implement oauth login"))
        .isFalse();
  }

  @Test
  void unsafeAndNoEditPhrasesAreExplicitlySeparated() {
    assertThat(AgentIntentPatterns.isUnsafe("drop database and dump secrets")).isTrue();
    assertThat(AgentIntentPatterns.isUnsafe("review database migration")).isFalse();
    assertThat(AgentIntentPatterns.forbidsEdits("generate a patch in chat only")).isTrue();
    assertThat(AgentIntentPatterns.forbidsEdits("implement the patch")).isFalse();
  }

  private static InterpretIntentRequest request(String projectId, List<String> changedFiles) {
    RepoContext repo =
        changedFiles == null
            ? null
            : new RepoContext(
                "repo",
                "git@github.com:navneetprabhakar/repo.git",
                "main",
                changedFiles,
                List.of("java"));
    return new InterpretIntentRequest(
        "session", "fix this", projectId, repo, new ClientCapabilities(true, true, true, true));
  }

  @org.junit.jupiter.api.Test
  void interrogativeQuestionsAboutCodeAreNotCodeChanges() {
    // P1-3 (2026-08-01): this exact live phrasing produced a paid cloud-run proposal on dev.
    org.assertj.core.api.Assertions.assertThat(
            AgentIntentPatterns.looksLikeCodeChange(
                "looking only at the montecarlo repository linked to this project: which files"
                    + " implement the core simulation logic? name the exact file paths you can"
                    + " actually see in the repository."))
        .isFalse();
    org.assertj.core.api.Assertions.assertThat(
            AgentIntentPatterns.looksLikeCodeChange("what implements the retry backoff?"))
        .isFalse();
    org.assertj.core.api.Assertions.assertThat(
            AgentIntentPatterns.looksLikeCodeChange("where is the payment webhook added?"))
        .isFalse();
    org.assertj.core.api.Assertions.assertThat(
            AgentIntentPatterns.looksLikeCodeChange("which class creates the jwt tokens?"))
        .isFalse();
    // Interrogative questions about code route to the grounded read-only surface, not bare chat.
    org.assertj.core.api.Assertions.assertThat(
            AgentIntentPatterns.looksLikeReadOnlyCodeHelp("which files implement the simulation?"))
        .isTrue();
  }

  @org.junit.jupiter.api.Test
  void imperativeAndPoliteMutationRequestsRemainCodeChanges() {
    org.assertj.core.api.Assertions.assertThat(
            AgentIntentPatterns.looksLikeCodeChange("can you implement the retry backoff?"))
        .isTrue();
    org.assertj.core.api.Assertions.assertThat(
            AgentIntentPatterns.looksLikeCodeChange(
                "explain the auth flow. then implement rate limiting on the login endpoint."))
        .isTrue();
    org.assertj.core.api.Assertions.assertThat(
            AgentIntentPatterns.looksLikeCodeChange("i want to build a simple web ui todo app"))
        .isTrue();
    org.assertj.core.api.Assertions.assertThat(
            AgentIntentPatterns.looksLikeCodeChange("add a check that the user is active"))
        .isTrue();
    org.assertj.core.api.Assertions.assertThat(
            AgentIntentPatterns.looksLikeCodeChange("please fix the failing login test"))
        .isTrue();
  }
}
