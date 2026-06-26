package com.navneet.executespec.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.project.rag.RetrievalService;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.tool.BuildProjectTool;

import org.junit.jupiter.api.Test;

/**
 * Pins the tier-config tuning that keeps iteration limits as a safety net, not the primary quality
 * ceiling:
 *
 * <ul>
 *   <li>RL-2026-06 cap ladder: LOW=60, MEDIUM=120, HIGH=180. Project runs double the cap because
 *       they need more repo inspection before the first durable edit.
 *   <li>Per-tier wall-clock budget, loosened again by RL-2026-05-004 to cut PARTIAL: LOW=30min
 *       MEDIUM=45min, HIGH=75min. Doubled for project runs (mirrors the iter-cap multiplier).
 *   <li>Stagnation thresholds are looser than the old static 8/12 guard but still much smaller than
 *       the iteration cap, so read-only loops terminate while productive edit/verify loops keep
 *       working.
 * </ul>
 *
 * <p>{@code tierConfigFor} is private — accessed via reflection so the public {@code execute}
 * surface doesn't have to be widened just to make caps unit-testable.
 */
class SequentialExecutorTierConfigTest {

  private SequentialExecutor newExecutor() {
    ModelResolver modelResolver = mock(ModelResolver.class);
    // Force the resolve call to throw so tierConfigFor falls back to run-level provider+model.
    // We don't care about resolved model name here; we care about the iter/stagnation/wall-clock
    // numbers.
    lenient()
        .when(modelResolver.resolve(anyString(), any()))
        .thenThrow(new RuntimeException("not wired in unit test"));
    return new SequentialExecutor(
        mock(com.navneet.executespec.engine.WorkerAgent.class),
        mock(BuildProjectTool.class),
        new ObjectMapper(),
        mock(Metrics.class),
        mock(SseBroadcaster.class),
        mock(AgentTaskRepository.class),
        modelResolver,
        mock(com.navneet.executespec.router.ModelMatrixProperties.class),
        mock(RetrievalService.class),
        mock(com.navneet.executespec.smoketest.WorkspaceTestRunner.class),
        mock(com.navneet.executespec.config.featureflag.FeatureFlagService.class),
        mock(ContextStore.class),
        mock(RunCostTracker.class),
        mock(com.navneet.executespec.deployment.GcsTaskCheckpointService.class),
        null);
  }

  /** Reflectively invoke {@code tierConfigFor} and return field-by-field for assertion. */
  private record Cfg(
      String provider,
      String model,
      int maxIter,
      int maxTokens,
      int stagnationWarnAfter,
      int stagnationForceAfter,
      int wallClockSeconds) {}

  private Cfg invoke(
      SequentialExecutor exec,
      PlannedTask.Complexity complexity,
      String provider,
      String model,
      boolean projectRun)
      throws Exception {
    Method m =
        SequentialExecutor.class.getDeclaredMethod(
            "tierConfigFor",
            PlannedTask.Complexity.class,
            String.class,
            String.class,
            boolean.class);
    m.setAccessible(true);
    Object cfg = m.invoke(exec, complexity, provider, model, projectRun);
    assertNotNull(cfg);
    return new Cfg(
        (String) recordField(cfg, "provider"),
        (String) recordField(cfg, "model"),
        (int) recordField(cfg, "maxIter"),
        (int) recordField(cfg, "maxTokens"),
        (int) recordField(cfg, "stagnationWarnAfter"),
        (int) recordField(cfg, "stagnationForceAfter"),
        (int) recordField(cfg, "wallClockSeconds"));
  }

  private static Object recordField(Object record, String name) throws Exception {
    Method accessor = record.getClass().getDeclaredMethod(name);
    accessor.setAccessible(true);
    return accessor.invoke(record);
  }

  @Test
  void medium_fresh_run_iter_cap_is_120() throws Exception {
    Cfg c = invoke(newExecutor(), PlannedTask.Complexity.MEDIUM, "anthropic", "claude-foo", false);
    assertEquals(120, c.maxIter, "MEDIUM gets enough room for inspect -> edit -> verify loops");
  }

  @Test
  void medium_project_run_iter_cap_is_240() throws Exception {
    Cfg c = invoke(newExecutor(), PlannedTask.Complexity.MEDIUM, "anthropic", "claude-foo", true);
    assertEquals(240, c.maxIter, "project-run multiplier ×2 → 120 × 2 = 240");
  }

  @Test
  void low_project_iter_cap_is_120() throws Exception {
    Cfg c = invoke(newExecutor(), PlannedTask.Complexity.LOW, "anthropic", "claude-foo", true);
    assertEquals(120, c.maxIter, "LOW iter cap 60; project ×2 = 120");
  }

  @Test
  void high_project_iter_cap_is_360() throws Exception {
    Cfg c = invoke(newExecutor(), PlannedTask.Complexity.HIGH, "anthropic", "claude-foo", true);
    assertEquals(360, c.maxIter, "HIGH iter cap 180; project ×2 = 360");
  }

  @Test
  void low_wall_clock_30min_fresh_60min_project() throws Exception {
    Cfg fresh = invoke(newExecutor(), PlannedTask.Complexity.LOW, "anthropic", "x", false);
    Cfg proj = invoke(newExecutor(), PlannedTask.Complexity.LOW, "anthropic", "x", true);
    assertEquals(30 * 60, fresh.wallClockSeconds, "LOW wall-clock 15 → 30min (RL-2026-05-004)");
    assertEquals(60 * 60, proj.wallClockSeconds, "project ×2 = 60min");
  }

  @Test
  void medium_wall_clock_45min_fresh_90min_project() throws Exception {
    Cfg fresh = invoke(newExecutor(), PlannedTask.Complexity.MEDIUM, "anthropic", "x", false);
    Cfg proj = invoke(newExecutor(), PlannedTask.Complexity.MEDIUM, "anthropic", "x", true);
    assertEquals(45 * 60, fresh.wallClockSeconds, "MEDIUM wall-clock 25 → 45min (RL-2026-05-004)");
    assertEquals(90 * 60, proj.wallClockSeconds, "project ×2 = 90min");
  }

  @Test
  void high_wall_clock_75min_fresh_150min_project() throws Exception {
    Cfg fresh = invoke(newExecutor(), PlannedTask.Complexity.HIGH, "anthropic", "x", false);
    Cfg proj = invoke(newExecutor(), PlannedTask.Complexity.HIGH, "anthropic", "x", true);
    assertEquals(75 * 60, fresh.wallClockSeconds, "HIGH wall-clock 45 → 75min (RL-2026-05-004)");
    assertEquals(150 * 60, proj.wallClockSeconds, "project ×2 = 150min");
  }

  @Test
  void wall_clock_is_positive_for_every_complexity_and_run_mode() throws Exception {
    for (PlannedTask.Complexity c : PlannedTask.Complexity.values()) {
      for (boolean projectRun : new boolean[] {false, true}) {
        Cfg cfg = invoke(newExecutor(), c, "anthropic", "x", projectRun);
        assertTrue(
            cfg.wallClockSeconds > 0,
            "wallClockSeconds must be positive for "
                + c
                + " projectRun="
                + projectRun
                + ", got "
                + cfg.wallClockSeconds);
      }
    }
  }

  // Stagnation thresholds (project-run bumps)

  @Test
  void low_stagnation_force_24_on_project_runs() throws Exception {
    Cfg fresh = invoke(newExecutor(), PlannedTask.Complexity.LOW, "anthropic", "x", false);
    Cfg proj = invoke(newExecutor(), PlannedTask.Complexity.LOW, "anthropic", "x", true);
    assertEquals(
        16, fresh.stagnationForceAfter, "fresh-scratch LOW gets a modestly looser force threshold");
    assertEquals(
        24,
        proj.stagnationForceAfter,
        "project-run LOW gets 24 (readFile-heavy reverse-engineering needs" + " more headroom)");
  }

  @Test
  void medium_stagnation_force_36_on_project_runs() throws Exception {
    Cfg fresh = invoke(newExecutor(), PlannedTask.Complexity.MEDIUM, "anthropic", "x", false);
    Cfg proj = invoke(newExecutor(), PlannedTask.Complexity.MEDIUM, "anthropic", "x", true);
    assertEquals(28, fresh.stagnationForceAfter, "fresh-scratch MEDIUM force threshold");
    assertEquals(36, proj.stagnationForceAfter, "project-run MEDIUM gets extra read headroom");
  }

  @Test
  void high_stagnation_force_unchanged_on_project_runs() throws Exception {
    Cfg fresh = invoke(newExecutor(), PlannedTask.Complexity.HIGH, "anthropic", "x", false);
    Cfg proj = invoke(newExecutor(), PlannedTask.Complexity.HIGH, "anthropic", "x", true);
    assertEquals(40, fresh.stagnationForceAfter);
    assertEquals(40, proj.stagnationForceAfter, "HIGH already has generous stagnation budget");
  }

  @Test
  void warn_threshold_unchanged_on_project_runs() throws Exception {
    // Warn threshold is the "first-shot PROGRESS CHECK" point — should stay unchanged so the
    // model still gets the nudge at the same stagnation depth. Only force-terminate budget
    // grows.
    for (PlannedTask.Complexity c : PlannedTask.Complexity.values()) {
      Cfg fresh = invoke(newExecutor(), c, "anthropic", "x", false);
      Cfg proj = invoke(newExecutor(), c, "anthropic", "x", true);
      assertEquals(
          fresh.stagnationWarnAfter,
          proj.stagnationWarnAfter,
          "warn threshold must not change between fresh and project for " + c);
    }
  }
}
