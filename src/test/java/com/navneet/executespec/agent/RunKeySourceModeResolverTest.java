package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * BYOK deep-dive §7 #2 — the run→OWN_KEYS mode must be answerable from durable state (org policy
 * via the run's persisted user), independent of the best-effort key caches, and must fail safe
 * (false) when the run cannot be attributed.
 */
class RunKeySourceModeResolverTest {

  private PipelineRunRepository runRepo;
  private ApiKeyResolver apiKeyResolver;
  private RunKeySourceModeResolver resolver;

  @BeforeEach
  void setUp() {
    runRepo = mock(PipelineRunRepository.class);
    apiKeyResolver = mock(ApiKeyResolver.class);
    resolver = new RunKeySourceModeResolver(runRepo, apiKeyResolver);
  }

  private static PipelineRun runFor(String runId, String userId) {
    PipelineRun r = new PipelineRun();
    r.setRunId(runId);
    r.setCreatedByUserId(userId);
    return r;
  }

  @Test
  void trueWhenOrgIsOwnKeys() {
    when(runRepo.findById("run-1")).thenReturn(Optional.of(runFor("run-1", "user-1")));
    when(apiKeyResolver.isOwnKeysMode("user-1")).thenReturn(true);

    assertThat(resolver.isOwnKeysRun("run-1")).isTrue();
  }

  @Test
  void falseWhenOrgIsHybridOrSystem() {
    when(runRepo.findById("run-2")).thenReturn(Optional.of(runFor("run-2", "user-2")));
    when(apiKeyResolver.isOwnKeysMode("user-2")).thenReturn(false);

    assertThat(resolver.isOwnKeysRun("run-2")).isFalse();
  }

  @Test
  void failsSafeFalseForUnknownOrUnattributableRun() {
    when(runRepo.findById("missing")).thenReturn(Optional.empty());
    assertThat(resolver.isOwnKeysRun("missing")).isFalse();
    assertThat(resolver.isOwnKeysRun(null)).isFalse();
    assertThat(resolver.isOwnKeysRun("")).isFalse();

    when(runRepo.findById("no-user")).thenReturn(Optional.of(runFor("no-user", null)));
    assertThat(resolver.isOwnKeysRun("no-user")).isFalse();
  }

  @Test
  void cachesTheModePerRun() {
    when(runRepo.findById("run-3")).thenReturn(Optional.of(runFor("run-3", "user-3")));
    when(apiKeyResolver.isOwnKeysMode("user-3")).thenReturn(true);

    assertThat(resolver.isOwnKeysRun("run-3")).isTrue();
    assertThat(resolver.isOwnKeysRun("run-3")).isTrue();

    // Second call served from cache — the durable lookups run once.
    verify(runRepo, times(1)).findById("run-3");
    verify(apiKeyResolver, times(1)).isOwnKeysMode("user-3");

    resolver.evict("run-3");
    assertThat(resolver.isOwnKeysRun("run-3")).isTrue();
    verify(runRepo, times(2)).findById("run-3");
  }
}
