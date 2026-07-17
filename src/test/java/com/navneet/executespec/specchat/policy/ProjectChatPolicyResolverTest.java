package com.navneet.executespec.specchat.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.ProjectMember;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.executioncontext.LlmExecutionContext;
import com.navneet.executespec.executioncontext.LlmExecutionContextService;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.executioncontext.LlmPolicySnapshot;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.specchat.ChatToolPolicy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

class ProjectChatPolicyResolverTest {

  private final ProjectAccessGuard accessGuard = mock(ProjectAccessGuard.class);
  private final ProjectMemberRepository members = mock(ProjectMemberRepository.class);
  private final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
  private final LlmExecutionPolicyFactory policies = mock(LlmExecutionPolicyFactory.class);
  private final LlmExecutionContextService contexts = mock(LlmExecutionContextService.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<ProjectChatConfirmationAuthority> confirmations =
      mock(ObjectProvider.class);

  private final ProjectChatPolicyResolver resolver =
      new ProjectChatPolicyResolver(
          accessGuard, members, sessions, policies, contexts, confirmations);

  private final Project project =
      Project.builder().id("project-1").orgId("org-1").createdBy("owner-1").name("Project").build();
  private final AppUserDetails principal =
      new AppUserDetails(
          AppUser.builder()
              .id("actor-1")
              .email("actor@example.com")
              .firstName("Actor")
              .orgId("org-1")
              .globalRole("USER")
              .status("ACTIVE")
              .build(),
          null);

  @BeforeEach
  void setup() {
    when(accessGuard.assertCanRead("project-1", principal)).thenReturn(project);
    when(sessions.findById("session-1")).thenReturn(Optional.empty());
    when(confirmations.orderedStream()).thenReturn(Stream.empty());
  }

  @Test
  void projectViewerCannotCreatePaidChatExecutionContext() {
    when(members.findByProjectIdAndUserId("project-1", "actor-1"))
        .thenReturn(Optional.of(member("PROJECT_VIEWER")));

    assertThatThrownBy(() -> resolver.resolve(request()))
        .isInstanceOf(ProjectChatPolicyDeniedException.class)
        .hasMessageContaining("PROJECT_VIEWER");

    verify(policies, never())
        .forChatTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void resolvesExactActorTenantRoleRoutingKeysBudgetsTrustAndDurableReference() {
    when(members.findByProjectIdAndUserId("project-1", "actor-1"))
        .thenReturn(Optional.of(member("PROJECT_DEVELOPER")));
    stubExecutionContext();

    ProjectChatPolicy policy = resolver.resolve(request());

    assertThat(policy.actorId()).isEqualTo("actor-1");
    assertThat(policy.actorOrgId()).isEqualTo("org-1");
    assertThat(policy.orgId()).isEqualTo("org-1");
    assertThat(policy.projectId()).isEqualTo("project-1");
    assertThat(policy.effectiveProjectRole())
        .isEqualTo(ProjectChatPolicy.EffectiveProjectRole.PROJECT_DEVELOPER);
    assertThat(policy.allowedCapabilities())
        .containsExactlyInAnyOrder(ChatToolPolicy.Capability.values());
    assertThat(policy.allowedProviders()).containsExactly("deepseek");
    assertThat(policy.allowedProviderModels()).containsEntry("deepseek", List.of("deepseek-chat"));
    assertThat(policy.keySourceMode()).isEqualTo(LlmPolicySnapshot.KeySourceMode.OWN_KEYS);
    assertThat(policy.providerKeySources())
        .containsEntry("deepseek", LlmPolicySnapshot.CredentialSource.ORG);
    assertThat(policy.budgets().maxToolCalls()).isEqualTo(ChatToolPolicy.MAX_TOOL_CALLS);
    assertThat(policy.budgets().maxModelRounds()).isEqualTo(ChatToolPolicy.MAX_MODEL_ROUNDS + 1);
    assertThat(policy.confirmation().state()).isEqualTo(ProjectChatPolicy.ConfirmationState.NONE);
    assertThat(policy.sourceTrustLabels())
        .containsEntry(
            ProjectChatPolicy.SourceKind.SYSTEM_POLICY,
            ProjectChatPolicy.TrustLabel.TRUSTED_AUTHORITY)
        .containsEntry(
            ProjectChatPolicy.SourceKind.TOOL_RESULT, ProjectChatPolicy.TrustLabel.UNTRUSTED_DATA);
    assertThat(policy.auditIdentity().correlationId()).isEqualTo("correlation-1");
    assertThat(policy.executionReference().contextId()).isEqualTo("llmctx-chat-1");
  }

  @Test
  void everyToolInvocationRevalidatesRoleAndStopsBeforeDelegateOnDowngrade() {
    when(members.findByProjectIdAndUserId("project-1", "actor-1"))
        .thenReturn(Optional.of(member("PROJECT_DEVELOPER")));
    stubExecutionContext();
    ProjectChatPolicy policy = resolver.resolve(request());
    ProjectChatPolicyResolver.TurnGuard guard = resolver.newGuard(policy, principal);
    AtomicInteger dispatches = new AtomicInteger();
    ToolCallback guarded = guard.guardTools(List.of(tool(dispatches))).getFirst();

    when(members.findByProjectIdAndUserId("project-1", "actor-1"))
        .thenReturn(Optional.of(member("PROJECT_VIEWER")));

    assertThatThrownBy(() -> guarded.call("{}"))
        .isInstanceOf(ProjectChatPolicyDeniedException.class)
        .hasMessageContaining("role changed");
    assertThat(dispatches).hasValue(0);
  }

  @Test
  void narrowConfirmationAuthorityCanGrantOnlyAnAllowedExactCapability() {
    when(members.findByProjectIdAndUserId("project-1", "actor-1"))
        .thenReturn(Optional.of(member("PROJECT_DEVELOPER")));
    stubExecutionContext();
    ProjectChatConfirmationAuthority authority = mock(ProjectChatConfirmationAuthority.class);
    when(confirmations.orderedStream()).thenAnswer(ignored -> Stream.of(authority));
    when(authority.resolve(any()))
        .thenReturn(
            new ProjectChatConfirmationAuthority.ConfirmationGrant(
                "confirmation-1",
                Instant.now().plusSeconds(60),
                Set.of(ChatToolPolicy.Capability.PROJECT_ANALYSE)));

    ProjectChatPolicy policy = resolver.resolve(request());

    assertThat(policy.confirmation().state())
        .isEqualTo(ProjectChatPolicy.ConfirmationState.CONFIRMED);
    assertThat(policy.confirmation().reference()).isEqualTo("confirmation-1");
    assertThat(policy.confirmation().confirmedCapabilities())
        .containsExactly(ChatToolPolicy.Capability.PROJECT_ANALYSE);
  }

  private ProjectChatPolicyResolver.TurnRequest request() {
    return new ProjectChatPolicyResolver.TurnRequest(
        principal,
        "org-1",
        "project-1",
        "session-1",
        "turn-1",
        "correlation-1",
        "spec-chat",
        "deepseek",
        null);
  }

  private void stubExecutionContext() {
    LlmExecutionReference reference =
        new LlmExecutionReference("llmctx-chat-1", LlmExecutionScope.customer("org-1"));
    when(policies.forChatTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(reference);
    LlmExecutionContext context = mock(LlmExecutionContext.class);
    when(context.getUserId()).thenReturn("actor-1");
    when(context.getOrgId()).thenReturn("org-1");
    when(context.getProjectId()).thenReturn("project-1");
    when(context.policySnapshot())
        .thenReturn(
            new LlmPolicySnapshot(
                LlmPolicySnapshot.KeySourceMode.OWN_KEYS,
                "owner-1",
                Set.of("deepseek"),
                Map.of("deepseek", List.of("deepseek-chat")),
                Map.of("deepseek", LlmPolicySnapshot.CredentialSource.ORG),
                Map.of(),
                "deepseek",
                null,
                LlmPolicySnapshot.RoutingMode.PROVIDER_PREFERRED,
                false,
                true,
                1));
    when(contexts.require(reference.scope(), reference.contextId())).thenReturn(context);
  }

  private ProjectMember member(String role) {
    return ProjectMember.builder()
        .id("member-1")
        .projectId("project-1")
        .userId("actor-1")
        .projectRole(role)
        .build();
  }

  private static ToolCallback tool(AtomicInteger dispatches) {
    ToolDefinition definition =
        DefaultToolDefinition.builder()
            .name("list_repos")
            .description("read")
            .inputSchema("{\"type\":\"object\"}")
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String input) {
        dispatches.incrementAndGet();
        return "{\"ok\":true}";
      }
    };
  }
}
