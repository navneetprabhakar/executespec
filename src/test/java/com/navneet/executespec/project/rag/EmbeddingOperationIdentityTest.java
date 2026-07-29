package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class EmbeddingOperationIdentityTest {

  private static final UUID DOCUMENT_ID = UUID.fromString("4dfbcf24-3b91-4e8f-bda1-f31585082968");
  private static final UUID REPO_ID = UUID.fromString("5dfbcf24-3b91-4e8f-bda1-f31585082968");

  @Test
  void documentIdentityFitsDatabaseColumnsAndSeparatesDocumentSourceAndAttempt() {
    String identity =
        EmbeddingOperationIdentity.document(DOCUMENT_ID.toString(), "a".repeat(64), 1);

    assertThat(identity).startsWith("project-document:").hasSize(49);
    assertThat(identity.length()).isLessThanOrEqualTo(96);
    assertThat(EmbeddingOperationIdentity.document(DOCUMENT_ID.toString(), "a".repeat(64), 1))
        .isEqualTo(identity);
    assertThat(EmbeddingOperationIdentity.document(DOCUMENT_ID.toString(), "b".repeat(64), 1))
        .isNotEqualTo(identity);
    assertThat(EmbeddingOperationIdentity.document(DOCUMENT_ID.toString(), "a".repeat(64), 2))
        .isNotEqualTo(identity);
    assertThat(
            EmbeddingOperationIdentity.document(
                UUID.fromString("6dfbcf24-3b91-4e8f-bda1-f31585082968").toString(),
                "a".repeat(64),
                1))
        .isNotEqualTo(identity);
  }

  @Test
  void codeIndexIdentityAlsoFitsForASixtyFourCharacterRevision() {
    String identity = EmbeddingOperationIdentity.codeIndex(REPO_ID, "a".repeat(64));

    assertThat(identity).startsWith("project-code:").hasSize(45);
    assertThat(identity.length()).isLessThanOrEqualTo(96);
    assertThat(EmbeddingOperationIdentity.codeIndex(REPO_ID, "b".repeat(64)))
        .isNotEqualTo(identity);
  }

  @Test
  void embeddingSourceNormalizerNeverReturnsMoreThanDatabaseLimit() {
    String longSource = "document:" + "x".repeat(180);

    String normalized = EmbeddingService.requireSource(longSource);

    assertThat(normalized).hasSize(95).startsWith("document:");
    assertThat(EmbeddingService.requireSource(longSource)).isEqualTo(normalized);
    assertThat(EmbeddingService.requireSource(longSource + "changed")).isNotEqualTo(normalized);
  }
}
