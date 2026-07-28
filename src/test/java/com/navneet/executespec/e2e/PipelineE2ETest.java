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
 * <p>The three provider-backed scenarios are fail-closed: each Maven invocation must select exactly
 * one scenario, preselect its run UUID, enable the live-LLM test mode, and supply a budget-ledger
 * reservation matching {@code DEEPSEEK/deepseek-v4-flash-native/<run UUID>}. The test-only reserved
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
  }

  @DynamicPropertySource
  static void isolatedDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  private static final String PROVIDER = "DEEPSEEK";
  private static final String MODEL = "deepseek-v4-flash-native";
  private static final double COST_CAP_USD = 0.10;
  private static final Duration PLAN_TIMEOUT = Duration.ofMinutes(4);
  private static final Duration WORK_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration SETTLE_TIMEOUT = Duration.ofMinutes(2);
  private static final String SIMPLE_TASK =
      "Create a plain text file called hello.txt in the workspace root. "
          + "The file must contain exactly one line: Hello World";

  private static final ApprovalRequest APPROVE =
      new ApprovalRequest(true, "Reserved E2E approval", null, null, null, null, null);
  private static final ApprovalRequest REJECT =
      new ApprovalRequest(false, "Reserved E2E rejection", null, null, null, null, null);

  @Autowired private TestRestTemplate rest;
  @Autowired private AppUserRepository userRepository;
  @Autowired private OrgRepository orgRepository;
  @Autowired private OrgMemberRepository orgMemberRepository;
  @Autowired private JwtUtil jwtUtil;

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
  }

  @AfterAll
  void cleanWorkspace() throws IOException {
    org.springframework.util.FileSystemUtils.deleteRecursively(Path.of("test-workspace-e2e"));
  }

  @Test
  @DisplayName("V2 happy path: authenticated submit → plan approval → automatic terminal state")
  void happyPath_autoMergesWithoutLegacyMergeGate() throws IOException {
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

    ValidationReportDTO report = awaitReport(runId);
    assertThat(report.filesGenerated()).isGreaterThanOrEqualTo(1);
    assertThat(report.verificationLedger()).isNotEmpty();
    assertThat(report.verificationLedger())
        .anyMatch(evidence -> "DETERMINISTIC".equals(evidence.source()));
    assertThat(report.runGoalDecision()).isNotNull();
    assertThat(report.runGoalDecision().state().name()).isEqualTo("STOP_DONE");

    // The authenticated compatibility endpoint exists only to return a clear V2 retirement signal.
    assertThat(post("/api/runs/" + runId + "/merge", APPROVE, Void.class).getStatusCode())
        .isEqualTo(HttpStatus.GONE);
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
    return List.of("happy", "reject", "cancel").contains(System.getenv("E2E_SCENARIO"))
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
    ResponseEntity<RunSummary> response =
        post(
            "/api/e2e/reserved-runs/" + runId,
            new ReservedRunRequest(SIMPLE_TASK),
            RunSummary.class);
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
