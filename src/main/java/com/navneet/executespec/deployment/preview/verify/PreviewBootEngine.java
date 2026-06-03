package com.navneet.executespec.deployment.preview.verify;

import java.nio.file.Path;

import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootSignals;

/**
 * The build-and-boot half of verifyBoot (RL-2026-05-066): builds an image (buildpack default or the
 * agent's {@code Dockerfile}) and runs it in the hardened, deny-by-default-egress cleanroom, then
 * returns the raw {@link BootSignals} for {@link BootHealthEvaluator} to classify.
 *
 * <p>This is the seam preview <b>Phase 0</b> fills (buildpack adapter + per-preview isolated
 * network + dedicated least-privilege service account + {@code CodeAbuseScanner} pre-build hook —
 * see the parent plan §6). Until a bean implementing this exists, {@link PreviewVerifier} finds no
 * engine and <b>fails open</b> (returns empty), so verifyBoot is completely inert and the BUILD
 * score keeps its compile-only signal — no behaviour change.
 *
 * <p>Security contract for any implementation (non-negotiable): run {@code CodeAbuseScanner} over
 * the workspace before building (fail-closed on a hit); dispatch through {@code
 * ShellSandboxPolicy}; run the container with {@code --network none} (no egress unless the recipe
 * declares a plan-gate- reviewed allowlist), non-root, {@code cap_drop ALL}, {@code
 * no-new-privileges}, read-only rootfs, resource caps, cloud-metadata + internal CIDRs blocked at
 * the network layer; pass NO platform secrets into the boot env (reuse the {@code ComposeExecutor}
 * env-var whitelist); enforce the recipe's {@code startTimeoutSec} (hard-capped at {@link
 * RunRecipe#MAX_START_TIMEOUT_SEC}); always tear the container down.
 */
public interface PreviewBootEngine {

  /**
   * Build + boot + health-probe the app at {@code workspace}, returning the raw signals. May throw
   * — {@link PreviewVerifier} treats any exception as fail-open (empty), so an implementation never
   * needs to swallow its own failures into a fake "healthy".
   */
  BootSignals boot(Path workspace, RunRecipe recipe, String runId) throws Exception;
}
