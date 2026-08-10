package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class WorkspaceGenerationQualificationEvidenceControllerTest {

  @Test
  void operatorEvidenceResponseIsNeverCacheable() {
    WorkspaceGenerationQualificationEvidenceService service =
        mock(WorkspaceGenerationQualificationEvidenceService.class);
    UUID attemptId = UUID.randomUUID();
    var payload =
        new WorkspaceGenerationQualificationEvidenceService.QualificationEvidence(
            attemptId,
            "run-1",
            "T1",
            WorkspaceGenerationState.ROLLED_BACK,
            "a".repeat(64),
            null,
            "b".repeat(64),
            "{\"files\":[]}",
            "TASK_OUTCOME_UNACCEPTED",
            "failed deterministic task smoke",
            "ANTHROPIC",
            "claude-sonnet-5",
            "ANTHROPIC",
            "claude-sonnet-5",
            9,
            11,
            9L,
            null,
            null,
            null,
            null);
    when(service.read(attemptId)).thenReturn(payload);

    var response = new WorkspaceGenerationQualificationEvidenceController(service).read(attemptId);

    assertThat(response.getBody()).isSameAs(payload);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
  }
}
