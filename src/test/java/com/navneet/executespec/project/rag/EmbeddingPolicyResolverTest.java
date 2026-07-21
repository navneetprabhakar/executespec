package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ProjectProviderResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EmbeddingPolicyResolverTest {

  private final ProjectRepository projects = mock(ProjectRepository.class);
  private final OrgRepository orgs = mock(OrgRepository.class);
  private final ProjectProviderResolver providers = mock(ProjectProviderResolver.class);
  private final ApiKeyResolver keys = mock(ApiKeyResolver.class);
  private final Project project =
      Project.builder().id("p1").orgId("o1").createdBy("u1").name("P").build();
  private EmbeddingPolicyResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new EmbeddingPolicyResolver(projects, orgs, providers, keys);
    ReflectionTestUtils.setField(resolver, "platformOpenAiKey", "global-openai-key");
    when(projects.findById("p1")).thenReturn(Optional.of(project));
    when(orgs.findById("o1"))
        .thenReturn(
            Optional.of(
                Org.builder()
                    .id("o1")
                    .name("O")
                    .domainPatterns("example.com")
                    .keySourceMode("OWN_KEYS")
                    .build()));
  }

  @Test
  void deepSeekOnlyProjectDoesNotUseGlobalOpenAiEvenWhenConfigured() {
    when(providers.resolve(project)).thenReturn(LlmProvider.DEEPSEEK);

    var availability = resolver.resolve("p1");

    assertThat(availability.semanticSearchAvailable()).isFalse();
    assertThat(availability.mode()).isEqualTo(EmbeddingPolicyResolver.Mode.LEXICAL);
    assertThat(availability.reason())
        .isEqualTo("SELECTED_PROVIDER_HAS_NO_COMPATIBLE_EMBEDDING_MODEL");
    verify(keys, never()).resolveWithProvenance("u1");
  }

  @Test
  void ownKeysOpenAiWithoutCustomerKeyCannotFallThroughToGlobalKey() {
    when(providers.resolve(project)).thenReturn(LlmProvider.OPENAI);
    when(keys.resolveWithProvenance("u1"))
        .thenReturn(credentials(null, "OWN_KEYS", Set.of("openai"), Map.of()));

    var availability = resolver.resolve("p1");

    assertThat(availability.semanticSearchAvailable()).isFalse();
    assertThat(availability.reason()).isEqualTo("OWN_KEYS_OPENAI_CREDENTIAL_UNAVAILABLE");
  }

  @Test
  void ownKeysOpenAiUsesOnlyThePresentCustomerCredential() {
    when(providers.resolve(project)).thenReturn(LlmProvider.OPENAI);
    when(keys.resolveWithProvenance("u1"))
        .thenReturn(
            credentials(
                "customer-key",
                "OWN_KEYS",
                Set.of("openai"),
                Map.of("openai", ApiKeyResolver.CredentialOrigin.ORG)));

    var availability = resolver.resolve("p1");

    assertThat(availability.semanticSearchAvailable()).isTrue();
    assertThat(availability.provider()).isEqualTo("openai");
    assertThat(availability.model()).isEqualTo(EmbeddingService.DEFAULT_MODEL);
  }

  @Test
  void providerAllowlistDisablesEmbeddingEvenWhenCustomerKeyExists() {
    project.setAiProvider(LlmProvider.OPENAI);
    when(providers.resolve(project)).thenReturn(LlmProvider.OPENAI);
    when(orgs.findById("o1"))
        .thenReturn(
            Optional.of(
                Org.builder()
                    .id("o1")
                    .name("O")
                    .domainPatterns("example.com")
                    .keySourceMode("OWN_KEYS")
                    .enabledProviders("deepseek")
                    .build()));

    var availability = resolver.resolve("p1");

    assertThat(availability.semanticSearchAvailable()).isFalse();
    assertThat(availability.reason()).isEqualTo("SELECTED_PROVIDER_DISABLED_BY_ORG");
    verify(keys, never()).resolveWithProvenance("u1");
  }

  @Test
  void systemModeCanUsePlatformKeyOnlyWhenPolicyExplicitlySelectsOpenAi() {
    when(providers.resolve(project)).thenReturn(LlmProvider.OPENAI);
    when(orgs.findById("o1"))
        .thenReturn(
            Optional.of(
                Org.builder()
                    .id("o1")
                    .name("O")
                    .domainPatterns("example.com")
                    .keySourceMode("SYSTEM")
                    .enabledProviders("openai")
                    .build()));
    when(keys.resolveWithProvenance("u1"))
        .thenReturn(credentials(null, "SYSTEM", Set.of("openai"), Map.of()));

    assertThat(resolver.resolve("p1").semanticSearchAvailable()).isTrue();
  }

  private static ApiKeyResolver.CredentialResolution credentials(
      String openAi,
      String mode,
      Set<String> providers,
      Map<String, ApiKeyResolver.CredentialOrigin> origins) {
    return new ApiKeyResolver.CredentialResolution(
        new ResolvedKeys(null, openAi, null, null, null, null, mode, providers), "o1", origins);
  }
}
