package com.navneet.executespec.specchat.policy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.executioncontext.LlmExecutionBudget;
import com.navneet.executespec.executioncontext.LlmExecutionContext;
import com.navneet.executespec.executioncontext.LlmExecutionContextService;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmPolicySnapshot;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.specchat.ChatToolPolicy;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Resolves and revalidates the immutable authority snapshot for customer chat turns. */
@Service
@RequiredArgsConstructor
public class ProjectChatPolicyResolver {

  private static final ProjectChatPolicy.TurnBudgets DEFAULT_BUDGETS =
      new ProjectChatPolicy.TurnBudgets(
          ChatToolPolicy.MAX_TURN_TOKENS,
          BigDecimal.valueOf(ChatToolPolicy.MAX_TURN_COST_USD),
          ChatToolPolicy.MAX_PROVIDER_CALLS_PER_TURN,
          ChatToolPolicy.MAX_TOOL_CALLS,
          ChatToolPolicy.MAX_MODEL_ROUNDS + 1);

  private final ProjectAccessGuard projectAccessGuard;
  private final ProjectMemberRepository projectMemberRepository;
  private final AgentSessionRepository agentSessionRepository;
  private final LlmExecutionPolicyFactory executionPolicyFactory;
  private final LlmExecutionContextService executionContextService;
  private final ObjectProvider<ProjectChatConfirmationAuthority> confirmationAuthorities;

  /** Resolve before any model dispatch or persistence belonging to a paid chat turn. */
  public ProjectChatPolicy resolve(TurnRequest request) {
    if (request == null) throw denied("chat turn request is required");
    AppUserDetails principal = requirePrincipal(request.principal());
    String actorId = require(principal.getUser().getId(), "actor id");
    String actorOrgId = principal.getUser().getOrgId();
    if (request.claimedActorOrgId() != null
        && !request.claimedActorOrgId().isBlank()
        && !java.util.Objects.equals(request.claimedActorOrgId().trim(), actorOrgId)) {
      throw denied("chat actor org does not match authenticated tenant");
    }
    String sessionId = requireBounded(request.sessionId(), "session id", 160);
    String turnId = requireBounded(request.turnId(), "turn id", 160);
    String correlationId = requireBounded(request.correlationId(), "correlation id", 96);
    String projectId = normalize(request.projectId());
    Project project =
        projectId == null ? null : projectAccessGuard.assertCanRead(projectId, principal);
    ProjectChatPolicy.EffectiveProjectRole role = effectiveRole(principal, project);
    Set<ChatToolPolicy.Capability> capabilities = capabilities(role);
    if (!capabilities.contains(ChatToolPolicy.Capability.SPEND_AI)) {
      throw denied("PROJECT_VIEWER cannot initiate provider spend");
    }
    validatePersistentSession(sessionId, actorId, actorOrgId, projectId);

    var reference =
        executionPolicyFactory.forChatTurn(
            actorId,
            actorOrgId,
            projectId,
            require(request.feature(), "feature"),
            sessionId,
            turnId,
            correlationId,
            require(request.provider(), "provider"),
            normalize(request.model()),
            new LlmExecutionBudget(
                null,
                null,
                DEFAULT_BUDGETS.maxTotalTokens(),
                DEFAULT_BUDGETS.maxCostUsd(),
                DEFAULT_BUDGETS.maxProviderCalls()));
    LlmExecutionContext context =
        executionContextService.require(reference.scope(), reference.contextId());
    LlmPolicySnapshot routing = context.policySnapshot();
    String hostOrgId = require(context.getOrgId(), "execution org");
    if (project != null && !hostOrgId.equals(project.getOrgId())) {
      throw denied("chat execution context crossed the project tenant");
    }
    if (!actorId.equals(context.getUserId())
        || !java.util.Objects.equals(projectId, context.getProjectId())) {
      throw denied("chat execution context identity mismatch");
    }

    ProjectChatPolicy.Confirmation confirmation =
        confirmation(
            new ProjectChatConfirmationAuthority.ConfirmationQuery(
                actorId, hostOrgId, projectId, sessionId, turnId, correlationId),
            capabilities);
    ProjectChatPolicy policy =
        new ProjectChatPolicy(
            actorId,
            actorOrgId,
            hostOrgId,
            projectId,
            role,
            capabilities,
            routing.allowedProviders(),
            routing.allowedProviderModels(),
            routing.keySourceMode(),
            routing.providerKeySources(),
            DEFAULT_BUDGETS,
            confirmation,
            trustLabels(),
            new ProjectChatPolicy.AuditIdentity(
                sessionId, turnId, correlationId, reference.contextId()),
            reference);
    revalidate(policy, principal);
    return policy;
  }

  /** Recheck tenant, actor, role and durable V282 routing identity at a service/tool boundary. */
  public void revalidate(ProjectChatPolicy policy, AppUserDetails principal) {
    if (policy == null) throw denied("chat policy is missing");
    AppUserDetails actor = requirePrincipal(principal);
    if (!policy.actorId().equals(actor.getUser().getId())
        || !java.util.Objects.equals(policy.actorOrgId(), actor.getUser().getOrgId())) {
      throw denied("chat policy actor mismatch");
    }
    Project project =
        policy.projectId() == null
            ? null
            : projectAccessGuard.assertCanRead(policy.projectId(), actor);
    if (project != null && !policy.orgId().equals(project.getOrgId())) {
      throw denied("chat policy tenant mismatch");
    }
    ProjectChatPolicy.EffectiveProjectRole currentRole = effectiveRole(actor, project);
    if (currentRole != policy.effectiveProjectRole()
        || !capabilities(currentRole).equals(policy.allowedCapabilities())) {
      throw denied("chat project role changed during the turn");
    }
    LlmExecutionContext context =
        executionContextService.require(
            policy.executionReference().scope(), policy.executionReference().contextId());
    LlmPolicySnapshot routing = context.policySnapshot();
    if (!policy.actorId().equals(context.getUserId())
        || !policy.orgId().equals(context.getOrgId())
        || !java.util.Objects.equals(policy.projectId(), context.getProjectId())
        || routing.keySourceMode() != policy.keySourceMode()
        || !routing.allowedProviders().equals(policy.allowedProviders())
        || !routing.allowedProviderModels().equals(policy.allowedProviderModels())
        || !routing.providerKeySources().equals(policy.providerKeySources())) {
      throw denied("durable chat execution policy mismatch");
    }
  }

  public TurnGuard newGuard(ProjectChatPolicy policy, AppUserDetails principal) {
    revalidate(policy, principal);
    return new TurnGuard(this, policy, principal, Clock.systemUTC());
  }

  public record TurnRequest(
      AppUserDetails principal,
      String claimedActorOrgId,
      String projectId,
      String sessionId,
      String turnId,
      String correlationId,
      String feature,
      String provider,
      String model) {}

  /** Mutable counters are intentionally outside the immutable authority snapshot. */
  public static final class TurnGuard {
    private final ProjectChatPolicyResolver resolver;
    private final ProjectChatPolicy policy;
    private final AppUserDetails principal;
    private final Clock clock;
    private final AtomicInteger providerCalls = new AtomicInteger();
    private final AtomicInteger modelRounds = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();

    TurnGuard(
        ProjectChatPolicyResolver resolver,
        ProjectChatPolicy policy,
        AppUserDetails principal,
        Clock clock) {
      this.resolver = resolver;
      this.policy = policy;
      this.principal = principal;
      this.clock = clock;
    }

    public void beforeModelRound(String provider, String model) {
      resolver.revalidate(policy, principal);
      policy.requireCapability(ChatToolPolicy.Capability.SPEND_AI);
      policy.requireRoute(provider, model);
      if (modelRounds.incrementAndGet() > policy.budgets().maxModelRounds()
          || providerCalls.incrementAndGet() > policy.budgets().maxProviderCalls()) {
        throw denied("chat model/provider-call budget exhausted");
      }
    }

    public List<ToolCallback> guardTools(List<ToolCallback> tools) {
      if (tools == null || tools.isEmpty()) return List.of();
      return tools.stream().map(this::guardTool).toList();
    }

    private ToolCallback guardTool(ToolCallback delegate) {
      if (delegate == null || delegate.getToolDefinition() == null) {
        throw denied("chat tool definition is missing");
      }
      ToolDefinition definition = delegate.getToolDefinition();
      if (!ChatToolPolicy.isExplicitlyClassified(definition.name())) {
        throw denied("chat tool is not classified");
      }
      return new ToolCallback() {
        @Override
        public ToolDefinition getToolDefinition() {
          return definition;
        }

        @Override
        public String call(String input) {
          resolver.revalidate(policy, principal);
          ChatToolPolicy.Capability capability = ChatToolPolicy.capability(definition.name());
          if (toolCalls.incrementAndGet() > policy.budgets().maxToolCalls()) {
            throw denied("chat tool-call budget exhausted");
          }
          if (capability == ChatToolPolicy.Capability.PROJECT_READ) {
            policy.requireCapability(capability);
          } else {
            policy.requireConfirmed(capability, clock.instant());
          }
          return delegate.call(input);
        }
      };
    }
  }

  private ProjectChatPolicy.Confirmation confirmation(
      ProjectChatConfirmationAuthority.ConfirmationQuery query,
      Set<ChatToolPolicy.Capability> allowedCapabilities) {
    List<ProjectChatConfirmationAuthority> authorities =
        confirmationAuthorities.orderedStream().limit(2).toList();
    if (authorities.isEmpty()) return ProjectChatPolicy.Confirmation.none();
    if (authorities.size() != 1) throw denied("multiple chat confirmation authorities configured");
    ProjectChatConfirmationAuthority authority = authorities.getFirst();
    ProjectChatConfirmationAuthority.ConfirmationGrant grant = authority.resolve(query);
    if (grant == null || grant.reference() == null || grant.reference().isBlank()) {
      return ProjectChatPolicy.Confirmation.none();
    }
    if (grant.expiresAt() == null) throw denied("chat confirmation grant is missing expiry");
    if (!Instant.now().isBefore(grant.expiresAt())) {
      return ProjectChatPolicy.Confirmation.none();
    }
    Set<ChatToolPolicy.Capability> confirmed = new LinkedHashSet<>(grant.confirmedCapabilities());
    if (!allowedCapabilities.containsAll(confirmed)) {
      throw denied("chat confirmation grants a capability outside actor policy");
    }
    confirmed.retainAll(allowedCapabilities);
    if (confirmed.isEmpty()) return ProjectChatPolicy.Confirmation.none();
    return new ProjectChatPolicy.Confirmation(
        ProjectChatPolicy.ConfirmationState.CONFIRMED,
        grant.reference(),
        grant.expiresAt(),
        confirmed);
  }

  private void validatePersistentSession(
      String sessionId, String actorId, String actorOrgId, String projectId) {
    agentSessionRepository
        .findById(sessionId)
        .ifPresent(
            session -> {
              if (!actorId.equals(session.getUserId())
                  || !java.util.Objects.equals(actorOrgId, session.getOrgId())
                  || !java.util.Objects.equals(projectId, normalize(session.getProjectId()))) {
                throw denied("agent session scope does not match the chat turn");
              }
            });
  }

  private ProjectChatPolicy.EffectiveProjectRole effectiveRole(
      AppUserDetails principal, Project project) {
    if (project == null) return ProjectChatPolicy.EffectiveProjectRole.UNSCOPED_USER;
    if ("SUPER_ADMIN".equals(principal.getUser().getGlobalRole())) {
      return ProjectChatPolicy.EffectiveProjectRole.SUPER_ADMIN;
    }
    boolean sameOrg = java.util.Objects.equals(project.getOrgId(), principal.getUser().getOrgId());
    boolean orgAdmin =
        "ORG_ADMIN".equals(principal.getUser().getGlobalRole())
            || (principal.getOrgMember() != null
                && "ORG_ADMIN".equals(principal.getOrgMember().getOrgRole()));
    if (sameOrg && orgAdmin) return ProjectChatPolicy.EffectiveProjectRole.ORG_ADMIN;
    String role =
        projectMemberRepository
            .findByProjectIdAndUserId(project.getId(), principal.getUser().getId())
            .map(member -> member.getProjectRole())
            .orElse(null);
    try {
      return ProjectChatPolicy.EffectiveProjectRole.valueOf(require(role, "project role"));
    } catch (IllegalArgumentException unknown) {
      throw denied("unknown or missing project role");
    }
  }

  private static Set<ChatToolPolicy.Capability> capabilities(
      ProjectChatPolicy.EffectiveProjectRole role) {
    return switch (role) {
      case PROJECT_VIEWER -> Set.of(ChatToolPolicy.Capability.PROJECT_READ);
      case UNSCOPED_USER ->
          Set.of(ChatToolPolicy.Capability.PROJECT_READ, ChatToolPolicy.Capability.SPEND_AI);
      case SUPER_ADMIN, ORG_ADMIN, PROJECT_ADMIN, PROJECT_DEVELOPER ->
          Set.copyOf(EnumSet.allOf(ChatToolPolicy.Capability.class));
    };
  }

  private static Map<ProjectChatPolicy.SourceKind, ProjectChatPolicy.TrustLabel> trustLabels() {
    Map<ProjectChatPolicy.SourceKind, ProjectChatPolicy.TrustLabel> labels = new LinkedHashMap<>();
    for (ProjectChatPolicy.SourceKind kind : ProjectChatPolicy.SourceKind.values()) {
      labels.put(
          kind,
          kind == ProjectChatPolicy.SourceKind.SYSTEM_POLICY
              ? ProjectChatPolicy.TrustLabel.TRUSTED_AUTHORITY
              : ProjectChatPolicy.TrustLabel.UNTRUSTED_DATA);
    }
    return Map.copyOf(labels);
  }

  private static AppUserDetails requirePrincipal(AppUserDetails principal) {
    if (principal == null || principal.getUser() == null) throw denied("authentication required");
    return principal;
  }

  private static String require(String value, String field) {
    if (value == null || value.isBlank()) throw denied(field + " is required");
    return value.trim();
  }

  private static String requireBounded(String value, String field, int maxLength) {
    String normalized = require(value, field);
    if (normalized.length() > maxLength) throw denied(field + " is too long");
    return normalized;
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static ProjectChatPolicyDeniedException denied(String reason) {
    return new ProjectChatPolicyDeniedException(reason);
  }
}
