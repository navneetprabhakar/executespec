package com.navneet.executespec.eval;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Operator-only endpoint for running model evaluations.
 *
 * <p>Gated three ways. SecurityConfig maps {@code /api/admin/**} to SUPER_ADMIN; the class-level
 * {@link org.springframework.security.access.prepost.PreAuthorize} repeats that at the method
 * boundary so role enforcement is not solely positional in the SecurityConfig matcher chain; and
 * the {@link org.springframework.context.annotation.Profile} restriction keeps the bean out of the
 * production graph entirely.
 *
 * <p>The profile gate matters because a single call fans out a 54-request platform-key provider
 * grid with no reservation, idempotency key or budget ceiling, and its synthetic run id means the
 * spend is unattributed to any customer or managed-AI period. That must never be reachable in prod
 * (RR-1633). This mirrors the gating already applied to the {@code test/*} fixture controllers.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/eval")
@RequiredArgsConstructor
@org.springframework.context.annotation.Profile({"local", "dev", "benchmark"})
@org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
public class EvalController {

  private final ModelEvaluator modelEvaluator;

  /**
   * Trigger a full model evaluation. Runs 6 models x 9 test cases = 54 LLM calls (~10-15 min).
   * Returns the evaluation report with per-tier recommendations.
   */
  @PostMapping("/run")
  public ResponseEntity<?> runEvaluation(
      @RequestParam(value = "providers", required = false) List<String> providers) {
    Set<String> providerFilter =
        (providers != null && !providers.isEmpty()) ? new java.util.HashSet<>(providers) : null;
    log.info(
        "[EvalController] Model evaluation triggered by admin, providers={}",
        providerFilter != null ? providerFilter : "ALL");
    try {
      EvalReport report = modelEvaluator.evaluate(providerFilter);
      return ResponseEntity.ok(
          Map.of(
              "evaluationId", report.evaluationId(),
              "durationMs", report.durationMs(),
              "modelsEvaluated", report.modelResults().size(),
              "recommendations", report.recommendations(),
              "report", report));
    } catch (Exception e) {
      log.error("[EvalController] Evaluation failed", e);
      return ResponseEntity.internalServerError()
          .body(Map.of("error", "Evaluation failed: " + e.getMessage()));
    }
  }
}
