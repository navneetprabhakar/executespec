package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.agent.requirement.ExactPlanFingerprint;
import com.navneet.executespec.agent.requirement.RequirementGateBindingService.RequestedMode;
import com.navneet.executespec.agent.requirement.RequirementIrApprovalCompatibilityRegistry;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.billing.service.UsageTrackingService;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.executioncontext.LlmExecutionContext;
import com.navneet.executespec.executioncontext.LlmExecutionContextService;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.governance.GovernanceGuard;
import com.navneet.executespec.harness.repository.GateRepository;
import com.navneet.executespec.harness.repository.PlanGateResumeOutboxRepository;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.run.CustomerRunSubmissionService;
import com.navneet.executespec.run.RunIdempotencyClaim;
import com.navneet.executespec.run.RunIdempotencyService;
import com.navneet.executespec.run.RunLocalExecutionFlag;
import com.navneet.executespec.security.InputSanitizer;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Provider-free PostgreSQL functional coverage for an external collaborator's durable run path.
 *
 * <p>The test deliberately stops at the service boundary: HTTP authentication/serialization is
 * covered separately by controller tests, while this class proves the durable host-tenant versus
 * payer-org invariant across submission, approval, and the execution contexts consumed by Ask and
 * Screens. The orchestrator transport is replaced because the real implementation starts the
 * asynchronous provider-backed pipeline immediately; its answer persists the same atomic submit
 * policy into PostgreSQL before returning.
 */
@TestPropertySource(
    properties = {
      "execution.plan-gate-resume.scan-initial-delay-ms=3600000",
      "execution.plan-gate-resume.scan-interval-ms=3600000"
    })
class ExternalCollaboratorRunLifecyclePostgresIntegrationTest extends PostgresIntegrationBase {
  private static final String PREFIX = "ext-life-";
  private static final String PLAN_JSON = "{\"planTitle\":\"External change\",\"tasks\":[]}";
  private static final String CONTINUATION =
      "{\"schemaVersion\":1,\"localRun\":false,"
          + "\"requireContinuationConfirmation\":false,"
          + "\"requireContinuationBenchmarkPrior\":false,\"pinnedModel\":null,"
          + "\"lowComplexityWorkerStartTierOverride\":null}";

  @Autowired JdbcTemplate jdbc;
  @Autowired PipelineRunRepository runs;
  @Autowired GateRepository gates;
  @Autowired PlanGateResumeOutboxRepository outbox;
  @Autowired RunRequirementContractRepository requirements;
  @Autowired PlanGateOpenService gateOpener;
  @Autowired LlmExecutionContextService contexts;
  @Autowired OrgRepository orgs;
  @Autowired ProjectRepository projects;
  @Autowired EntityManager entityManager;
  @Autowired ObjectMapper objectMapper;
  @Autowired PlatformTransactionManager transactionManager;

  @BeforeEach
  void cleanOwnedRows() {
    jdbc.update(
        "DELETE FROM llm_execution_context WHERE isolation_key LIKE ?", "org:" + PREFIX + "%");
    jdbc.update("DELETE FROM pipeline_run WHERE run_id LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM project WHERE id LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM app_user WHERE id LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM org WHERE id LIKE ?", PREFIX + "%");
  }

  @Test
  void externalCollaboratorKeepsHostTenantAndPayerPolicyThroughSubmitApproveAskAndScreens() {
    String hostOrgId = PREFIX + "host-org-" + UUID.randomUUID();
    String payerOrgId = PREFIX + "payer-org-" + UUID.randomUUID();
    String actorId = PREFIX + "actor-" + UUID.randomUUID();
    String hostOwnerId = PREFIX + "host-owner-" + UUID.randomUUID();
    String projectId = PREFIX + "project-" + UUID.randomUUID();
    String runId = PREFIX + "run-" + UUID.randomUUID();
    UUID repoId = UUID.randomUUID();
    insertFixture(hostOrgId, payerOrgId, actorId, hostOwnerId, projectId);

    AppUserDetails actor =
        new AppUserDetails(
            AppUser.builder()
                .id(actorId)
                .email(actorId + "@payer.example.test")
                .orgId(payerOrgId)
                .globalRole("USER")
                .status("ACTIVE")
                .build(),
            null);
    Project hostProject =
        Project.builder().id(projectId).orgId(hostOrgId).createdBy(hostOwnerId).build();

    ApiKeyResolver apiKeys = mock(ApiKeyResolver.class);
    when(apiKeys.resolveWithProvenance(actorId)).thenReturn(systemCredentials(payerOrgId));
    when(apiKeys.isOwnKeysMode(actorId)).thenReturn(false);

    AtomicReference<RunOrchestrator.SubmitPolicy> capturedPolicy = new AtomicReference<>();
    RunOrchestrator orchestrator =
        mock(
            RunOrchestrator.class,
            invocation -> {
              if ("submitRunWithLocalExecution".equals(invocation.getMethod().getName())
                  && invocation.getArguments().length == 25) {
                RunOrchestrator.SubmitPolicy policy = invocation.getArgument(24);
                capturedPolicy.set(policy);
                jdbc.update(
                    """
                    INSERT INTO pipeline_run
                      (run_id,status,started_at,user_request,plan_json,credits_charged,
                       cost_estimate_high,created_by_user_id,org_id,billing_org_id,project_id,
                       ai_provider,requested_provider)
                    VALUES (?, 'PLANNING', ?, ?, ?, 3, 4.5, ?, ?, ?, ?, 'anthropic', 'anthropic')
                    """,
                    runId,
                    Timestamp.from(Instant.now()),
                    invocation.getArgument(0),
                    PLAN_JSON,
                    actorId,
                    policy.tenantOrgId(),
                    policy.billingOrgId(),
                    projectId);
                return runs.findById(runId).orElseThrow();
              }
              return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            });

    ProjectAccessGuard projectGuard = mock(ProjectAccessGuard.class);
    when(projectGuard.assertCanWrite(projectId, actor)).thenReturn(hostProject);
    RunIdempotencyService idempotency = mock(RunIdempotencyService.class);
    when(idempotency.claim(actorId, null, null)).thenReturn(RunIdempotencyClaim.disabled());
    when(idempotency.complete(any(), eq(runId)))
        .thenReturn(
            new RunIdempotencyService.Completion(
                RunIdempotencyService.CompletionStatus.NOT_REQUIRED, runId));

    CustomerRunSubmissionService submissionService =
        new CustomerRunSubmissionService(
            orchestrator,
            mock(FeatureFlagService.class),
            mock(InputSanitizer.class),
            mock(PlanGateBypassPolicy.class),
            projectGuard,
            mock(ProjectRepoService.class),
            mock(ModelAccessService.class),
            mock(AiProviderPreferenceService.class),
            mock(ManagedAiUsageService.class),
            mock(TierEnforcementService.class),
            mock(UsageTrackingService.class),
            apiKeys,
            idempotency,
            mock(StateStore.class),
            mock(RunLocalExecutionFlag.class),
            mock(RunLifecycleService.class));

    CustomerRunSubmissionService.Submission submitted =
        submissionService.submit(
            CustomerRunSubmissionService.Command.builder()
                .userRequest("Return 404 when the order is absent")
                .source("REST")
                .projectId(projectId)
                .selectedRepoIds(List.of(repoId))
                .aiProvider("ANTHROPIC")
                .build(),
            actor);

    assertThat(submitted.created()).isTrue();
    assertThat(capturedPolicy.get().tenantOrgId()).isEqualTo(hostOrgId);
    assertThat(capturedPolicy.get().billingOrgId()).isEqualTo(payerOrgId);
    assertThat(runRow(runId))
        .containsEntry("org_id", hostOrgId)
        .containsEntry("billing_org_id", payerOrgId)
        .containsEntry("created_by_user_id", actorId);

    gateOpener.open(
        new PlanGateOpenService.OpenCommand(
            runId,
            PLAN_JSON,
            ExactPlanFingerprint.sha256(PLAN_JSON),
            CONTINUATION,
            1,
            null,
            RequestedMode.LEGACY));

    ManagedAiUsageService managedAi = mock(ManagedAiUsageService.class);
    when(managedAi.assertCanApproveEstimatedSpend(actorId, payerOrgId, false, 4.5))
        .thenReturn(
            new ManagedAiUsageService.ApprovalEvaluation(
                "ALLOWED", "MANAGED", 2000L, 1900L, 100L, 25.0, 23.0));
    GovernanceGuard governance = mock(GovernanceGuard.class);
    when(governance.evaluateApprovalCost(eq(hostOrgId), eq(4.5), any(AppUserDetails.class)))
        .thenReturn(
            new GovernanceGuard.OrgCostEvaluation(
                "ALLOWED", new BigDecimal("10.00"), 4.5, "BELOW_THRESHOLD"));
    when(governance.evaluateProjectSpendCap(projectId, 4.5))
        .thenReturn(
            new GovernanceGuard.ProjectSpendEvaluation(
                "ALLOWED",
                new BigDecimal("25.00"),
                BigDecimal.ZERO,
                new BigDecimal("4.50"),
                "WITHIN_CAP"));
    PlanGateDecisionService decisions =
        new PlanGateDecisionService(
            runs,
            gates,
            outbox,
            requirements,
            new RequirementIrApprovalCompatibilityRegistry(objectMapper),
            entityManager,
            apiKeys,
            managedAi,
            governance,
            mock(ApplicationEventPublisher.class));

    PlanGateDecisionService.DecisionResult approved =
        new TransactionTemplate(transactionManager)
            .execute(
                ignored ->
                    decisions.decide(
                        runId,
                        new PlanApprovalService.Command(
                            true,
                            3,
                            "REALTIME",
                            ExactPlanFingerprint.sha256(PLAN_JSON),
                            null,
                            null),
                        actor));

    assertThat(approved).isNotNull();
    assertThat(approved.evidence().billingOrgId()).isEqualTo(payerOrgId);
    assertThat(approved.evidence().credentialAuthorizationOrgId()).isEqualTo(payerOrgId);
    assertThat(gateAndOutboxRow(runId))
        .containsEntry("gate_status", "APPROVED")
        .containsEntry("run_status", "APPROVAL_RESUMING")
        .containsEntry("outbox_status", "READY")
        .containsEntry("billing_org_id", payerOrgId);

    ModelRegistry registry = mock(ModelRegistry.class);
    when(registry.allModels()).thenReturn(Map.of());
    LlmExecutionPolicyFactory policyFactory =
        new LlmExecutionPolicyFactory(
            contexts, runs, mock(AgentSessionRepository.class), projects, orgs, apiKeys, registry);
    ReflectionTestUtils.setField(policyFactory, "platformAnthropicKey", "test-key-not-used");
    LlmExecutionReference ask = policyFactory.forRunChatTurn(runId, PREFIX + "ask-turn");
    LlmExecutionReference screens =
        policyFactory.forRunPreviewSurface(runId, PREFIX + "screen-attempt");

    assertContext(ask, hostOrgId, payerOrgId, actorId, projectId, "run-chat", "RUN_CHAT");
    assertContext(
        screens, hostOrgId, payerOrgId, actorId, projectId, "preview-render", "PREVIEW_RENDER");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM llm_execution_context WHERE id IN (?,?)",
                Integer.class,
                ask.contextId(),
                screens.contextId()))
        .isEqualTo(2);
  }

  private void assertContext(
      LlmExecutionReference reference,
      String hostOrgId,
      String payerOrgId,
      String actorId,
      String projectId,
      String feature,
      String sourceType) {
    LlmExecutionContext context =
        contexts.require(LlmExecutionScope.customer(hostOrgId), reference.contextId());
    assertThat(context.getIsolationKey()).isEqualTo("org:" + hostOrgId);
    assertThat(context.getOrgId()).isEqualTo(hostOrgId);
    assertThat(context.getCredentialOrgId()).isEqualTo(payerOrgId);
    assertThat(context.getUserId()).isEqualTo(actorId);
    assertThat(context.getKeySubjectUserId()).isEqualTo(actorId);
    assertThat(context.getProjectId()).isEqualTo(projectId);
    assertThat(context.getFeature()).isEqualTo(feature);
    assertThat(context.getSourceType()).isEqualTo(sourceType);
    assertThat(context.getAllowedProvidersJson()).contains("anthropic");
    assertThat(context.getProviderKeySourcesJson()).containsEntry("anthropic", "PLATFORM");
  }

  private void insertFixture(
      String hostOrgId, String payerOrgId, String actorId, String hostOwnerId, String projectId) {
    jdbc.update(
        "INSERT INTO org (id,name,domain_patterns,tier,created_by_user_id) VALUES (?,?,?,?,?)",
        hostOrgId,
        "External Host",
        "host.example.test",
        "ENTERPRISE",
        hostOwnerId);
    jdbc.update(
        "INSERT INTO org (id,name,domain_patterns,tier,created_by_user_id) VALUES (?,?,?,?,?)",
        payerOrgId,
        "External Payer",
        "payer.example.test",
        "PRO",
        actorId);
    jdbc.update(
        """
        INSERT INTO app_user
          (id,email,first_name,last_name,password_hash,global_role,tier,status,org_id)
        VALUES (?,?,?,?,?,?,?,?,?)
        """,
        hostOwnerId,
        hostOwnerId + "@host.example.test",
        "Host",
        "Owner",
        "hash",
        "USER",
        "ENTERPRISE",
        "ACTIVE",
        hostOrgId);
    jdbc.update(
        """
        INSERT INTO app_user
          (id,email,first_name,last_name,password_hash,global_role,tier,status,org_id)
        VALUES (?,?,?,?,?,?,?,?,?)
        """,
        actorId,
        actorId + "@payer.example.test",
        "External",
        "Collaborator",
        "hash",
        "USER",
        "PRO",
        "ACTIVE",
        payerOrgId);
    jdbc.update(
        "INSERT INTO project (id,org_id,name,created_by,ai_provider) VALUES (?,?,?,?,?)",
        projectId,
        hostOrgId,
        "External Collaboration Project",
        hostOwnerId,
        "ANTHROPIC");
  }

  private Map<String, Object> runRow(String runId) {
    return jdbc.queryForMap(
        "SELECT org_id,billing_org_id,created_by_user_id FROM pipeline_run WHERE run_id=?", runId);
  }

  private Map<String, Object> gateAndOutboxRow(String runId) {
    return jdbc.queryForMap(
        """
        SELECT g.status AS gate_status,g.billing_org_id,r.status AS run_status,
               o.status AS outbox_status
          FROM gate_record g
          JOIN pipeline_run r ON r.run_id=g.run_id
          JOIN plan_gate_resume_outbox o ON o.run_id=g.run_id
         WHERE g.run_id=?
        """,
        runId);
  }

  private static ApiKeyResolver.CredentialResolution systemCredentials(String orgId) {
    return new ApiKeyResolver.CredentialResolution(ResolvedKeys.SYSTEM_ONLY, orgId, Map.of());
  }
}
