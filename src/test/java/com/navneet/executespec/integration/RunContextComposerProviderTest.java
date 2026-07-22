package com.navneet.executespec.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelRegistry;

import org.junit.jupiter.api.Test;

class RunContextComposerProviderTest {

  @Test
  void normalizeProviderAllowsExplicitGeminiProvider() {
    RunContextComposer composer = composer(null);

    assertEquals("gemini", composer.normalizeProvider("gemini", null, "run-1"));
  }

  @Test
  void normalizeProviderAllowsGeminiProjectDefault() {
    ProjectRepository projectRepo = mock(ProjectRepository.class);
    Project project =
        Project.builder()
            .id("p1")
            .orgId("org-1")
            .name("Project")
            .createdBy("owner")
            .aiProvider(LlmProvider.GEMINI)
            .build();
    when(projectRepo.findById("p1")).thenReturn(Optional.of(project));
    RunContextComposer composer = composer(projectRepo);

    assertEquals("gemini", composer.normalizeProvider(null, "p1", "run-1"));
  }

  @Test
  void normalizeProviderAllowsEnabledProvider() {
    RunContextComposer composer = composer(null);

    assertEquals("anthropic", composer.normalizeProvider("anthropic", null, "run-1"));
  }

  @Test
  void defaultModelForFailsClosedWhenProviderHasNoWorkhorseModel() {
    // RR-467 — a missing model for the selected provider must NOT silently cross to an Anthropic
    // Haiku slug (which broke route pins / key ownership / billing evidence). It fails the run.
    ModelRegistry registry = mock(ModelRegistry.class);
    when(registry.workhouseModelFor("gemini")).thenReturn(null);
    RunContextComposer composer =
        new RunContextComposer(
            new ObjectMapper(), null, null, mock(ProjectRepository.class), registry, null);

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> composer.defaultModelFor("gemini"));
    assertTrue(ex.getMessage().contains("gemini"));
    assertFalse(ex.getMessage().contains("claude-haiku"));
  }

  private static RunContextComposer composer(ProjectRepository projectRepo) {
    return new RunContextComposer(
        new ObjectMapper(),
        null,
        null,
        projectRepo == null ? mock(ProjectRepository.class) : projectRepo,
        null,
        null);
  }
}
