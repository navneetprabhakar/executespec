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
 * creator's account preference is used, followed by the canonical product default. Keeping this
 * policy in one seam prevents architecture, docs, memory, and review features from silently
 * drifting to different providers.
 */
@Service
@RequiredArgsConstructor
public class ProjectProviderResolver {

  public static final LlmProvider CANONICAL_DEFAULT = LlmProvider.ANTHROPIC;

  private final ProjectRepository projectRepository;
  private final AppUserRepository appUserRepository;

  public LlmProvider resolve(Project project) {
    if (project == null) return CANONICAL_DEFAULT;
    if (project.getAiProvider() != null) return project.getAiProvider();
    return resolveAccountPreference(project.getCreatedBy());
  }

  public LlmProvider resolve(String projectId) {
    if (projectId == null || projectId.isBlank()) return CANONICAL_DEFAULT;
    return projectRepository.findById(projectId).map(this::resolve).orElse(CANONICAL_DEFAULT);
  }

  /** Explicit run/provider overrides take precedence over project policy. */
  public LlmProvider resolve(String projectId, LlmProvider explicitProvider) {
    return explicitProvider != null ? explicitProvider : resolve(projectId);
  }

  private LlmProvider resolveAccountPreference(String userId) {
    if (userId == null || userId.isBlank()) return CANONICAL_DEFAULT;
    return appUserRepository
        .findById(userId)
        .map(user -> normalize(user.getPreferredAiProvider()))
        .orElse(CANONICAL_DEFAULT);
  }

  private static LlmProvider normalize(String value) {
    if (value == null || value.isBlank()) return CANONICAL_DEFAULT;
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if ("GOOGLE".equals(normalized)) normalized = "GEMINI";
    try {
      return LlmProvider.valueOf(normalized);
    } catch (IllegalArgumentException ignored) {
      return CANONICAL_DEFAULT;
    }
  }
}
