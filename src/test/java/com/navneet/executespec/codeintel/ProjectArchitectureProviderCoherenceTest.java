package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.router.LlmProvider;

import org.junit.jupiter.api.Test;

/**
 * Pins the provider handoff into the final architecture workspace/brief stage. A confirmed action
 * must not re-resolve the project default after its repo and combined architecture calls.
 */
class ProjectArchitectureProviderCoherenceTest {

  @Test
  void workspacePersistenceReceivesTheAlreadyResolvedProvider() {
    ProjectBriefService brief = mock(ProjectBriefService.class);
    when(brief.summarise("project-1", "# Architecture", LlmProvider.DEEPSEEK, "actor-1", null))
        .thenReturn("DeepSeek summary");

    assertThat(
            ProjectArchitectureService.summariseBrief(
                brief, "project-1", "# Architecture", LlmProvider.DEEPSEEK, "actor-1", null))
        .isEqualTo("DeepSeek summary");
    verify(brief).summarise("project-1", "# Architecture", LlmProvider.DEEPSEEK, "actor-1", null);
  }
}
