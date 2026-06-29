package com.navneet.executespec.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.JwtAuthenticationFilter;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.entitlements.EntitlementResolver;
import com.navneet.executespec.entitlements.EntitlementsResponse;
import com.navneet.executespec.entitlements.GovernancePolicyResolver;
import com.navneet.executespec.entitlements.GovernancePolicyResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * R-142 — single-source entitlements endpoint for v3 surfaces.
 *
 * <p>Mounted under /api/v3 to keep the v2 surface untouched. The billing / pricing /
 * composer-gating logic on v3 reads exclusively from this contract, so the strict-review
 * billing-state contradiction (FREE label on an ENTERPRISE org) cannot recur.
 */
@RestController
@RequestMapping("/api/v3")
@RequiredArgsConstructor
public class EntitlementsController {

  private final EntitlementResolver resolver;
  private final GovernancePolicyResolver governancePolicyResolver;
  private final FeatureFlagService featureFlagService;

  @GetMapping("/entitlements")
  public ResponseEntity<EntitlementsResponse> me(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      return ResponseEntity.status(401).build();
    }
    return ResponseEntity.ok(resolver.resolve(userDetails.getUser().getId()));
  }

  /**
   * Read side of T26 — the effective governance/model/tool policy for the caller. The CLI {@code
   * policy} / {@code doctor} surfaces consume this so they show the real policy instead of
   * inferring it. Pure-read; reflects only what the enforcement guards actually apply.
   */
  @GetMapping("/governance-policy")
  public ResponseEntity<GovernancePolicyResponse> governancePolicy(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      return ResponseEntity.status(401).build();
    }
    return ResponseEntity.ok(governancePolicyResolver.resolve(userDetails.getUser().getId()));
  }

  /**
   * Runtime compatibility contract for split CLI / VS Code clients. This is intentionally separate
   * from `/agent/v2/capabilities`: that endpoint lists model-visible actions; this endpoint lists
   * backend REST/SSE contracts the local client must be able to trust before entering local-hands.
   */
  @GetMapping("/client-capabilities")
  public ResponseEntity<ClientCapabilitiesResponse> clientCapabilities(
      @AuthenticationPrincipal AppUserDetails userDetails, HttpServletRequest request) {
    if (userDetails == null) {
      return ResponseEntity.status(401).build();
    }

    String authSource =
        String.valueOf(request.getAttribute(JwtAuthenticationFilter.AUTH_SOURCE_ATTRIBUTE));
    boolean patAuthenticated = JwtAuthenticationFilter.AUTH_SOURCE_PAT.equals(authSource);
    boolean localHandsEnabled = featureFlagService.isEnabled("worker.local-hands.enabled");

    return ResponseEntity.ok(
        new ClientCapabilitiesResponse(
            1,
            "2026-06-local-hands-v1",
            Instant.now(),
            authSource,
            new LocalHandsCapability(
                true,
                localHandsEnabled,
                "PAT",
                patAuthenticated,
                localHandsEnabled && patAuthenticated,
                "/api/runs",
                "/api/runs/{runId}/tool-results",
                List.of(
                    "localExecutionAccepted=true is required before the client starts the local tool pump",
                    "Browser/JWT local execution is rejected because the browser cannot enforce local permissions",
                    "Local CLI/TUI permission policy remains the execution gate")),
            Map.ofEntries(
                endpoint(
                    "runs.submit",
                    "POST",
                    "/api/runs",
                    true,
                    "Creates runs; localExecution=true is PAT-only and feature-flag gated"),
                endpoint(
                    "runs.detail",
                    "GET",
                    "/api/runs/{runId}",
                    true,
                    "Run-read guarded detail used by TUI attach/reload"),
                endpoint(
                    "runs.events",
                    "GET",
                    "/api/runs/{runId}/events",
                    true,
                    "Scoped SSE-token stream for live run events"),
                endpoint(
                    "runs.eventReplay",
                    "GET",
                    "/api/runs/{runId}/event-log",
                    true,
                    "Run-read guarded 24h replay for attach/backfill"),
                endpoint(
                    "runs.report",
                    "GET",
                    "/api/runs/{runId}/report",
                    true,
                    "Run-read guarded validation report"),
                endpoint(
                    "runs.cost",
                    "GET",
                    "/api/runs/{runId}/cost",
                    true,
                    "Run-read guarded cost/token snapshot"),
                endpoint(
                    "runs.toolResults",
                    "POST",
                    "/api/runs/{runId}/tool-results",
                    true,
                    "Run-write guarded, PAT-only local tool-result endpoint"),
                endpoint(
                    "agent.turnStream",
                    "POST",
                    "/api/agent/sessions/{sessionId}/turns/stream",
                    true,
                    "Durable agent turn stream for CLI/VS Code/Web sessions"),
                endpoint(
                    "agent.capabilities",
                    "GET",
                    "/api/agent/v2/capabilities",
                    true,
                    "Surface-specific model-visible action catalog"),
                endpoint(
                    "models.catalog",
                    "GET",
                    "/api/models/me",
                    true,
                    "Authenticated provider/model catalog with disabled providers filtered"),
                endpoint(
                    "managedAi.balance",
                    "GET",
                    "/api/billing/managed-ai/balance",
                    true,
                    "Authenticated current-user managed-AI balance"),
                endpoint(
                    "governance.policy",
                    "GET",
                    "/api/v3/governance-policy",
                    true,
                    "Authenticated effective governance/model/tool policy")),
            new EventContractCapability(
                true,
                1,
                List.of(
                    "TOOL_CALL",
                    "TOOL_RESULT",
                    "STATUS",
                    "TOKEN_USAGE",
                    "RUN_DETAIL",
                    "PLAN_READY",
                    "GATE_OPENED",
                    "DONE"),
                8000,
                true),
            new ToolResultContractCapability(
                true,
                true,
                true,
                true,
                60000,
                4000,
                true,
                List.of("ACCEPTED", "DUPLICATE", "UNKNOWN_SESSION", "UNKNOWN_CALL")),
            new WebFetchCapability(
                true,
                true,
                true,
                true,
                true,
                "untrusted",
                "specchat.WebSearchChatTools"),
            new ProviderCatalogCapability(
                true,
                "/api/models/me",
                List.of("sarvam"),
                true,
                "Disabled providers are filtered from normal model catalogs and fail closed if forced"),
            new GovernanceCapability(true, "/api/v3/governance-policy"),
            new ManagedAiCapability(
                true,
                "/api/billing/managed-ai/balance",
                "/api/billing/managed-ai/policy")));
  }

  private static Map.Entry<String, EndpointCapability> endpoint(
      String name, String method, String path, boolean authenticated, String note) {
    return Map.entry(name, new EndpointCapability(method, path, authenticated, note));
  }

  public record ClientCapabilitiesResponse(
      int schemaVersion,
      String contractVersion,
      Instant generatedAt,
      String authSource,
      LocalHandsCapability localHands,
      Map<String, EndpointCapability> endpoints,
      EventContractCapability eventContracts,
      ToolResultContractCapability toolResultContract,
      WebFetchCapability webFetch,
      ProviderCatalogCapability providerCatalog,
      GovernanceCapability governance,
      ManagedAiCapability managedAi) {}

  public record LocalHandsCapability(
      boolean supported,
      boolean enabled,
      String requiredAuthSource,
      boolean currentAuthSourceAllowed,
      boolean currentRequestCanSubmitLocalHands,
      String submitPath,
      String toolResultsPath,
      List<String> notes) {}

  public record EndpointCapability(String method, String path, boolean authenticated, String note) {}

  public record EventContractCapability(
      boolean typedEventsSupported,
      int schemaVersion,
      List<String> eventTypes,
      int typedPayloadMaxChars,
      boolean redactedDisplayMetadata) {}

  public record ToolResultContractCapability(
      boolean strictEnvelope,
      boolean duplicateOutcomeDeterministic,
      boolean unknownCallRejected,
      boolean terminalRunRejected,
      int successContentMaxChars,
      int errorMessageMaxChars,
      boolean secretRedaction,
      List<String> dispatcherOutcomes) {}

  public record WebFetchCapability(
      boolean explicitActionOnly,
      boolean blocksLoopbackAndPrivateIp,
      boolean blocksRedirectToPrivateIp,
      boolean rejectsSecretBearingUrls,
      boolean responseBounded,
      String trustLabel,
      String implementation) {}

  public record ProviderCatalogCapability(
      boolean available,
      String path,
      List<String> disabledProviders,
      boolean providerErrorsRedacted,
      String note) {}

  public record GovernanceCapability(boolean available, String path) {}

  public record ManagedAiCapability(boolean balanceAvailable, String balancePath, String policyPath) {}
}
