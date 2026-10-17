package com.navneet.executespec.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.api.RunMapper;
import com.navneet.executespec.api.dto.ApprovalRequest;
import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.api.dto.RunSummary;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.JwtUtil;
import com.navneet.executespec.execution.model.GoalDonenessState;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.report.model.ValidationReportDTO;
import com.navneet.executespec.run.CustomerRunSubmissionService;
import com.navneet.executespec.run.RunRequestFingerprint;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Authenticated V2 lifecycle tests.
 *
 * <p>The four provider-backed scenarios are fail-closed: each Maven invocation must select exactly
 * one scenario, preselect its run UUID, enable the live-LLM test mode, and supply a budget-ledger
 * reservation matching its selected provider, model, and run UUID. The test-only reserved
 * submission endpoint binds that UUID before asynchronous planning can dispatch. Without every
 * guard, the live scenario is skipped and {@link
 * com.navneet.executespec.agent.ProviderDispatchAuthorization} still rejects provider transport.
 *
 * <p>Normal {@code mvn verify} excludes this {@code e2e} tag. See README.md for the exact reserved
 * command.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(PipelineE2ETest.ReservedRunSubmissionTestConfiguration.class)
@TestPropertySource(properties = "agent.workspace=./test-workspace-e2e")
@Testcontainers
class PipelineE2ETest {

  static final org.testcontainers.containers.GenericContainer<?> REDIS =
      new org.testcontainers.containers.GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("executespec_pipeline_e2e")
          .withUsername("executespec_e2e")
          .withPassword("executespec_e2e");

  static {
    // Spring resolves @DynamicPropertySource while building the test context, before the Jupiter
    // @Container callback is guaranteed to run. Start this disposable singleton explicitly so
    // getJdbcUrl() can never observe an unstarted container.
    POSTGRES.start();
    REDIS.start();
  }

  @DynamicPropertySource
  static void isolatedDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add(
        "executespec.sandbox.image",
        () ->
            System.getenv()
                .getOrDefault(
                    "EXECUTESPEC_E2E_SANDBOX_IMAGE", "docker.io/library/debian:stable-slim"));
    registry.add(
        "executespec.sandbox.workspace-root",
        () -> Path.of("test-sandboxes-e2e").toAbsolutePath().toString());
    registry.add(
        "executespec.sandbox.runtime-uid",
        () -> System.getenv().getOrDefault("EXECUTESPEC_E2E_RUNTIME_UID", "65532"));
    registry.add(
        "executespec.sandbox.runtime-gid",
        () -> System.getenv().getOrDefault("EXECUTESPEC_E2E_RUNTIME_GID", "65532"));
  }

  private static final PipelineE2ERoute ROUTE = PipelineE2ERoute.from(System.getenv());
  private static final String PROVIDER = ROUTE.provider();
  private static final String MODEL = ROUTE.model();
  private static final double COST_CAP_USD = PipelineE2ERoute.costCap(System.getenv());
  private static final Duration PLAN_TIMEOUT = Duration.ofMinutes(4);
  private static final Duration WORK_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration SETTLE_TIMEOUT = Duration.ofMinutes(2);
  private static final String SIMPLE_TASK =
      """
      Create a minimal Python library: main.py exporting greeting() returning exactly Hello World,
      test_main.py with maintained pytest tests for that behavior, requirements.txt pinning
      pytest==8.3.5, and hello.txt containing exactly one line: Hello World. This is a library,
      not an HTTP service or a CLI. Keep the project small and run its tests before completion.
      """;

  private static final String BUILD_REGRESSION_TASK =
      """
Build a small Python library with three sequential plan tasks: bootstrap the library/tests,
implement slug behavior including edge cases, then write its README. Use main.py exporting
slugify(text: str) -> str, requirements.txt pinning pytest==8.3.5, test_main.py and README.md.
slugify lowercases text, replaces each run of non-ASCII-alphanumeric characters with one
hyphen, and strips leading/trailing hyphens. Empty or punctuation-only text returns empty.
Include maintained pytest happy-path and edge-case tests, and run them before completion.
This is a library, not an HTTP service or CLI application.
""";

  @Autowired private com.navneet.executespec.config.featureflag.FeatureFlagService flags;

  @Autowired
  private com.navneet.executespec.sandboxroute.SandboxCommandRoutingService sandboxRouting;

  @Autowired
  private com.navneet.executespec.workspacegeneration.DisposableVerificationWorkspaceService
      disposableWorkspaces;

  @Autowired
  private com.navneet.executespec.workspacegeneration.WorkspaceGenerationRecoveryService
      generations;

  private static final ApprovalRequest APPROVE =
      new ApprovalRequest(true, "Reserved E2E approval", null, null, null, null, null);
  private static final ApprovalRequest REJECT =
      new ApprovalRequest(false, "Reserved E2E rejection", null, null, null, null, null);

  @Autowired private TestRestTemplate rest;
  @Autowired private AppUserRepository userRepository;
  @Autowired private OrgRepository orgRepository;
  @Autowired private OrgMemberRepository orgMemberRepository;
  @Autowired private JwtUtil jwtUtil;
  private final com.navneet.executespec.state.StateStore stateStore;
  private final org.springframework.jdbc.core.JdbcTemplate fixtureJdbc;

  @Autowired
  PipelineE2ETest(
      com.navneet.executespec.state.StateStore stateStore,
      org.springframework.jdbc.core.JdbcTemplate fixtureJdbc) {
    this.stateStore = stateStore;
    this.fixtureJdbc = fixtureJdbc;
  }

  private String bearerToken;
  private String actorUserId;
  private String actorOrgId;
  private String actorMemberId;

  @BeforeAll
  void createAuthenticatedEnterpriseActor() {
    String suffix = UUID.randomUUID().toString();
    String userId = "e2e-user-" + suffix;
    String orgId = "e2e-org-" + suffix;
    actorUserId = userId;
    actorOrgId = orgId;
    actorMemberId = "e2e-member-" + suffix;

    Org org =
        orgRepository.save(
            Org.builder()
                .id(orgId)
                .name("Pipeline E2E " + suffix)
                .domainPatterns("")
                .tier("ENTERPRISE")
                .status("ACTIVE")
                .keySourceMode("HYBRID")
                .createdByUserId(userId)
                .build());
    AppUser user =
        userRepository.save(
            AppUser.builder()
                .id(userId)
                .email("pipeline-e2e-" + suffix + "@example.com")
                .firstName("Pipeline")
                .lastName("E2E")
                .globalRole("USER")
                .tier("ENTERPRISE")
                .status("ACTIVE")
                .emailVerified(true)
                .orgId(org.getId())
                .build());
    OrgMember member =
        orgMemberRepository.save(
            OrgMember.builder()
                .id(actorMemberId)
                .orgId(org.getId())
                .userId(user.getId())
                .orgRole("ORG_ADMIN")
                .build());
    bearerToken = jwtUtil.generateAccessToken(user, member);
    if (liveFixtureRequested() || Boolean.parseBoolean(System.getenv("E2E_RUNTIME_PREFLIGHT"))) {
      for (String key :
          List.of(
              "sandbox.lifecycle.enabled",
              "sandbox.runtime.dispatch.enabled",
              "sandbox.runtime.container-strict-v1.enabled",
              "sandbox.runtime.toolchain-readiness.enabled")) {
        flags.update(key, "true", actorUserId);
      }
      if ("build".equals(System.getenv("E2E_SCENARIO"))) {
        flags.update("build.shared-code-change-loop.enabled", "true", actorUserId);
      }
      assertThat(sandboxRouting.readiness().enabled())
          .as("isolated E2E strict runtime readiness")
          .isTrue();
    }
  }

  @AfterAll
  void cleanWorkspace() throws IOException {
    if (liveFixtureRequested()) {
      String runId = System.getenv("E2E_RUN_ID");
      var report =
          get("/api/runs/" + runId + "/report", com.fasterxml.jackson.databind.JsonNode.class);
      PipelineE2EEvidence.save(
          stateStore,
          runId,
          report.getStatusCode().is2xxSuccessful() ? report.getBody() : null,
          get("/api/runs/" + runId, com.fasterxml.jackson.databind.JsonNode.class).getBody(),
          fixtureJdbc);
      return; // Retain generated workspaces for independent inspection, including failed runs.
    }
    // Provider-free checks create no run workspace. Preserve earlier invocations' diagnostic trees.
  }

  @Test
  @DisplayName("V2 happy path: authenticated submit → plan approval → automatic terminal state")
  void happyPath_completesWithoutLegacyMergeGate() throws IOException {
    String runId = requireLiveScenario("happy");
    submitReservedRun(runId);
    awaitStatus(runId, "AWAITING_APPROVAL", PLAN_TIMEOUT);

    assertThat(post("/api/runs/" + runId + "/approve", APPROVE, Void.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(awaitTerminal(runId, WORK_TIMEOUT)).isEqualTo("COMPLETE");

    RunDetail detail = getDetail(runId);
    assertThat(detail.getCompletedAt()).isNotNull();
    assertThat(detail.getTasks()).isNotEmpty();
    assertThat(detail.getFailureReason()).isNull();

    Path artifact = Path.of("test-workspace-e2e", runId, "hello.txt");
    assertThat(artifact).exists().isRegularFile();
    assertThat(Files.readString(artifact)).isIn("Hello World", "Hello World\n", "Hello World\r\n");
    assertHeldOutPython(runId, "import main; assert main.greeting() == 'Hello World'");

    ValidationReportDTO report = awaitReport(runId);
    assertThat(report.filesGenerated()).isGreaterThanOrEqualTo(1);
    assertThat(report.verificationLedger()).isNotEmpty();
    assertThat(report.verificationLedger())
        .anyMatch(evidence -> "DETERMINISTIC".equals(evidence.source()));
    assertThat(report.runGoalDecision()).isNotNull();
    assertThat(report.finalBuildPassed()).isTrue();
    assertThat(report.testsPassed()).isPositive();
    assertThat(report.pipelineResult().name()).isIn("PASSED", "PASSED_WITH_FIXES");
    // Lifecycle success is distinct from the conservative proof state. Workspace-authored tests
    // cannot manufacture independent functional evidence or erase a disclosed assessment gap.
    assertThat(report.runGoalDecision().state())
        .isIn(GoalDonenessState.DONE, GoalDonenessState.UNCERTAIN);
    if (report.runGoalDecision().state() == GoalDonenessState.DONE) {
      assertThat(report.runGoalDecision().recommendedDecision())
          .isEqualTo(RunContinuationDecision.STOP_DONE);
    } else {
      assertThat(report.runGoalDecision().incompleteItems()).isNotEmpty();
      assertThat(report.runGoalDecision().recommendedDecision())
          .isNotEqualTo(RunContinuationDecision.STOP_DONE);
    }

    // The authenticated compatibility endpoint exists only to return a clear V2 retirement signal.
    assertThat(post("/api/runs/" + runId + "/merge", APPROVE, Void.class).getStatusCode())
        .isEqualTo(HttpStatus.GONE);
  }

  @Test
  @DisplayName("Shared BUILD: approve → bootstrap/change/docs → real regression tests → report")
  void buildPathAcceptsGeneratedLibraryAndIndependentHeldOutBehavior() {
    String runId = requireLiveScenario("build");
    submitReservedRun(runId, BUILD_REGRESSION_TASK);
    awaitStatus(runId, "AWAITING_APPROVAL", PLAN_TIMEOUT);
    assertThat(post("/api/runs/" + runId + "/approve", APPROVE, Void.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(awaitTerminal(runId, WORK_TIMEOUT)).isEqualTo("COMPLETE");
    var detail = getDetail(runId);
    assertThat(detail.getTasks()).isNotEmpty();
    var report = awaitReport(runId);
    assertThat(report.verificationLedger()).isNotEmpty();
    assertHeldOutPython(
        runId,
        "import main; assert main.slugify('  Hello, WORLD!! ') == 'hello-world';"
            + " assert main.slugify('---') == ''; assert main.slugify('a___b') == 'a-b'");
  }

  private void assertHeldOutPython(String runId, String assertions) {
    Path workspace = Path.of("test-workspace-e2e", runId).toAbsolutePath();
    var context = new com.navneet.executespec.tool.ExecutionContext(runId, "held-out", workspace);
    var execution =
        disposableWorkspaces.executeAccepted(
            context,
            () -> generations.assertAcceptedFrontier(runId, workspace, "E2E_HELD_OUT"),
            disposable ->
                sandboxRouting.executeVerification(
                    disposable.verificationSandboxAuthority(),
                    List.of("python3", "-c", assertions + "; print('held-out-pass')"),
                    Duration.ofSeconds(30)));
    assertThat(execution.value().succeeded()).as(execution.value().toString()).isTrue();
    assertThat(execution.value().stdout()).contains("held-out-pass");
  }

  @Test
  @DisplayName("V2 plan rejection terminalizes as CANCELLED")
  void planRejection_marksRunCancelled() {
    String runId = requireLiveScenario("reject");
    submitReservedRun(runId);
    awaitStatus(runId, "AWAITING_APPROVAL", PLAN_TIMEOUT);

    assertThat(post("/api/runs/" + runId + "/approve", REJECT, Void.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    awaitStatus(runId, "CANCELLED", SETTLE_TIMEOUT);
    assertThat(getDetail(runId).getFailureReason()).isNotBlank();
  }

  @Test
  @DisplayName("V2 cancellation and paginated status listing use current contracts")
  void cancelDuringPlanGate_marksRunCancelledAndListIsPaginated() {
    String runId = requireLiveScenario("cancel");
    submitReservedRun(runId);
    awaitStatus(runId, "AWAITING_APPROVAL", PLAN_TIMEOUT);

    ResponseEntity<JsonNode> list =
        get("/api/runs?status=AWAITING_APPROVAL&scope=mine", JsonNode.class);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(list.getBody()).isNotNull();
    assertThat(list.getBody().path("content").findValuesAsText("runId")).contains(runId);

    assertThat(delete("/api/runs/" + runId).getStatusCode()).isEqualTo(HttpStatus.OK);
    awaitStatus(runId, "CANCELLED", SETTLE_TIMEOUT);
  }

  @Test
  @DisplayName("Unknown authenticated run returns 404")
  void getUnknownRun_returns404() {
    assertThat(
            get("/api/runs/00000000-0000-0000-0000-000000000000", RunDetail.class).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("Metrics endpoint is authenticated and reachable")
  void metrics_returns200() {
    ResponseEntity<JsonNode> response = get("/api/metrics", JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().has("totalRuns")).isTrue();
  }

  @Test
  @DisplayName("Blank run request is rejected before any provider dispatch")
  void submitBlankRequest_returns400() {
    ResponseEntity<Void> response = post("/api/runs", Map.of("userRequest", "  "), Void.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("Run submission requires authentication")
  void submitWithoutAuthentication_returns401() {
    ResponseEntity<Void> response =
        rest.postForEntity(
            "/api/e2e/reserved-runs/" + UUID.randomUUID(),
            new ReservedRunRequest(SIMPLE_TASK),
            Void.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private String requireLiveScenario(String expected) {
    String scenario = System.getenv("E2E_SCENARIO");
    String runId = System.getenv("E2E_RUN_ID");
    String reservation = System.getProperty("executespec.live-llm.reservation-id", "");
    boolean liveEnabled =
        Boolean.parseBoolean(System.getProperty("executespec.live-llm.enabled", "false"));
    boolean testMode =
        Boolean.parseBoolean(System.getProperty("executespec.live-llm.test-mode", "false"));

    assumeTrue(
        expected.equals(scenario)
            && liveEnabled
            && testMode
            && !reservation.isBlank()
            && isUuid(runId),
        () ->
            "live scenario requires E2E_SCENARIO="
                + expected
                + ", E2E_RUN_ID=<uuid>, -Plive-llm-test, and an exact reservation");
    return runId;
  }

  private boolean liveFixtureRequested() {
    return List.of("happy", "reject", "cancel", "build")
            .contains(String.valueOf(System.getenv("E2E_SCENARIO")))
        && isUuid(System.getenv("E2E_RUN_ID"))
        && Boolean.parseBoolean(System.getProperty("executespec.live-llm.enabled", "false"))
        && Boolean.parseBoolean(System.getProperty("executespec.live-llm.test-mode", "false"))
        && !System.getProperty("executespec.live-llm.reservation-id", "").isBlank();
  }

  private static boolean isUuid(String value) {
    try {
      UUID.fromString(value);
      return true;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private void submitReservedRun(String runId) {
    submitReservedRun(runId, SIMPLE_TASK);
  }

  private void submitReservedRun(String runId, String spec) {
    ResponseEntity<RunSummary> response =
        post("/api/e2e/reserved-runs/" + runId, new ReservedRunRequest(spec), RunSummary.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().runId()).isEqualTo(runId);
    assertThat(response.getBody().aiProvider()).isEqualTo(PROVIDER);
  }

  private String getStatus(String runId) {
    ResponseEntity<RunDetail> response = get("/api/runs/" + runId, RunDetail.class);
    return response.getBody() == null ? "NOT_FOUND" : response.getBody().getStatus();
  }

  private RunDetail getDetail(String runId) {
    ResponseEntity<RunDetail> response = get("/api/runs/" + runId, RunDetail.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }

  private void awaitStatus(String runId, String expected, Duration timeout) {
    await("run " + runId + " → " + expected)
        .atMost(timeout)
        .pollInterval(Duration.ofSeconds(6))
        .until(
            () -> {
              String status = getStatus(runId);
              return expected.equals(status)
                  || List.of("FAILED", "CANCELLED", "COMPLETE", "PARTIAL").contains(status);
            });
    assertThat(getStatus(runId)).isEqualTo(expected);
  }

  private String awaitTerminal(String runId, Duration timeout) {
    await("run " + runId + " → terminal")
        .atMost(timeout)
        .pollInterval(Duration.ofSeconds(6))
        .until(
            () -> List.of("COMPLETE", "PARTIAL", "FAILED", "CANCELLED").contains(getStatus(runId)));
    return getStatus(runId);
  }

  private ValidationReportDTO awaitReport(String runId) {
    await("validation report for " + runId)
        .atMost(SETTLE_TIMEOUT)
        .pollInterval(Duration.ofSeconds(3))
        .until(
            () ->
                get("/api/runs/" + runId + "/report", ValidationReportDTO.class).getStatusCode()
                    == HttpStatus.OK);
    ResponseEntity<ValidationReportDTO> response =
        get("/api/runs/" + runId + "/report", ValidationReportDTO.class);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }

  private HttpHeaders authenticatedHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(bearerToken);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private <T> ResponseEntity<T> get(String path, Class<T> responseType) {
    return rest.exchange(
        path, HttpMethod.GET, new HttpEntity<>(authenticatedHeaders()), responseType);
  }

  private <T> ResponseEntity<T> post(String path, Object body, Class<T> responseType) {
    return rest.exchange(
        path, HttpMethod.POST, new HttpEntity<>(body, authenticatedHeaders()), responseType);
  }

  private ResponseEntity<Void> delete(String path) {
    return rest.exchange(
        path, HttpMethod.DELETE, new HttpEntity<>(authenticatedHeaders()), Void.class);
  }

  record ReservedRunRequest(String userRequest) {}

  /**
   * Test-context-only submission seam. It exists because provider budget reservations bind an exact
   * run UUID before the first asynchronous planner call, while the public POST /api/runs
   * intentionally allocates its own UUID.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class ReservedRunSubmissionTestConfiguration {
    @Bean
    PipelineE2ETransportEvidence transportEvidence() {
      return new PipelineE2ETransportEvidence();
    }

    @Bean
    ReservedRunSubmissionController reservedRunSubmissionController(
        CustomerRunSubmissionService submissionService,
        RunOrchestrator runOrchestrator,
        RunMapper runMapper) {
      return new ReservedRunSubmissionController(submissionService, runOrchestrator, runMapper);
    }
  }

  @RequestMapping("/api/e2e/reserved-runs")
  @org.springframework.stereotype.Controller
  static class ReservedRunSubmissionController {

    private final CustomerRunSubmissionService submissionService;
    private final RunOrchestrator runOrchestrator;
    private final RunMapper runMapper;

    ReservedRunSubmissionController(
        CustomerRunSubmissionService submissionService,
        RunOrchestrator runOrchestrator,
        RunMapper runMapper) {
      this.submissionService = submissionService;
      this.runOrchestrator = runOrchestrator;
      this.runMapper = runMapper;
    }

    @PostMapping("/{runId}")
    @ResponseBody
    ResponseEntity<RunSummary> submit(
        @PathVariable String runId,
        @RequestBody ReservedRunRequest request,
        @AuthenticationPrincipal AppUserDetails actor) {
      CustomerRunSubmissionService.Command command =
          CustomerRunSubmissionService.Command.builder()
              .userRequest(request.userRequest())
              .source("MANUAL")
              .skipApproval(false)
              .autoMerge(false)
              .modelName(MODEL)
              .aiProvider(PROVIDER)
              .rememberProviderPreference(false)
              .executionMode("PRODUCTION")
              .userCostCap(COST_CAP_USD)
              .skipAutoPush(true)
              .requireConfirmationBeforeExpensiveContinuation(true)
              .requireBenchmarkPriorForAutomaticContinuation(true)
              .idempotencyKey("pipeline-e2e-" + runId)
              .requestFingerprint(
                  RunRequestFingerprint.of(List.of(request.userRequest(), PROVIDER, MODEL, runId)))
              .build();
      PipelineRun run =
          runOrchestrator.withReservedRunId(
              runId, () -> submissionService.submit(command, actor).run());
      return ResponseEntity.accepted().body(runMapper.toSummary(run, false, false));
    }
  }
}
