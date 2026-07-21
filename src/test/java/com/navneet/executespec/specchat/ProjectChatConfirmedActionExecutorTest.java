package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.ProjectDocGeneratorService;
import com.navneet.executespec.codeintel.StalenessService;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codereview.CodeReviewService;
import com.navneet.executespec.codereview.entity.CodeReviewScan;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.RunModelOverrides;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectChatConfirmedActionExecutorTest {

  private final ProjectArchitectureService architecture = mock(ProjectArchitectureService.class);
  private final ProjectDocGeneratorService docs = mock(ProjectDocGeneratorService.class);
  private final CodeReviewService reviews = mock(CodeReviewService.class);
  private final ProjectRepoService repos = mock(ProjectRepoService.class);
  private final StalenessService staleness = mock(StalenessService.class);
  private final LlmExecutionPolicyFactory policies = mock(LlmExecutionPolicyFactory.class);
  private final LlmExecutionReference reference =
      new LlmExecutionReference("ctx-confirmed", LlmExecutionScope.customer("org-1"));
  private final ProjectChatConfirmedActionExecutor executor =
      new ProjectChatConfirmedActionExecutor(
          architecture, docs, reviews, repos, staleness, policies, new ObjectMapper());
  private AppUserDetails principal;

  @BeforeEach
  void setUp() {
    principal =
        new AppUserDetails(
            AppUser.builder()
                .id("developer-1")
                .orgId("org-1")
                .email("developer@example.com")
                .globalRole("USER")
                .status("ACTIVE")
                .build(),
            null);
    when(policies.forProject(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(reference);
  }

  @AfterEach
  void clearRoute() {
    RunModelOverrides.clear();
  }

  @Test
  void pinsExactRouteForDocumentAndRestoresPriorBinding() {
    Map<String, String> prior =
        Map.of(RunModelOverrides.key(LlmProvider.ANTHROPIC, AgentTier.PLANNING), "prior-model");
    RunModelOverrides.bind(prior);
    when(docs.generate(
            eq("project-1"),
            eq(ProjectGeneratedDoc.DocType.SETUP),
            eq("developer-1"),
            eq(LlmProvider.DEEPSEEK),
            eq(reference)))
        .thenAnswer(
            ignored -> {
              for (AgentTier tier : AgentTier.values()) {
                assertThat(RunModelOverrides.lookup(LlmProvider.DEEPSEEK, tier))
                    .isEqualTo("deepseek-v4-pro-native");
              }
              return ProjectGeneratedDoc.builder()
                  .id(UUID.randomUUID())
                  .projectId("project-1")
                  .docType(ProjectGeneratedDoc.DocType.SETUP)
                  .content("setup")
                  .version(2)
                  .build();
            });

    Map<String, Object> result =
        executor.execute(
            ProjectChatConfirmationService.Action.GENERATE_PROJECT_DOC,
            confirmation("GENERATE_PROJECT_DOC", "{\"docType\":\"SETUP\"}"),
            principal);

    assertThat(RunModelOverrides.capture()).isEqualTo(prior);
    assertThat(result)
        .containsEntry("provider", "deepseek")
        .containsEntry("model", "deepseek-v4-pro-native")
        .containsEntry("routePinned", true)
        .containsEntry("status", "COMPLETED");
    verify(policies)
        .forProject(
            eq("project-1"),
            eq("project-chat-confirmed-generate_project_doc"),
            eq("PROJECT_CHAT_CONFIRMED_ACTION"),
            any(),
            any(),
            eq("deepseek"),
            eq("deepseek-v4-pro-native"),
            eq("developer-1"),
            eq(ProjectChatConfirmationService.Action.GENERATE_PROJECT_DOC.budget()));
  }

  @Test
  void failureBurnPathStillRestoresPriorBinding() {
    Map<String, String> prior =
        Map.of(RunModelOverrides.key(LlmProvider.OPENAI, AgentTier.WORKHORSE), "prior-model");
    RunModelOverrides.bind(prior);
    when(docs.generate(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("document failed"));

    assertThatThrownBy(
            () ->
                executor.execute(
                    ProjectChatConfirmationService.Action.GENERATE_PROJECT_DOC,
                    confirmation("GENERATE_PROJECT_DOC", "{\"docType\":\"SETUP\"}"),
                    principal))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("document failed");
    assertThat(RunModelOverrides.capture()).isEqualTo(prior);
  }

  @Test
  void codeReviewReceivesExactRouteAndSharedBudgetReference() {
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo = ProjectRepo.builder().id(repoId).githubRepoFullName("org/repo").build();
    when(repos.resolveGitHubReviewRepoStrict("project-1", repoId, null)).thenReturn(repo);
    CodeReviewScan scan = CodeReviewScan.builder().id(UUID.randomUUID()).status("QUEUED").build();
    when(reviews.submitConfirmedScan(
            "project-1",
            repoId,
            "org/repo",
            principal.getUser(),
            LlmProvider.DEEPSEEK,
            "deepseek-v4-pro-native",
            reference))
        .thenReturn(scan);

    Map<String, Object> result =
        executor.execute(
            ProjectChatConfirmationService.Action.RUN_CODE_REVIEW,
            confirmation("RUN_CODE_REVIEW", "{\"repoId\":\"" + repoId + "\"}"),
            principal);

    assertThat(result).containsEntry("scanId", scan.getId().toString());
    verify(reviews)
        .submitConfirmedScan(
            "project-1",
            repoId,
            "org/repo",
            principal.getUser(),
            LlmProvider.DEEPSEEK,
            "deepseek-v4-pro-native",
            reference);
  }

  private static ProjectChatConfirmation confirmation(String action, String targetJson) {
    return ProjectChatConfirmation.builder()
        .id(UUID.randomUUID())
        .projectId("project-1")
        .orgId("org-1")
        .actorUserId("developer-1")
        .action(action)
        .capability("SPEND_AI")
        .provider("deepseek")
        .model("deepseek-v4-pro-native")
        .targetJson(targetJson)
        .targetHash("a".repeat(64))
        .scopeHash("b".repeat(64))
        .tokenHash("c".repeat(64))
        .expiresAt(Instant.now().plusSeconds(300))
        .status(ProjectChatConfirmation.Status.CONSUMED)
        .consumedAt(Instant.now())
        .build();
  }
}
