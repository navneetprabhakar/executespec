package com.navneet.executespec.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.api.dto.ApprovalRequest;
import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.api.dto.RunSummary;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * End-to-end pipeline tests. Each test drives a full run through the REST API, approving or
 * rejecting gates as a real user would.
 *
 * <p>Prerequisites: - PostgreSQL running at localhost:5432/executespec - ANTHROPIC_API_KEY or
 * spring.ai.anthropic.api-key set
 *
 * <p>Run with: mvn test -Dgroups=e2e -Dspring.profiles.active=e2e
 *
 * <p>Or via IDE: add VM arg -Dspring.profiles.active=e2e and run this class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(
    properties = {
      "spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY:test-key-e2e}",
      "spring.ai.openai.api-key=${OPENAI_API_KEY:test-key-e2e}",
      "agent.workspace=./test-workspace-e2e"
    })
class PipelineE2ETest {

  @Autowired private TestRestTemplate rest;

  // ── Timing constants ──────────────────────────────────────────────────────

  /** Max time to wait for the planner LLM to produce a plan. */
  static final Duration PLAN_TIMEOUT = Duration.ofMinutes(4);

  /** Max time to wait for all worker agents to finish. */
  static final Duration WORK_TIMEOUT = Duration.ofMinutes(10);

  /** Max time to wait for a terminal state after a gate decision. */
  static final Duration SETTLE_TIMEOUT = Duration.ofMinutes(2);

  // ── Test tasks ────────────────────────────────────────────────────────────

  /**
   * Smallest possible task — creates a single file. Keeps LLM calls cheap and fast; suitable for
   * all gate-flow tests.
   */
  static final String SIMPLE_TASK =
      "Create a plain text file called hello.txt in the workspace root. "
          + "The file must contain exactly one line: Hello World";

  /**
   * Slightly richer task that produces verifiable artefacts. Use this when you want to inspect
   * worker output after the test.
   */
  static final String SCRIPT_TASK =
      "Create a Python script called greet.py that accepts a --name argument "
          + "and prints 'Hello, <name>!'. Include a short README.md explaining usage.";

  // ── Approval helpers ──────────────────────────────────────────────────────

  static final ApprovalRequest APPROVE = new ApprovalRequest(true, "E2E auto-approve", null, null);
  static final ApprovalRequest REJECT =
      new ApprovalRequest(false, "E2E test rejection", null, null);

  // ══════════════════════════════════════════════════════════════════════════
  // Test 1 — Happy path: full pipeline from submit to COMPLETE
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  @Order(1)
  @DisplayName("Happy path: submit → approve plan → workers run → approve merge → COMPLETE")
  void happyPath_fullPipeline() {
    // 1. Submit run
    String runId = submitRun(SIMPLE_TASK);

    // 2. Wait for plan to be ready
    awaitStatus(runId, "AWAITING_APPROVAL", PLAN_TIMEOUT);

    // Inspect the plan before approving (optional — uncomment to print)
    // RunDetail prePlan = getDetail(runId);
    // System.out.println("Plan JSON: " + prePlan.planJson());

    // 3. Approve plan gate
    ResponseEntity<Void> approveResp =
        rest.postForEntity("/api/runs/" + runId + "/approve", APPROVE, Void.class);
    assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    // 4. Wait for workers to finish
    awaitStatus(runId, "AWAITING_MERGE", WORK_TIMEOUT);

    // 5. Approve merge gate
    ResponseEntity<Void> mergeResp =
        rest.postForEntity("/api/runs/" + runId + "/merge", APPROVE, Void.class);
    assertThat(mergeResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    // 6. Wait for COMPLETE
    awaitStatus(runId, "COMPLETE", SETTLE_TIMEOUT);

    // 7. Verify final state
    RunDetail detail = getDetail(runId);
    assertThat(detail.getStatus()).isEqualTo("COMPLETE");
    assertThat(detail.getCompletedAt()).isNotNull();
    assertThat(detail.getFailureReason()).isNull();
    assertThat(detail.getTasks()).isNotEmpty();
    assertThat(detail.getIntegrationSource()).isNull(); // manual run — no integration source
  }

  // ══════════════════════════════════════════════════════════════════════════
  // Test 2 — Plan rejection: user rejects the plan → FAILED
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  @Order(2)
  @DisplayName("Plan rejection: user rejects plan → run marked FAILED")
  void planRejection_marksRunFailed() {
    String runId = submitRun(SIMPLE_TASK);
    awaitStatus(runId, "AWAITING_APPROVAL", PLAN_TIMEOUT);

    // Reject the plan
    rest.postForEntity("/api/runs/" + runId + "/approve", REJECT, Void.class);

    awaitStatus(runId, "FAILED", SETTLE_TIMEOUT);

    RunDetail detail = getDetail(runId);
    assertThat(detail.getStatus()).isEqualTo("FAILED");
    assertThat(detail.getFailureReason()).isNotBlank();
  }

  // ══════════════════════════════════════════════════════════════════════════
  // Test 3 — Merge rejection: user rejects the merge → FAILED
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  @Order(3)
  @DisplayName("Merge rejection: user rejects merge after workers finish → run marked FAILED")
  void mergeRejection_marksRunFailed() {
    String runId = submitAndApprovePlan(SIMPLE_TASK);
    awaitStatus(runId, "AWAITING_MERGE", WORK_TIMEOUT);

    // Reject the merge
    rest.postForEntity("/api/runs/" + runId + "/merge", REJECT, Void.class);

    awaitStatus(runId, "FAILED", SETTLE_TIMEOUT);

    RunDetail detail = getDetail(runId);
    assertThat(detail.getStatus()).isEqualTo("FAILED");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // Test 4 — Cancellation while awaiting plan approval
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  @Order(4)
  @DisplayName("Cancel run during AWAITING_APPROVAL → run marked FAILED")
  void cancelDuringPlanGate_marksRunFailed() {
    String runId = submitRun(SIMPLE_TASK);
    awaitStatus(runId, "AWAITING_APPROVAL", PLAN_TIMEOUT);

    // Cancel the run
    rest.delete("/api/runs/" + runId);

    awaitStatus(runId, "FAILED", SETTLE_TIMEOUT);

    RunDetail detail = getDetail(runId);
    assertThat(detail.getStatus()).isEqualTo("FAILED");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // Test 5 — Run listing and status filter
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  @Order(5)
  @DisplayName("List runs: status filter returns only matching runs")
  void listRuns_statusFilter_returnsMatchingRuns() {
    // Submit a run and hold it at plan gate so we can observe AWAITING_APPROVAL
    String runId = submitRun(SIMPLE_TASK);
    awaitStatus(runId, "AWAITING_APPROVAL", PLAN_TIMEOUT);

    // GET /api/runs?status=AWAITING_APPROVAL
    ResponseEntity<List<RunSummary>> resp =
        rest.exchange(
            "/api/runs?status=AWAITING_APPROVAL",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {});

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).extracting(RunSummary::runId).contains(runId);

    // Verify the run is not in a different status bucket
    ResponseEntity<List<RunSummary>> completedResp =
        rest.exchange(
            "/api/runs?status=COMPLETE",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {});
    assertThat(completedResp.getBody()).extracting(RunSummary::runId).doesNotContain(runId);

    // Cleanup — reject the plan gate so the run terminates
    rest.postForEntity("/api/runs/" + runId + "/approve", REJECT, Void.class);
    awaitStatus(runId, "FAILED", SETTLE_TIMEOUT);
  }

  // ══════════════════════════════════════════════════════════════════════════
  // Test 6 — GET /api/runs/{id}: 404 for unknown runId
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  @Order(6)
  @DisplayName("GET /api/runs/unknown-id returns 404")
  void getUnknownRun_returns404() {
    ResponseEntity<RunDetail> resp =
        rest.getForEntity("/api/runs/00000000-0000-0000-0000-000000000000", RunDetail.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  // ══════════════════════════════════════════════════════════════════════════
  // Test 7 — Metrics endpoint is reachable
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  @Order(7)
  @DisplayName("GET /api/metrics returns 200 with run counts")
  void metrics_returns200() {
    ResponseEntity<Map<String, Object>> resp =
        rest.exchange("/api/metrics", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).containsKeys("totalRuns", "completedRuns", "failedRuns");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // Test 8 — Submit run with blank request returns 400
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  @Order(8)
  @DisplayName("POST /api/runs with blank userRequest returns 400")
  void submitBlankRequest_returns400() {
    // RunRequest with empty userRequest
    Map<String, String> body = Map.of("userRequest", "  ");
    ResponseEntity<Void> resp = rest.postForEntity("/api/runs", body, Void.class);
    assertThat(resp.getStatusCode().value()).isGreaterThanOrEqualTo(400);
  }

  // ══════════════════════════════════════════════════════════════════════════
  // Private helpers
  // ══════════════════════════════════════════════════════════════════════════

  /** Submits a run and returns its runId. Asserts 202 Accepted. */
  private String submitRun(String task) {
    Map<String, String> body = Map.of("userRequest", task, "source", "MANUAL");
    ResponseEntity<RunSummary> resp = rest.postForEntity("/api/runs", body, RunSummary.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(resp.getBody()).isNotNull();
    assertThat(resp.getBody().runId()).isNotBlank();
    return resp.getBody().runId();
  }

  /** Submits a run and approves the plan gate. Returns the runId at WORKING state. */
  private String submitAndApprovePlan(String task) {
    String runId = submitRun(task);
    awaitStatus(runId, "AWAITING_APPROVAL", PLAN_TIMEOUT);
    rest.postForEntity("/api/runs/" + runId + "/approve", APPROVE, Void.class);
    return runId;
  }

  /** Returns the current status of a run, or "NOT_FOUND" if the run does not exist. */
  private String getStatus(String runId) {
    RunDetail detail = rest.getForObject("/api/runs/" + runId, RunDetail.class);
    return detail != null ? detail.getStatus() : "NOT_FOUND";
  }

  private RunDetail getDetail(String runId) {
    RunDetail detail = rest.getForObject("/api/runs/" + runId, RunDetail.class);
    assertThat(detail).as("RunDetail for %s", runId).isNotNull();
    return detail;
  }

  /**
   * Polls until the run reaches {@code expected} OR any terminal state (FAILED/CANCELLED). Asserts
   * the actual status equals {@code expected} after polling completes.
   *
   * <p>Failing early on unexpected terminal states prevents tests hanging for their full timeout
   * when the pipeline encounters an LLM or infrastructure error.
   */
  private void awaitStatus(String runId, String expected, Duration timeout) {
    await("run " + runId + " → " + expected)
        .atMost(timeout)
        .pollInterval(Duration.ofSeconds(6))
        .until(
            () -> {
              String s = getStatus(runId);
              return expected.equals(s) || "FAILED".equals(s) || "CANCELLED".equals(s);
            });

    assertThat(getStatus(runId))
        .as("Expected run %s to be %s", runId, expected)
        .isEqualTo(expected);
  }
}
