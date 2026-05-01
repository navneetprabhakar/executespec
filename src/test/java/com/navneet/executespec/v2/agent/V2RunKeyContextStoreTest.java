package com.navneet.executespec.v2.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.ResolvedKeys;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the per-run BYOK key cache that backs the V2 router fix
 * (audit-output/BYOK_DEEPDIVE.md §5.2). The store is the single point where {@link
 * com.navneet.executespec.agent.ApiKeyResolver}'s output is captured once per run and read back by
 * every subsequent LLM dispatch.
 */
class V2RunKeyContextStoreTest {

  private final ApiKeyResolver resolver = mock(ApiKeyResolver.class);
  private final V2RunKeyContextStore store = new V2RunKeyContextStore(resolver);

  @Test
  void resolveAndPut_userWithAnthropicKey_returnsKeyAndUserSource() {
    when(resolver.resolve("u1"))
        .thenReturn(new ResolvedKeys("sk-ant-customer", null, null, "HYBRID", null));

    V2RunKeyContext ctx = store.resolveAndPut("run1", "u1");

    assertThat(ctx.anthropicKey()).isEqualTo("sk-ant-customer");
    assertThat(ctx.openAiKey()).isNull();
    assertThat(ctx.geminiKey()).isNull();
    assertThat(ctx.anthropicSource()).isEqualTo(V2RunKeyContext.Source.USER);
    assertThat(ctx.openAiSource()).isEqualTo(V2RunKeyContext.Source.PLATFORM);
    assertThat(ctx.geminiSource()).isEqualTo(V2RunKeyContext.Source.PLATFORM);
  }

  @Test
  void keyFor_returnsRightProviderKey() {
    when(resolver.resolve("u1"))
        .thenReturn(new ResolvedKeys("sk-ant", "sk-oai", "AIza-gem", "HYBRID", null));
    V2RunKeyContext ctx = store.resolveAndPut("run-multi", "u1");

    assertThat(ctx.keyFor("anthropic")).isEqualTo("sk-ant");
    assertThat(ctx.keyFor("openai")).isEqualTo("sk-oai");
    assertThat(ctx.keyFor("gemini")).isEqualTo("AIza-gem");
    assertThat(ctx.keyFor("UNKNOWN")).isNull();
    assertThat(ctx.keyFor(null)).isNull();
  }

  @Test
  void resolveAndPut_noKeysConfigured_allSourcesPlatform() {
    when(resolver.resolve("u-empty"))
        .thenReturn(new ResolvedKeys(null, null, null, "HYBRID", null));

    V2RunKeyContext ctx = store.resolveAndPut("run-empty", "u-empty");

    assertThat(ctx.anthropicSource()).isEqualTo(V2RunKeyContext.Source.PLATFORM);
    assertThat(ctx.openAiSource()).isEqualTo(V2RunKeyContext.Source.PLATFORM);
    assertThat(ctx.geminiSource()).isEqualTo(V2RunKeyContext.Source.PLATFORM);
    assertThat(ctx.dominantSource()).isEqualTo(V2RunKeyContext.Source.PLATFORM);
  }

  @Test
  void dominantSource_picksFirstNonPlatform() {
    // OpenAI is the only configured provider — anthropic + gemini fall
    // through. dominantSource should report USER (not PLATFORM).
    when(resolver.resolve("u1")).thenReturn(new ResolvedKeys(null, "sk-oai", null, "HYBRID", null));

    V2RunKeyContext ctx = store.resolveAndPut("run1", "u1");
    assertThat(ctx.dominantSource()).isEqualTo(V2RunKeyContext.Source.USER);
  }

  @Test
  void getReturnsCachedEntry() {
    when(resolver.resolve("u1")).thenReturn(new ResolvedKeys("sk-ant", null, null, "HYBRID", null));
    store.resolveAndPut("run-cache", "u1");

    V2RunKeyContext fetched = store.get("run-cache");
    assertThat(fetched).isNotNull();
    assertThat(fetched.keyFor("anthropic")).isEqualTo("sk-ant");
  }

  @Test
  void getReturnsNullForUnknownRun() {
    assertThat(store.get("never-stored")).isNull();
    assertThat(store.get(null)).isNull();
  }

  @Test
  void evictDropsEntry() {
    when(resolver.resolve("u1")).thenReturn(new ResolvedKeys("sk-ant", null, null, "HYBRID", null));
    store.resolveAndPut("run-evict", "u1");
    assertThat(store.get("run-evict")).isNotNull();

    store.evict("run-evict");

    assertThat(store.get("run-evict")).isNull();
  }

  @Test
  void resolveAndPutRejectsBlankRunId() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> store.resolveAndPut(null, "u1"));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> store.resolveAndPut("", "u1"));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> store.resolveAndPut("   ", "u1"));
  }

  @Test
  void resolveAndPutRecallReplacesEntry() {
    // Idempotent re-call replaces the entry — last write wins. Useful
    // for runs that change ownership mid-pipeline (rare).
    when(resolver.resolve("u-old"))
        .thenReturn(new ResolvedKeys("sk-old", null, null, "HYBRID", null));
    when(resolver.resolve("u-new"))
        .thenReturn(new ResolvedKeys("sk-new", null, null, "HYBRID", null));

    store.resolveAndPut("same-run", "u-old");
    store.resolveAndPut("same-run", "u-new");

    assertThat(store.get("same-run").keyFor("anthropic")).isEqualTo("sk-new");
  }
}
