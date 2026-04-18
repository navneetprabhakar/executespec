package com.navneet.executespec.v2.batch;

import com.navneet.executespec.router.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2BatchClientRouterTest {

    private static V2BatchClient stub(LlmProvider provider) {
        return new V2BatchClient() {
            @Override public V2BatchJob submit(String runId, List<V2BatchRequest> requests) {
                throw new UnsupportedOperationException();
            }
            @Override public V2BatchJob.V2BatchJobState poll(V2BatchJob job) {
                throw new UnsupportedOperationException();
            }
            @Override public List<V2BatchTaskResult> fetchResults(V2BatchJob job) {
                throw new UnsupportedOperationException();
            }
            @Override public void cancel(V2BatchJob job) {}
            @Override public LlmProvider provider() { return provider; }
        };
    }

    @Test
    void emptyRouterThrowsOnForProvider() {
        V2BatchClientRouter router = new V2BatchClientRouter(List.of());
        assertThatThrownBy(() -> router.forProvider(LlmProvider.ANTHROPIC))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("ANTHROPIC");
        assertThat(router.hasClient(LlmProvider.ANTHROPIC)).isFalse();
    }

    @Test
    void routesToRegisteredClient() {
        V2BatchClient anthropic = stub(LlmProvider.ANTHROPIC);
        V2BatchClient openai = stub(LlmProvider.OPENAI);
        V2BatchClientRouter router = new V2BatchClientRouter(List.of(anthropic, openai));
        assertThat(router.forProvider(LlmProvider.ANTHROPIC)).isSameAs(anthropic);
        assertThat(router.forProvider(LlmProvider.OPENAI)).isSameAs(openai);
        assertThat(router.hasClient(LlmProvider.GOOGLE)).isFalse();
    }

    @Test
    void duplicateProviderKeepsLatest() {
        V2BatchClient first = stub(LlmProvider.ANTHROPIC);
        V2BatchClient second = stub(LlmProvider.ANTHROPIC);
        V2BatchClientRouter router = new V2BatchClientRouter(List.of(first, second));
        assertThat(router.forProvider(LlmProvider.ANTHROPIC)).isSameAs(second);
    }
}
