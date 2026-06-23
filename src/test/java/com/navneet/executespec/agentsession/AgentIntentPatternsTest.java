package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.RepoContext;

import org.junit.jupiter.api.Test;

class AgentIntentPatternsTest {

  @Test
  void runIdExtractionAcceptsUuidAndShortHexIds() {
    assertThat(AgentIntentPatterns.extractRunId("approve run 9586cfee-f442-4927-90f4-ba4f5f645d0b"))
        .isEqualTo("9586cfee-f442-4927-90f4-ba4f5f645d0b");
    assertThat(AgentIntentPatterns.extractRunId("cancel deadbeef")).isEqualTo("deadbeef");
    assertThat(AgentIntentPatterns.extractRunId("approve it")).isNull();
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
}
