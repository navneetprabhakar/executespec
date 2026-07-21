package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RR-1529: the documented cosine-distance ceiling was enforced for the document corpus but not for
 * the code corpus, so an off-topic query still produced k confident-looking snippets.
 */
class CodeChunkStoreThresholdTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final CodeChunkStore store = new CodeChunkStore(jdbc);

  @Test
  void chunksBeyondTheConfiguredCeilingAreRejectedBeforeTheyReachTheModel() {
    ReflectionTestUtils.setField(store, "maxCosineDistance", 0.55d);
    stubRows(
        List.of(
            chunk("src/Near.java", 0.20),
            chunk("src/Edge.java", 0.55),
            chunk("src/Unrelated.java", 0.91),
            chunk("src/Opposite.java", 1.40)));

    var hits = store.nearest("p1", "r1", new float[] {0.1f}, 8);

    assertThat(hits)
        .extracting(CodeChunkStore.RetrievedCodeChunk::filePath)
        .containsExactly("src/Near.java", "src/Edge.java");
    // The reported relevance complement can never be negative for an admitted hit.
    assertThat(hits).allSatisfy(hit -> assertThat(1.0 - hit.distance()).isBetween(0.0, 1.0));
  }

  @Test
  void anEntirelyOffTopicQueryReturnsNoHitsInsteadOfFabricatedRelevance() {
    ReflectionTestUtils.setField(store, "maxCosineDistance", 0.55d);
    stubRows(List.of(chunk("src/Unrelated.java", 0.98)));

    assertThat(store.nearest("p1", null, new float[] {0.1f}, 8)).isEmpty();
  }

  @SuppressWarnings("unchecked")
  private void stubRows(List<CodeChunkStore.RetrievedCodeChunk> rows) {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(rows);
  }

  private static CodeChunkStore.RetrievedCodeChunk chunk(String path, double distance) {
    return new CodeChunkStore.RetrievedCodeChunk("r1", "acme/app", path, 0, "code", 10, distance);
  }
}
