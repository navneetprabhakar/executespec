package com.navneet.executespec.rollout;

import java.util.LinkedHashMap;
import java.util.Map;

import com.navneet.executespec.agent.Classification;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Public V2 run API. Two endpoints:
 *
 * <ul>
 *   <li>{@code POST /api/v2/runs} — submit a run. Gated by {@link HarnessVersionResolver} — if the
 *       currently-configured flag value does not resolve to V2 (and this is not an explicit bypass
 *       call), returns 403 with a clear error so clients don't silently get the wrong pipeline.
 *   <li>{@code GET /api/v2/runs/{runId}} — poll a run's current status.
 * </ul>
 *
 * <p>Phase 9 baseline — V1 callers continue to use V1's {@code /api/runs} unchanged. V1 endpoint
 * routing integration (the plan's "flag check in /api/runs POST" step) is a follow-up once V2 has
 * shown green on internal canary testing.
 */
@RestController
@RequestMapping("/api/v2/runs")
@RequiredArgsConstructor
@Slf4j
public class RunController {

  private final RunService runService;

  @PostMapping
  public ResponseEntity<Map<String, Object>> submit(@RequestBody SubmitRequest body) {
    if (body == null || body.spec() == null || body.spec().isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "spec is required"));
    }
    if (body.provider() == null || body.model() == null) {
      return ResponseEntity.badRequest().body(Map.of("error", "provider and model are required"));
    }

    // V2 is the only harness — no routing gate.
    Classification classification =
        body.classification() != null
            ? Classification.valueOf(body.classification().toUpperCase())
            : Classification.MEDIUM;

    Run run =
        runService.submit(
            new RunService.Request(
                body.spec(),
                body.provider(),
                body.model(),
                classification,
                body.workspaceSummary(),
                body.maxIterationsPerTask(),
                body.enableSmokeTests() == null ? true : body.enableSmokeTests(),
                body.runReviewer() == null ? true : body.runReviewer(),
                body.skipWiringPass() == null ? false : body.skipWiringPass()));

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("runId", run.runId());
    resp.put("status", run.status().name());
    resp.put("submittedAt", run.submittedAt().toString());
    resp.put("harnessVersion", "v2");
    return ResponseEntity.accepted().body(resp);
  }

  @GetMapping("/{runId}")
  public ResponseEntity<Map<String, Object>> poll(@PathVariable String runId) {
    return runService
        .find(runId)
        .map(this::snapshot)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private Map<String, Object> snapshot(Run run) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("runId", run.runId());
    m.put("status", run.status().name());
    m.put("submittedAt", run.submittedAt().toString());
    m.put("statusUpdatedAt", run.statusUpdatedAt().toString());
    m.put("provider", run.provider());
    m.put("model", run.model());
    m.put("classification", run.classification());
    if (run.plan() != null) {
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("planTitle", run.plan().planTitle());
      p.put("taskCount", run.plan().tasks().size());
      p.put("buildCommand", run.plan().buildCommand());
      p.put("smokeTestStrategy", run.plan().smokeTestStrategy().name());
      m.put("plan", p);
    }
    if (run.executionResult() != null) {
      var er = run.executionResult();
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("overall", er.overall().name());
      e.put("tasksExecuted", er.tasksExecuted());
      e.put("tasksSuccessful", er.tasksSuccessful());
      e.put("tasksWithWarning", er.tasksWithWarning());
      e.put("tasksFailed", er.tasksFailed());
      e.put("totalLatencyMs", er.totalLatencyMs());
      m.put("execution", e);
    }
    if (run.reviewerResult() != null) {
      var rr = run.reviewerResult();
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("overall", rr.overall().name());
      r.put("finalBuildPassed", rr.finalBuildPassed());
      r.put("finalBuildExitCode", rr.finalBuildExitCode());
      r.put("generalPassSummary", rr.generalPassSummary());
      r.put("wiringPassSummary", rr.wiringPassSummary());
      r.put("totalLatencyMs", rr.totalLatencyMs());
      m.put("review", r);
    }
    if (run.failureMessage() != null) m.put("failureMessage", run.failureMessage());
    if (run.completedAt() != null) m.put("completedAt", run.completedAt().toString());
    m.put(
        "statusLog",
        run.statusLog().stream()
            .map(
                ev -> {
                  Map<String, Object> lm = new LinkedHashMap<>();
                  lm.put("status", ev.status().name());
                  lm.put("at", ev.at().toString());
                  if (ev.note() != null) lm.put("note", ev.note());
                  return lm;
                })
            .toList());
    return m;
  }

  public record SubmitRequest(
      String spec,
      String provider,
      String model,
      String classification,
      String workspaceSummary,
      Integer maxIterationsPerTask,
      Boolean enableSmokeTests,
      Boolean runReviewer,
      Boolean skipWiringPass,
      Boolean force) {}
}
