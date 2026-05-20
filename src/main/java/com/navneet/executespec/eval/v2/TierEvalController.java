package com.navneet.executespec.eval.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.eval.v2.cases.LightweightCases;
import com.navneet.executespec.eval.v2.cases.PlanningCases;
import com.navneet.executespec.eval.v2.cases.WorkhorseCases;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Tier-eval v2 admin endpoint. Production-shaped per-tier evaluation that exercises the same
 * prompts + tool schemas the live agents use. Distinct from {@code /api/admin/eval/run} (the
 * legacy text-quality benchmark) — keep both, pick whichever question matches what you're
 * answering.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/eval/v2")
@RequiredArgsConstructor
public class TierEvalController {

  private final TierEvalRunner runner;

  /**
   * Run every selected tier × provider combination once. Default: all tiers, all providers.
   *
   * @param tiers comma-separated subset of {@code LIGHTWEIGHT,PLANNING} (case-insensitive). Omit
   *     for both.
   * @param providers comma-separated subset of {@code anthropic,openai,gemini}. Omit for all
   *     three.
   */
  @PostMapping("/run")
  public ResponseEntity<?> run(
      @RequestParam(value = "tiers", required = false) String tiers,
      @RequestParam(value = "providers", required = false) String providers) {
    List<String> tierList =
        (tiers == null || tiers.isBlank())
            ? List.of("LIGHTWEIGHT", "PLANNING", "WORKHORSE")
            : java.util.Arrays.stream(tiers.split(",")).map(String::trim).map(String::toUpperCase)
                .toList();
    List<String> providerList =
        (providers == null || providers.isBlank())
            ? List.of("anthropic", "openai", "gemini")
            : java.util.Arrays.stream(providers.split(",")).map(String::trim).map(String::toLowerCase)
                .toList();

    List<TierEvalCase> singleShot = new ArrayList<>();
    if (tierList.contains("LIGHTWEIGHT")) singleShot.addAll(LightweightCases.all());
    if (tierList.contains("PLANNING")) singleShot.addAll(PlanningCases.all());
    boolean wantWorkhorse = tierList.contains("WORKHORSE");
    if (singleShot.isEmpty() && !wantWorkhorse) {
      return ResponseEntity.badRequest().body(Map.of("error", "no cases — tiers=" + tierList));
    }
    int whCount = wantWorkhorse ? WorkhorseCases.all().size() : 0;
    log.info(
        "tier-eval-v2 START single-shot={} workhorse={} providers={} (~{} LLM units)",
        singleShot.size(),
        whCount,
        providerList,
        (singleShot.size() + whCount) * providerList.size());
    try {
      TierEvalReport base = runner.runAll(singleShot, providerList);
      List<TierEvalResult> all = new ArrayList<>(base.results());
      if (wantWorkhorse) {
        all.addAll(runner.runAllWorkhorse(WorkhorseCases.all(), providerList));
      }
      return ResponseEntity.ok(
          TierEvalReport.builder()
              .evaluationId(base.evaluationId())
              .startedAt(base.startedAt())
              .durationMs(base.durationMs())
              .results(all)
              .build());
    } catch (Exception e) {
      log.error("tier-eval-v2 failed", e);
      return ResponseEntity.internalServerError()
          .body(Map.of("error", "tier-eval-v2 failed: " + e.getMessage()));
    }
  }
}
