package com.navneet.executespec.test;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retained compatibility surface for the retired Phase-0 native-provider smoke.
 *
 * <p>The original endpoint called provider clients and Spring AI model beans directly. That bypass
 * cannot participate in canonical {@code LlmRouter} admission because an HTTP smoke request owns no
 * durable {@code PLATFORM_INTERNAL} operation lease or process-local operation authority. Therefore
 * both routes fail closed without resolving credentials, reserving budget, or invoking any provider
 * transport. A future replacement must establish that durable authority at a root-facing service
 * before calling the canonical router.
 */
@org.springframework.context.annotation.Profile({"local", "dev"})
@RestController
@RequestMapping("/api/v2/test")
public class NativeToolsTestController {

  static final String LIVE_PROVIDER_SMOKE_DISABLED =
      "Live native-provider smoke is retired: canonical PLATFORM_INTERNAL operation authority is"
          + " required";

  @GetMapping("/native-tools")
  public Map<String, Object> testNativeTools(
      @RequestParam String provider,
      @RequestParam(required = false) String model,
      @RequestParam(defaultValue = "native-tools-smoke") String runId) {
    Map<String, Object> result = disabledResult();
    result.put("provider", provider);
    result.put("runId", runId);
    if (model != null && !model.isBlank()) result.put("modelOverride", model);
    return result;
  }

  @GetMapping("/native-tools/all")
  public Map<String, Object> testAll(
      @RequestParam(defaultValue = "native-tools-matrix") String runId) {
    Map<String, Object> result = disabledResult();
    result.put("runId", runId);
    result.put("totalTested", 0);
    result.put("passed", 0);
    result.put("failed", 0);
    return result;
  }

  private static Map<String, Object> disabledResult() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ok", false);
    result.put("disabled", true);
    result.put("error", LIVE_PROVIDER_SMOKE_DISABLED);
    return result;
  }
}
