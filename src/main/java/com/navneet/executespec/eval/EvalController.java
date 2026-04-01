package com.navneet.executespec.eval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin-only endpoint for running model evaluations.
 * Secured via SecurityConfig: /api/admin/** requires SUPER_ADMIN role.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/eval")
@RequiredArgsConstructor
public class EvalController {

    private final ModelEvaluator modelEvaluator;

    /**
     * Trigger a full model evaluation.
     * Runs 6 models x 9 test cases = 54 LLM calls (~10-15 min).
     * Returns the evaluation report with per-tier recommendations.
     */
    @PostMapping("/run")
    public ResponseEntity<?> runEvaluation(
            @RequestParam(value = "providers", required = false) List<String> providers) {
        Set<String> providerFilter = (providers != null && !providers.isEmpty())
                ? new java.util.HashSet<>(providers) : null;
        log.info("[EvalController] Model evaluation triggered by admin, providers={}", providerFilter != null ? providerFilter : "ALL");
        try {
            EvalReport report = modelEvaluator.evaluate(providerFilter);
            return ResponseEntity.ok(Map.of(
                    "evaluationId", report.evaluationId(),
                    "durationMs", report.durationMs(),
                    "modelsEvaluated", report.modelResults().size(),
                    "recommendations", report.recommendations(),
                    "report", report
            ));
        } catch (Exception e) {
            log.error("[EvalController] Evaluation failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Evaluation failed: " + e.getMessage()
            ));
        }
    }
}
