package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class RetrievalServiceTest {

  private final EmbeddingService embeddings = mock(EmbeddingService.class);
  private final ChunkStore chunks = mock(ChunkStore.class);
  private final RetrievalService service = new RetrievalService(embeddings, chunks);

  @Test
  void unsupportedProviderUsesDurableLexicalSearchWithoutQueryEmbedding() {
    when(embeddings.availability("p1")).thenReturn(lexicalAvailability());
    when(chunks.lexical("p1", "payment retry", 3))
        .thenReturn(List.of(hit("payments.md", "Retry payments with bounded backoff.")));

    var result = service.retrieveWithReadiness("p1", "payment retry", 3, 100);

    assertThat(result.mode()).isEqualTo("LEXICAL");
    assertThat(result.reason()).isEqualTo("SELECTED_PROVIDER_HAS_NO_COMPATIBLE_EMBEDDING_MODEL");
    assertThat(result.content()).contains("LEXICAL FALLBACK").contains("bounded backoff");
    verify(embeddings, never())
        .embedQueryForProject(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    verify(chunks, never()).nearest(anyString(), org.mockito.ArgumentMatchers.any(), anyInt());
  }

  @Test
  void transientSemanticQueryFailureDegradesHonestlyToLexical() {
    when(embeddings.availability("p1")).thenReturn(semanticAvailability());
    when(embeddings.embedQueryForProject(
            eq("p1"),
            eq("auth"),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.isNull()))
        .thenThrow(new IllegalStateException("provider unavailable"));
    when(chunks.lexical("p1", "auth", 3))
        .thenReturn(List.of(hit("security.md", "JWT authentication policy")));

    var result = service.retrieveWithReadiness("p1", "auth", 3, 100);

    assertThat(result.mode()).isEqualTo("LEXICAL");
    assertThat(result.reason()).isEqualTo("SEMANTIC_RESULTS_UNAVAILABLE");
    assertThat(result.content()).contains("JWT authentication policy");
  }

  @Test
  void semanticReadyUsesGovernedQueryVectorAndDoesNotNeedLexicalFallback() {
    when(embeddings.availability("p1")).thenReturn(semanticAvailability());
    float[] vector = new float[] {0.1f};
    when(embeddings.embedQueryForProject(
            eq("p1"),
            eq("architecture"),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.isNull()))
        .thenReturn(vector);
    when(chunks.nearest("p1", vector, 3))
        .thenReturn(List.of(hit("architecture.md", "Service boundaries")));

    var result = service.retrieveWithReadiness("p1", "architecture", 3, 100);

    assertThat(result.mode()).isEqualTo("SEMANTIC");
    assertThat(result.content()).contains("## REFERENCE DOCS").contains("Service boundaries");
    verify(chunks, never()).lexical(anyString(), anyString(), anyInt());
  }

  private static ChunkStore.RetrievedChunk hit(String filename, String content) {
    return new ChunkStore.RetrievedChunk("d1", filename, 0, content, 8, 0.1);
  }

  private static EmbeddingPolicyResolver.Availability lexicalAvailability() {
    return new EmbeddingPolicyResolver.Availability(
        EmbeddingPolicyResolver.Mode.LEXICAL,
        false,
        true,
        "deepseek",
        null,
        "SELECTED_PROVIDER_HAS_NO_COMPATIBLE_EMBEDDING_MODEL");
  }

  private static EmbeddingPolicyResolver.Availability semanticAvailability() {
    return new EmbeddingPolicyResolver.Availability(
        EmbeddingPolicyResolver.Mode.SEMANTIC,
        true,
        true,
        "openai",
        EmbeddingService.DEFAULT_MODEL,
        "AVAILABLE");
  }
}
