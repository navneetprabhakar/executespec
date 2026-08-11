package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class WorkspaceOriginResolverTest {

  @Test
  void submissionFactsDetermineOriginWithoutFilesystemInspection() {
    assertThat(WorkspaceOriginResolver.submitted(null, null, false, null, List.of()))
        .isEqualTo(WorkspaceOrigin.GREENFIELD);
    assertThat(WorkspaceOriginResolver.submitted(null, null, false, "EXISTING", List.of()))
        .isEqualTo(WorkspaceOrigin.BROWNFIELD);
    assertThat(WorkspaceOriginResolver.submitted(null, "prior-run", false, null, List.of()))
        .isEqualTo(WorkspaceOrigin.ITERATION);
    assertThat(
            WorkspaceOriginResolver.submitted(
                "project", null, false, null, List.of("repo-a", "repo-b")))
        .isEqualTo(WorkspaceOrigin.MULTI_REPOSITORY);
  }

  @Test
  void historicalFallbackIsExplicitAndPersistedValueWins() {
    assertThat(
            WorkspaceOriginResolver.persistedOrHistorical(
                "GREENFIELD", "project", null, false, "EXISTING", List.of("repo")))
        .isEqualTo(WorkspaceOrigin.GREENFIELD);
    assertThat(
            WorkspaceOriginResolver.persistedOrHistorical(
                null, null, null, false, "EXISTING", List.of()))
        .isEqualTo(WorkspaceOrigin.BROWNFIELD);
  }
}
