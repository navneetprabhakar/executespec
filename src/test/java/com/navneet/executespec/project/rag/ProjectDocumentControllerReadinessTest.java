package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.rag.entity.ProjectDocument;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.junit.jupiter.api.Test;

class ProjectDocumentControllerReadinessTest {

  private final ProjectDocumentService service = mock(ProjectDocumentService.class);
  private final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
  private final ProjectDocumentController controller =
      new ProjectDocumentController(service, access);

  @Test
  void historicalSemanticRowIsPresentedAsLexicalWhenCurrentProjectRouteCannotEmbed() {
    ProjectDocument document =
        ProjectDocument.builder()
            .id("d1")
            .projectId("p1")
            .filename("architecture.md")
            .status(ProjectDocument.Status.READY)
            .retrievalMode("SEMANTIC")
            .embeddingProvider("openai")
            .embeddingModel(EmbeddingService.DEFAULT_MODEL)
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
    when(service.list("p1")).thenReturn(List.of(document));
    when(service.embeddingAvailability("p1"))
        .thenReturn(
            new EmbeddingPolicyResolver.Availability(
                EmbeddingPolicyResolver.Mode.LEXICAL,
                false,
                true,
                "deepseek",
                null,
                "SELECTED_PROVIDER_HAS_NO_COMPATIBLE_EMBEDDING_MODEL"));

    var body = controller.list(principal(), "p1").getBody();

    assertThat(body).hasSize(1);
    var view = body.get(0);
    assertThat(view.status()).isEqualTo("LEXICAL_READY");
    assertThat(view.retrievalMode()).isEqualTo("LEXICAL");
    assertThat(view.semanticSearchAvailable()).isFalse();
    assertThat(view.lexicalSearchAvailable()).isTrue();
    assertThat(view.embeddingProvider()).isNull();
    assertThat(view.embeddingModel()).isNull();
    assertThat(view.readinessReason())
        .isEqualTo("SELECTED_PROVIDER_HAS_NO_COMPATIBLE_EMBEDDING_MODEL");
  }

  /**
   * RR-1532: a lexical row must not be annotated with the route reason asserting that semantic
   * embedding IS available — that hides a real degradation behind a success string.
   */
  @Test
  void lexicalRowOnAHealthyRouteReportsTheDegradationNotTheAvailabilityReason() {
    ProjectDocument document =
        ProjectDocument.builder()
            .id("d2")
            .projectId("p1")
            .filename("runbook.md")
            .status(ProjectDocument.Status.LEXICAL_READY)
            .retrievalMode("LEXICAL")
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
    when(service.list("p1")).thenReturn(List.of(document));
    when(service.embeddingAvailability("p1"))
        .thenReturn(
            new EmbeddingPolicyResolver.Availability(
                EmbeddingPolicyResolver.Mode.SEMANTIC,
                true,
                true,
                "openai",
                EmbeddingService.DEFAULT_MODEL,
                "GOVERNED_OPENAI_EMBEDDING_AVAILABLE"));

    var view = controller.list(principal(), "p1").getBody().get(0);

    assertThat(view.semanticSearchAvailable()).isFalse();
    assertThat(view.readinessReason())
        .isEqualTo("DOCUMENT_INDEXED_LEXICALLY_SEMANTIC_REINDEX_PENDING");
  }

  private static AppUserDetails principal() {
    return new AppUserDetails(
        AppUser.builder()
            .id("u1")
            .orgId("o1")
            .email("u@example.com")
            .globalRole("USER")
            .status("ACTIVE")
            .build(),
        null);
  }
}
