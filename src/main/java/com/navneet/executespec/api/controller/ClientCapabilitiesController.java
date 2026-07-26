package com.navneet.executespec.api.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import com.navneet.executespec.auth.security.JwtAuthenticationFilter;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** Read-only runtime contract consumed by the CLI and VS Code local-hands clients. */
@RestController
@RequestMapping("/api/v3")
@RequiredArgsConstructor
public class ClientCapabilitiesController {

  private static final List<String> EVENT_TYPES =
      List.of("RUN_STATUS", "LOG", "TOOL_CALL", "FILE_WRITTEN", "TOKEN_USAGE", "RUN_DETAIL");

  private final FeatureFlagService featureFlagService;
  private final SandboxCommandRoutingService sandboxRouting;

  @GetMapping("/client-capabilities")
  public ClientCapabilities capabilities(HttpServletRequest request) {
    boolean localHandsEnabled = featureFlagService.isEnabled("worker.local-hands.enabled");
    SandboxCommandRoutingService.Readiness sandbox = sandboxRouting.readiness();
    // RR-646 — resolve the ACTUAL request auth source instead of hard-coding "authenticated" with
    // currentAuthSourceAllowed=true. Local-hands run submission is PAT-only (RunController rejects
    // localExecution=true for browser/JWT). A JWT/browser caller previously received machine-
    // readable authorization saying its current request could submit local execution, which the
    // run endpoint then rejected — capability-driven clients read that as server drift.
    String authSource = resolveAuthSource(request);
    boolean pat = JwtAuthenticationFilter.AUTH_SOURCE_PAT.equals(authSource);
    boolean currentRequestCanSubmitLocalHands = pat && localHandsEnabled;
    return new ClientCapabilities(
        1,
        "agent-protocol-v2",
        Instant.now(),
        authSource,
        new LocalHands(
            true,
            localHandsEnabled,
            "PAT",
            pat,
            currentRequestCanSubmitLocalHands,
            "/api/runs",
            "/api/runs/{runId}/tool-results",
            List.of(
                "Local-hands execution is accepted only for PAT-authenticated clients.",
                "The browser/JWT surface cannot submit local execution.")),
        new RemoteSandbox(
            true,
            sandbox.enabled(),
            sandbox.lifecycleEnabled(),
            sandbox.dispatchEnabled(),
            sandbox.strictContainerEnabled(),
            sandbox.toolchainReady(),
            sandbox.imageDigestPinned(),
            sandbox.runtimeAvailable(),
            sandbox.enabled() ? "CONTAINER_STRICT_V1" : "NOT_ENABLED",
            sandbox.enabled()
                ? "Commands are routed to the strict run container."
                : "Strict sandbox execution is disabled; runs use a policy-guarded server"
                    + " workspace and must not be advertised as isolated sandboxes. "
                    + sandbox.reason()),
        Map.of(
            "agentSessions",
                new Endpoint("GET", "/api/agent/sessions", true, "Durable agent sessions"),
            "agentTurns",
                new Endpoint(
                    "GET", "/api/agent/sessions/{id}/turns", true, "Reloadable turn history"),
            "runEvents",
                new Endpoint("GET", "/api/runs/{id}/events", true, "Scoped SSE run events")),
        new EventContracts(true, 1, EVENT_TYPES, 12000, true),
        new ToolResultContract(
            true,
            true,
            true,
            true,
            12000,
            2000,
            true,
            List.of("OK", "ERROR", "INTERNAL", "TIMEOUT", "DENIED")),
        new WebFetch(true, true, true, true, true, "untrusted", "explicit read-only web tool"),
        new ProviderCatalog(
            true,
            "/api/v3/provider-readiness",
            List.of(),
            true,
            "Authenticated account-effective readiness; deployment health is not dispatch"
                + " authority."),
        new Governance(true, "/api/v3/governance-policy"),
        new ManagedAi(true, "/api/billing/managed-ai/balance", "/api/billing/managed-ai/policy"));
  }

  /**
   * The auth source stamped by {@link JwtAuthenticationFilter} ("PAT" or "JWT"), or "anonymous"
   * when no credential was resolved for this request.
   */
  private static String resolveAuthSource(HttpServletRequest request) {
    Object source =
        request == null
            ? null
            : request.getAttribute(JwtAuthenticationFilter.AUTH_SOURCE_ATTRIBUTE);
    return source instanceof String s && !s.isBlank() ? s : "anonymous";
  }

  public record ClientCapabilities(
      int schemaVersion,
      String contractVersion,
      Instant generatedAt,
      String authSource,
      LocalHands localHands,
      RemoteSandbox remoteSandbox,
      Map<String, Endpoint> endpoints,
      EventContracts eventContracts,
      ToolResultContract toolResultContract,
      WebFetch webFetch,
      ProviderCatalog providerCatalog,
      Governance governance,
      ManagedAi managedAi) {}

  public record LocalHands(
      boolean supported,
      boolean enabled,
      String requiredAuthSource,
      boolean currentAuthSourceAllowed,
      boolean currentRequestCanSubmitLocalHands,
      String submitPath,
      String toolResultsPath,
      List<String> notes) {}

  public record RemoteSandbox(
      boolean supported,
      boolean enabled,
      boolean lifecycleEnabled,
      boolean dispatchEnabled,
      boolean strictContainerEnabled,
      boolean toolchainReady,
      boolean imageDigestPinned,
      boolean runtimeAvailable,
      String isolationProfile,
      String note) {}

  public record Endpoint(String method, String path, boolean authenticated, String note) {}

  public record EventContracts(
      boolean typedEventsSupported,
      int schemaVersion,
      List<String> eventTypes,
      int typedPayloadMaxChars,
      boolean redactedDisplayMetadata) {}

  public record ToolResultContract(
      boolean strictEnvelope,
      boolean duplicateOutcomeDeterministic,
      boolean unknownCallRejected,
      boolean terminalRunRejected,
      int successContentMaxChars,
      int errorMessageMaxChars,
      boolean secretRedaction,
      List<String> dispatcherOutcomes) {}

  public record WebFetch(
      boolean explicitActionOnly,
      boolean blocksLoopbackAndPrivateIp,
      boolean blocksRedirectToPrivateIp,
      boolean rejectsSecretBearingUrls,
      boolean responseBounded,
      String trustLabel,
      String implementation) {}

  public record ProviderCatalog(
      boolean available,
      String path,
      List<String> disabledProviders,
      boolean providerErrorsRedacted,
      String note) {}

  public record Governance(boolean available, String path) {}

  public record ManagedAi(boolean balanceAvailable, String balancePath, String policyPath) {}
}
