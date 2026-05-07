package com.navneet.executespec.v2.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.v2.agent.Classification;
import com.navneet.executespec.v2.agent.OutlinePlannerAgent;
import com.navneet.executespec.v2.agent.Plan;
import com.navneet.executespec.v2.agent.PlannedTask;
import com.navneet.executespec.v2.agent.PlannerRequest;
import com.navneet.executespec.v2.tool.ExecutionContext;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 Phase 4 verification endpoint.
 *
 * <p>v2.6.2 slice 5b — was tied to {@code PlannerAgent}; now exercises {@link OutlinePlannerAgent}.
 * SINGLE_SHOT goes through {@link OutlinePlannerAgent#synthesizeSingleShot} (no LLM call), MEDIUM /
 * MULTI_TASK go through {@link OutlinePlannerAgent#plan} in {@code FULL_PLAN} mode for parity with
 * the legacy probe.
 *
 * <p>Usage: {@code GET /api/v2/test/planner?provider=anthropic&model=claude-haiku-4-5-20251001} or
 * override spec via {@code &spec=...}.
 */
// Audit r5 (§6.2, 2026-05-06) — see ContextStoreTestController for rationale.
@org.springframework.context.annotation.Profile({"local", "dev"})
@RestController
@RequestMapping("/api/v2/test")
@RequiredArgsConstructor
@Slf4j
public class PlannerTestController {

  private static final String DEFAULT_SPEC_MEDIUM =
      "Build a small Node.js REST API with two endpoints: GET /health returning "
          + "{status:'ok'} and POST /echo returning the request body as-is. Use Express 4. "
          + "Include a minimal README with run instructions.";

  private static final String DEFAULT_SPEC_MULTI =
      "Build a production-grade Spring Boot 3.3 microservice for managing library books. "
          + "Requirements: JDBC-based persistence (do NOT use JPA), H2 in-memory DB, "
          + "three REST endpoints (list, add, delete), unit tests with JUnit 5, "
          + "OpenAPI 3 documentation via springdoc. Use constructor injection throughout.";

  private final OutlinePlannerAgent planner;

  @GetMapping("/planner")
  public Map<String, Object> testPlanner(
      @RequestParam(defaultValue = "anthropic") String provider,
      @RequestParam(defaultValue = "claude-haiku-4-5-20251001") String model,
      @RequestParam(required = false) String spec,
      @RequestParam(defaultValue = "ALL") String classification)
      throws IOException {
    Path workspace = Files.createTempDirectory("v2-planner-test-");
    try {
      ExecutionContext ctx =
          new ExecutionContext("planner-test-run", "planner-test-task", workspace.toRealPath());

      Map<String, Object> out = new LinkedHashMap<>();
      out.put("provider", provider);
      out.put("model", model);

      if ("ALL".equalsIgnoreCase(classification)) {
        // Run all three — SINGLE_SHOT locally (no LLM), MEDIUM/MULTI via LLM.
        out.put(
            "singleShot",
            probe(
                Classification.SINGLE_SHOT,
                spec != null
                    ? spec
                    : "Write a Python function that returns the Nth Fibonacci number and save it to"
                        + " fib.py",
                provider,
                model,
                ctx));
        out.put(
            "medium",
            probe(
                Classification.MEDIUM,
                spec != null ? spec : DEFAULT_SPEC_MEDIUM,
                provider,
                model,
                ctx));
        out.put(
            "multiTask",
            probe(
                Classification.MULTI_TASK,
                spec != null ? spec : DEFAULT_SPEC_MULTI,
                provider,
                model,
                ctx));
      } else {
        Classification cx = Classification.valueOf(classification.toUpperCase());
        out.put("result", probe(cx, spec != null ? spec : pickDefault(cx), provider, model, ctx));
      }
      return out;
    } finally {
      deleteTree(workspace);
    }
  }

  private Map<String, Object> probe(
      Classification cx, String spec, String provider, String model, ExecutionContext ctx) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("classification", cx.name());
    r.put("maxTasks", cx.maxTasks() == Integer.MAX_VALUE ? "unlimited" : cx.maxTasks());
    r.put("spec", spec);
    long start = System.currentTimeMillis();
    try {
      PlannerRequest req =
          PlannerRequest.builder()
              .spec(spec)
              .classification(cx)
              .provider(provider)
              .model(model)
              .context(ctx)
              .workspaceSummary("empty scratch workspace")
              .build();
      Plan plan =
          (cx == Classification.SINGLE_SHOT)
              ? planner.synthesizeSingleShot(req)
              : planner.plan(req, OutlinePlannerAgent.Mode.FULL_PLAN);
      r.put("ok", plan.tasks().size() >= 1 && plan.tasks().size() <= cx.maxTasks());
      r.put("latencyMs", System.currentTimeMillis() - start);
      r.put("planTitle", plan.planTitle());
      r.put("planSummary", plan.planSummary());
      r.put("projectType", plan.projectType());
      r.put("buildCommand", plan.buildCommand());
      r.put("smokeTestStrategy", plan.smokeTestStrategy().name());
      r.put("taskCount", plan.tasks().size());
      r.put("tasks", summarizeTasks(plan.tasks()));
    } catch (Exception e) {
      r.put("ok", false);
      r.put("latencyMs", System.currentTimeMillis() - start);
      String msg = e.getMessage();
      r.put(
          "error",
          e.getClass().getSimpleName()
              + ": "
              + (msg == null ? "" : (msg.length() > 500 ? msg.substring(0, 500) + "..." : msg)));
    }
    return r;
  }

  private static List<Map<String, Object>> summarizeTasks(List<PlannedTask> tasks) {
    return tasks.stream()
        .map(
            t -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("id", t.id());
              m.put("title", t.title());
              m.put("complexity", t.complexity().name());
              m.put("touchesFiles", t.touchesFiles());
              m.put("hardConstraints", t.hardConstraints());
              return m;
            })
        .toList();
  }

  private static String pickDefault(Classification cx) {
    return switch (cx) {
      case SINGLE_SHOT ->
          "Write a Python function that returns the Nth Fibonacci number and save it to fib.py";
      case MEDIUM -> DEFAULT_SPEC_MEDIUM;
      case MULTI_TASK -> DEFAULT_SPEC_MULTI;
    };
  }

  private static void deleteTree(Path root) {
    try {
      if (!Files.exists(root)) return;
      try (var walk = Files.walk(root)) {
        walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException ignored) {
                  }
                });
      }
    } catch (IOException ignored) {
    }
  }
}
