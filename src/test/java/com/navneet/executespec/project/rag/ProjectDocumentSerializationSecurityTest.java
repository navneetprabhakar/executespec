package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.project.rag.entity.ProjectDocument;

import org.junit.jupiter.api.Test;

class ProjectDocumentSerializationSecurityTest {

  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @Test
  void publicJsonExposesTypedStatusButNotStoragePathActorOrParserDetails() throws Exception {
    ProjectDocument document =
        ProjectDocument.builder()
            .id("d1")
            .projectId("p1")
            .pathRelative("docs/.uploads/secret-internal-path.bin")
            .filename("design.pdf")
            .mimeType("application/pdf")
            .uploadedBy("private-user-id")
            .status(ProjectDocument.Status.FAILED)
            .errorCode("CORRUPT_DOCUMENT")
            .failureReason("/tmp/private/parser stack and secret details")
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();

    String serialized = json.writeValueAsString(document);

    assertThat(serialized)
        .contains("CORRUPT_DOCUMENT")
        .contains("design.pdf")
        .doesNotContain("pathRelative")
        .doesNotContain("private-user-id")
        .doesNotContain("parser stack")
        .doesNotContain("secret-internal-path");
  }
}
