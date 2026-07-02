package com.navneet.executespec.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.router.LlmProvider;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RunContextComposerProviderTest {

  @Test
  void normalizeProviderRejectsExplicitDisabledProvider() {
    RunContextComposer composer = composer(null);

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> composer.normalizeProvider("gemini", null, "run-1"));

    assertEquals(400, ex.getStatusCode().value());
  }

  @Test
  void normalizeProviderRejectsDisabledProjectDefault() {
    ProjectRepository projectRepo = mock(ProjectRepository.class);
    Project project =
        Project.builder()
            .id("p1")
            .orgId("org-1")
            .name("Project")
            .createdBy("owner")
            .aiProvider(LlmProvider.SARVAM)
            .build();
    when(projectRepo.findById("p1")).thenReturn(Optional.of(project));
    RunContextComposer composer = composer(projectRepo);

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> composer.normalizeProvider(null, "p1", "run-1"));

    assertEquals(400, ex.getStatusCode().value());
  }

  @Test
  void normalizeProviderAllowsEnabledProvider() {
    RunContextComposer composer = composer(null);

    assertEquals("anthropic", composer.normalizeProvider("anthropic", null, "run-1"));
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
