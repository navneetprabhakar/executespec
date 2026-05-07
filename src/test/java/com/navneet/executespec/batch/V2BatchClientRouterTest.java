package com.navneet.executespec.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.NoSuchElementException;

import com.navneet.executespec.router.LlmProvider;

import org.junit.jupiter.api.Test;

class V2BatchClientRouterTest {

  private static BatchClient stub(LlmProvider provider) {
    return new BatchClient() {
      @Override
      public BatchJob submit(String runId, List<BatchRequest> requests) {
        throw new UnsupportedOperationException();
      }

      @Override
      public BatchJob.V2BatchJobState poll(BatchJob job) {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<BatchTaskResult> fetchResults(BatchJob job) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void cancel(BatchJob job) {}

      @Override
      public LlmProvider provider() {
        return provider;
      }
    };
  }

  @Test
  void emptyRouterThrowsOnForProvider() {
    BatchClientRouter router = new BatchClientRouter(List.of());
    assertThatThrownBy(() -> router.forProvider(LlmProvider.ANTHROPIC))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining("ANTHROPIC");
    assertThat(router.hasClient(LlmProvider.ANTHROPIC)).isFalse();
  }

  @Test
  void routesToRegisteredClient() {
    BatchClient anthropic = stub(LlmProvider.ANTHROPIC);
    BatchClient openai = stub(LlmProvider.OPENAI);
    BatchClientRouter router = new BatchClientRouter(List.of(anthropic, openai));
    assertThat(router.forProvider(LlmProvider.ANTHROPIC)).isSameAs(anthropic);
    assertThat(router.forProvider(LlmProvider.OPENAI)).isSameAs(openai);
    assertThat(router.hasClient(LlmProvider.GEMINI)).isFalse();
  }

  @Test
  void duplicateProviderKeepsLatest() {
    BatchClient first = stub(LlmProvider.ANTHROPIC);
    BatchClient second = stub(LlmProvider.ANTHROPIC);
    BatchClientRouter router = new BatchClientRouter(List.of(first, second));
    assertThat(router.forProvider(LlmProvider.ANTHROPIC)).isSameAs(second);
  }
}
