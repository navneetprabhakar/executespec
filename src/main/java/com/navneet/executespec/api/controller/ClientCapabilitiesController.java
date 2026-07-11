package com.navneet.executespec.api.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

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

  @GetMapping("/client-capabilities")
  public ClientCapabilities capabilities() {
    boolean localHandsEnabled = featureFlagService.isEnabled("worker.local-hands.enabled");
    return new ClientCapabilities(
        1,
        "agent-protocol-v2",
        Instant.now(),
        "authenticated",
        new LocalHands(
            true,
            localHandsEnabled,
            "PAT",
            true,
            localHandsEnabled,
            "/api/runs",
            "/api/runs/{runId}/tool-results",
            List.of(
                "Local-hands execution is accepted only for PAT-authenticated clients.",
                "The browser/JWT surface cannot submit local execution.")),
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
            true, "/api/health/providers", List.of(), true, "Provider health is read-only."),
        new Governance(true, "/api/v3/governance-policy"),
        new ManagedAi(true, "/api/billing/managed-ai/balance", "/api/billing/managed-ai/policy"));
  }

  public record ClientCapabilities(
      int schemaVersion,
      String contractVersion,
      Instant generatedAt,
      String authSource,
      LocalHands localHands,
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
