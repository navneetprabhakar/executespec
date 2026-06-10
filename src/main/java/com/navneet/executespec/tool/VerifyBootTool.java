package com.navneet.executespec.tool;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootVerification;
import com.navneet.executespec.deployment.preview.verify.PreviewVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * verifyBoot worker tool (RL-2026-05-066, preview Phase 1) — lets a BUILD-mode worker build + boot
 * the runnable app it produced in an isolated, hardened sandbox and health-check it <i>in-loop</i>,
 * before {@code completeTask}, instead of shipping code that compiles but never actually runs.
 * Returns a deterministic boot verdict the BUILD score reads, replacing the LLM's guess about
 * whether the app works.
 *
 * <p>Wraps {@link PreviewVerifier} — reads the agent-authored {@code
 * .agent-context/run-recipe.json} (port + health path), hands the build-and-boot to the Phase-0
 * {@code PreviewBootEngine}, and classifies the result with {@code BootHealthEvaluator}. All the
 * security (CodeAbuseScanner pre-build, {@code --network none} cleanroom, non-root, resource +
 * timeout caps, secret-free env) lives in the boot engine; this tool is the worker-facing surface.
 *
 * <p><b>Flag-gated out of the schema:</b> {@code worker.verify-boot.enabled} (V227, default OFF).
 * When off, {@link ToolRegistry#bindForWorker(ExecutionContext)} does not bind this tool at all, so
 * schema-sensitive providers do not see an inert tool. The in-{@code execute} disabled envelope is
 * defense-in-depth for direct callers. Default-ON only after the boot-pass-rate × verifiedScore
 * eval + the red-team checklist in the parent plan. Because the {@code PreviewBootEngine} bean does
 * not exist until preview Phase 0, even with the flag ON this currently fails open to {@code
 * available:true, verified:false} — never a false "healthy".
 */
@Component
@Slf4j
public class VerifyBootTool implements Tool, FlagGatedTool {

  /** Feature flag — see class javadoc. */
  public static final String FLAG_VERIFY_BOOT = "worker.verify-boot.enabled";

  private final PreviewVerifier previewVerifier;
  private final FeatureFlagService featureFlagService;
  private final ObjectMapper json;

  @Autowired
  public VerifyBootTool(
      PreviewVerifier previewVerifier, FeatureFlagService featureFlagService, ObjectMapper json) {
    this.previewVerifier = previewVerifier;
    this.featureFlagService = featureFlagService;
    this.json = json;
  }

  @Override
  public boolean enabled() {
    return featureFlagService == null || featureFlagService.isEnabled(FLAG_VERIFY_BOOT);
  }

  @Override
  public String name() {
    return "verifyBoot";
  }

  @Override
  public String description() {
    return "Builds and boots the runnable app you produced in an isolated, network-restricted"
        + " sandbox, then probes its declared health endpoint and returns a deterministic"
        + " boot verdict (HEALTHY / UNHEALTHY / TIMED_OUT / DID_NOT_START / BUILD_FAILED)."
        + " Use before completeTask on any app meant to run (web service, API, frontend):"
        + " first ensure .agent-context/run-recipe.json declares the port + healthPath, then"
        + " call verifyBoot; if the verdict isn't HEALTHY, read the reason, fix the app, and"
        + " re-verify. A booting app is the BUILD signal the score reads — not 'it"
        + " compiles'.";
  }

  @Override
  public String inputSchema() {
    // The run-recipe file (.agent-context/run-recipe.json) is authoritative for port + healthPath +
    // startTimeoutSec; no arguments are needed. (Phase 0 may add optional per-call overrides.)
    return """
{
  "type": "object",
  "properties": {},
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    if (featureFlagService != null && !featureFlagService.isEnabled(FLAG_VERIFY_BOOT)) {
      ObjectNode disabled = json.createObjectNode();
      disabled.put("available", false);
      disabled.put(
          "summary",
          "verifyBoot is disabled on this run (flag worker.verify-boot.enabled=false). The BUILD "
              + "score will use the compile signal; you do not need a run-recipe.");
      return ToolResult.ok(disabled.toString());
    }

    Optional<BootVerification> opt = previewVerifier.verify(ctx.workspaceRoot(), ctx.runId());
    if (opt.isEmpty()) {
      ObjectNode unavailable = json.createObjectNode();
      unavailable.put("available", true);
      unavailable.put("verified", false);
      unavailable.put(
          "summary",
          "boot verification unavailable on this run — either there is no"
              + " .agent-context/run-recipe.json (write one:"
              + " {\"port\":<n>,\"healthPath\":\"/...\"}) for a runnable app, or the preview build"
              + " capability isn't enabled here. Not a failure of your code by itself.");
      return ToolResult.ok(unavailable.toString());
    }

    BootVerification v = opt.get();
    ObjectNode result = json.createObjectNode();
    result.put("available", true);
    result.put("verified", v.healthy());
    result.put("verdict", v.verdict().name());
    result.put("reason", v.reason());
    result.put("summary", v.verdict().name() + ": " + v.reason());

    log.info(
        "verifyBoot run={} task={} → verdict={} healthy={}",
        ctx.runId(),
        ctx.taskId(),
        v.verdict(),
        v.healthy());

    return ToolResult.ok(result.toString());
  }
}
