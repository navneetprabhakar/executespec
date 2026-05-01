package com.navneet.executespec.auth.dto;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import com.navneet.executespec.auth.entity.Org;

public record OrgDto(
    String id,
    String name,
    String domainPatterns,
    String tier,
    String status,
    int monthlyRunCount,
    /** v2.6.0 — TEAM-tier seat count (shared pool). 1 for non-TEAM orgs. */
    int seatCount,
    boolean isPaymentVerified,
    Integer monthlyRunLimitOverride,
    boolean apiKeysProvided,
    Instant planExpiresAt,
    Instant createdAt,
    // Admin contact
    String adminEmail,
    // Per-org AI provider key flags (raw keys never exposed)
    boolean hasAnthropicKey,
    boolean hasOpenAiKey,
    boolean hasGeminiKey,
    // Key source mode & provider restrictions
    String keySourceMode,
    List<String> enabledProviders,
    // JIRA integration flags (raw credentials never exposed)
    boolean hasJiraCredentials,
    String jiraBaseUrl,
    // Confluence integration flags (raw credentials never exposed)
    boolean hasConfluenceCredentials,
    String confluenceBaseUrl) {
  private static boolean present(String s) {
    return s != null && !s.isBlank();
  }

  public static OrgDto from(Org org) {
    return new OrgDto(
        org.getId(),
        org.getName(),
        org.getDomainPatterns(),
        org.getTier(),
        org.getStatus(),
        org.getMonthlyRunCount(),
        org.getSeatCount(),
        org.isPaymentVerified(),
        org.getMonthlyRunLimitOverride(),
        org.isApiKeysProvided(),
        org.getPlanExpiresAt(),
        org.getCreatedAt(),
        org.getAdminEmail(),
        present(org.getAnthropicApiKey()),
        present(org.getOpenAiApiKey()),
        present(org.getGeminiApiKey()),
        org.getKeySourceMode(),
        parseProviders(org.getEnabledProviders()),
        // JIRA: all 3 fields must be present
        present(org.getJiraBaseUrl())
            && present(org.getJiraEmail())
            && present(org.getJiraApiToken()),
        org.getJiraBaseUrl(),
        // Confluence: all 3 fields must be present
        present(org.getConfluenceBaseUrl())
            && present(org.getConfluenceEmail())
            && present(org.getConfluenceApiToken()),
        org.getConfluenceBaseUrl());
  }

  private static List<String> parseProviders(String csv) {
    if (csv == null || csv.isBlank()) return null;
    return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
  }
}
