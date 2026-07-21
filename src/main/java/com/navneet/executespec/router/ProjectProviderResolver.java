package com.navneet.executespec.router;

import java.util.Locale;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Resolves the provider used by project-scoped LLM features.
 *
 * <p>The project setting is authoritative. Legacy rows may have a null provider, in which case the
 * creator's account preference is used. Missing or invalid provider policy fails closed instead of
 * silently spending against a platform default. Keeping this policy in one seam prevents
 * architecture, docs, memory, and review features from drifting to different providers.
 */
@Service
@RequiredArgsConstructor
public class ProjectProviderResolver {

  private final ProjectRepository projectRepository;
  private final AppUserRepository appUserRepository;

  public LlmProvider resolve(Project project) {
    if (project == null)
      throw new IllegalArgumentException("project required for provider routing");
    if (project.getAiProvider() != null) return project.getAiProvider();
    return resolveAccountPreference(project.getCreatedBy());
  }

  public LlmProvider resolve(String projectId) {
    if (projectId == null || projectId.isBlank()) {
      throw new IllegalArgumentException("projectId required for provider routing");
    }
    return projectRepository
        .findById(projectId)
        .map(this::resolve)
        .orElseThrow(() -> new IllegalArgumentException("project not found for provider routing"));
  }

  /** Explicit run/provider overrides take precedence over project policy. */
  public LlmProvider resolve(String projectId, LlmProvider explicitProvider) {
    return explicitProvider != null ? explicitProvider : resolve(projectId);
  }

  private LlmProvider resolveAccountPreference(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new IllegalStateException("project has no provider and no creator preference subject");
    }
    return appUserRepository
        .findById(userId)
        .map(user -> normalize(user.getPreferredAiProvider()))
        .orElseThrow(() -> new IllegalStateException("project creator not found for routing"));
  }

  private static LlmProvider normalize(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("project provider is unset and account preference is blank");
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if ("GOOGLE".equals(normalized)) normalized = "GEMINI";
    try {
      return LlmProvider.valueOf(normalized);
    } catch (IllegalArgumentException ignored) {
      throw new IllegalStateException("invalid account provider preference: " + value);
    }
  }
}
