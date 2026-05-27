package com.navneet.executespec.state;

/**
 * Discriminator for which V2 pipeline shape a {@link
 * com.navneet.executespec.state.entity.PipelineRun} uses. Stored as a string on {@code
 * pipeline_run.run_mode} (V182). Enum-shaped helper class — actual JPA column is a String to match
 * the existing convention for {@code harnessVersion}, {@code executionMode}, {@code status}, etc.
 *
 * <p><b>BUILD</b> — the existing classifier → planner → SequentialExecutor → reviewer flow. Default
 * for every product code path. Right shape for greenfield specs, multi-task work, and anything that
 * benefits from plan-approval gating.
 *
 * <p><b>PATCH</b> — single thin-agent loop. One {@link com.navneet.executespec.engine.WorkerAgent}
 * dispatched on the whole user request, full conversation context, no plan / no reviewer / no
 * per-task split, force PLANNING-tier model (don't downgrade to Haiku-class for "small" tasks),
 * 75-iter cap, relaxed stagnation guard. Right shape for bug-fix work where the input is a specific
 * failing test or a targeted edit. Today only the swebench + programbench bench services submit
 * with this mode; product entry points keep the BUILD default.
 *
 * <p>The string constants on this class are the authoritative spelling. Don't introduce literal
 * {@code "BUILD"} / {@code "PATCH"} elsewhere — reference these constants so a rename remains
 * mechanically safe.
 */
public final class RunMode {

  /** The default V2 pipeline (planner + executor + reviewer). */
  public static final String BUILD = "BUILD";

  /** Single thin-agent loop for bug-fix shapes. */
  public static final String PATCH = "PATCH";

  private RunMode() {
    // No instances.
  }

  /** True when {@code mode} is {@link #PATCH}. Null-safe. */
  public static boolean isPatch(String mode) {
    return PATCH.equals(mode);
  }
}
