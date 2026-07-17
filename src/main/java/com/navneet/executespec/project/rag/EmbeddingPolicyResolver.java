package com.navneet.executespec.project.rag;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ProjectProviderResolver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Secret-free preview of whether the selected project route can produce compatible vectors. */
@Service
@RequiredArgsConstructor
public class EmbeddingPolicyResolver {

  public enum Mode {
    SEMANTIC,
    LEXICAL
  }

  public record Availability(
      Mode mode,
      boolean semanticSearchAvailable,
      boolean lexicalFallbackAvailable,
      String provider,
      String model,
      String reason) {}

  private final ProjectRepository projectRepository;
  private final OrgRepository orgRepository;
  private final ProjectProviderResolver projectProviderResolver;
  private final ApiKeyResolver apiKeyResolver;

  @Value("${spring.ai.openai.api-key:}")
  private String platformOpenAiKey;

  @Value("${project.rag.embedding.openai.model:text-embedding-3-small}")
  private String embeddingModel = EmbeddingService.DEFAULT_MODEL;

  public Availability resolve(String projectId) {
    var project = projectRepository.findById(projectId).orElse(null);
    if (project == null) return lexical("PROJECT_NOT_FOUND", null);

    LlmProvider selected;
    try {
      selected = projectProviderResolver.resolve(project);
    } catch (RuntimeException invalidPolicy) {
      return lexical("PROJECT_PROVIDER_POLICY_INVALID", null);
    }
    String provider = selected.name().toLowerCase(Locale.ROOT);
    if (!"openai".equals(provider)) {
      return lexical("SELECTED_PROVIDER_HAS_NO_COMPATIBLE_EMBEDDING_MODEL", provider);
    }

    var org = orgRepository.findById(project.getOrgId()).orElse(null);
    if (org == null) return lexical("PROJECT_ORG_NOT_FOUND", provider);
    Set<String> enabled = parseProviders(org.getEnabledProviders());
    if (enabled != null && !enabled.contains(provider)) {
      return lexical("SELECTED_PROVIDER_DISABLED_BY_ORG", provider);
    }
    ApiKeyResolver.CredentialResolution credentials =
        apiKeyResolver.resolveWithProvenance(project.getCreatedBy());
    if (!project.getOrgId().equals(credentials.orgId())) {
      return lexical("CREDENTIAL_SUBJECT_TENANT_MISMATCH", provider);
    }
    String mode =
        org.getKeySourceMode() == null
            ? "HYBRID"
            : org.getKeySourceMode().trim().toUpperCase(Locale.ROOT);
    boolean customerKey = credentials.originFor(provider) != null;
    boolean platformKey = platformOpenAiKey != null && !platformOpenAiKey.isBlank();
    boolean available =
        switch (mode) {
          case "OWN_KEYS" -> customerKey;
          case "SYSTEM" -> platformKey;
          case "HYBRID" -> customerKey || platformKey;
          default -> false;
        };
    if (!available) {
      return lexical(
          "OWN_KEYS".equals(mode)
              ? "OWN_KEYS_OPENAI_CREDENTIAL_UNAVAILABLE"
              : "OPENAI_CREDENTIAL_UNAVAILABLE",
          provider);
    }
    return new Availability(
        Mode.SEMANTIC, true, true, provider, embeddingModel, "GOVERNED_OPENAI_EMBEDDING_AVAILABLE");
  }

  private static Availability lexical(String reason, String provider) {
    return new Availability(Mode.LEXICAL, false, true, provider, null, reason);
  }

  private static Set<String> parseProviders(String csv) {
    if (csv == null || csv.isBlank()) return null;
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .map(String::toLowerCase)
        .map(value -> "google".equals(value) ? "gemini" : value)
        .filter(value -> !value.isBlank())
        .collect(Collectors.toUnmodifiableSet());
  }
}
