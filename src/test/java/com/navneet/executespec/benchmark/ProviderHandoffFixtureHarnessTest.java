package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.util.Set;

import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;

import org.junit.jupiter.api.Test;

class ProviderHandoffFixtureHarnessTest {

  @Test
  void explicitAlwaysHandoffUsesOnlyAllowListedProvidersAndStrictPasses() throws Exception {
    WorkerAgent worker = mock(WorkerAgent.class);
    when(worker.run(any()))
        .thenAnswer(
            invocation -> {
              WorkerTaskRequest request = invocation.getArgument(0);
              String target = request.context().workspaceRoot().resolve("calculator.py").toString();
              if (request.context().runId().endsWith("handoff")) {
                Files.writeString(
                    request.context().workspaceRoot().resolve("calculator.py"),
                    "def add(a, b):\n    return a + b\n");
              }
              return WorkerResult.builder()
                  .outcome(
                      request.context().runId().endsWith("handoff")
                          ? WorkerResult.Outcome.COMPLETED
                          : WorkerResult.Outcome.ERROR)
                  .iterations(2)
                  .toolCallsDispatched(1)
                  .completionSummary(target)
                  .toolCallLog(java.util.List.of())
                  .totalLatencyMs(1)
                  .build();
            });
    var auth = new java.util.ArrayList<String>();
    var root = Files.createTempDirectory("es-handoff-fixture-");
    try {
      var request =
          new ProviderHandoffFixtureHarness.Request(
              "stuck-handoff-v1",
              "v1",
              "deepseek",
              "deepseek-v4-pro-native",
              "r-primary",
              "openrouter",
              "deepseek/deepseek-v4-pro",
              "r-fallback",
              Set.of("deepseek", "openrouter"),
              ProviderHandoffFixtureHarness.HandoffTrigger.ALWAYS);
      var evidence =
          ProviderHandoffFixtureHarness.run(
              worker,
              request,
              "handoff-1",
              root,
              (reservation, provider, model, runId) -> auth.add(reservation + ":" + provider));

      assertThat(evidence.handoffTriggered()).isTrue();
      assertThat(evidence.primary().provider()).isEqualTo("deepseek");
      assertThat(evidence.fallback().provider()).isEqualTo("openrouter");
      assertThat(evidence.primary())
          .extracting(ProviderHandoffFixtureHarness.PassEvidence::evaluatorPassed)
          .isEqualTo(false);
      assertThat(evidence.fallback())
          .extracting(ProviderHandoffFixtureHarness.PassEvidence::evaluatorPassed)
          .isEqualTo(true);
      assertThat(auth).containsExactly("r-primary:deepseek", "r-fallback:openrouter");
      var requestCaptor = org.mockito.ArgumentCaptor.forClass(WorkerTaskRequest.class);
      org.mockito.Mockito.verify(worker, org.mockito.Mockito.times(2)).run(requestCaptor.capture());
      assertThat(requestCaptor.getAllValues())
          .allMatch(r -> Boolean.TRUE.equals(r.strictProvider()));
    } finally {
      try (var walk = Files.walk(root)) {
        walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }

  @Test
  void routeRejectsAnAllowListThatCouldPermitUncontrolledFailover() {
    WorkerAgent worker = mock(WorkerAgent.class);
    var root = java.nio.file.Path.of("/tmp/es-invalid-handoff-" + System.nanoTime());
    assertThatThrownBy(
            () ->
                ProviderHandoffFixtureHarness.run(
                    worker,
                    new ProviderHandoffFixtureHarness.Request(
                        "repository-localization-v1",
                        "v1",
                        "deepseek",
                        "m",
                        "r1",
                        "openrouter",
                        "m2",
                        "r2",
                        Set.of("deepseek", "openrouter", "anthropic"),
                        ProviderHandoffFixtureHarness.HandoffTrigger.ON_PROVIDER_ERROR),
                    "r",
                    root,
                    (a, b, c, d) -> {}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly primaryProvider and fallbackProvider");
  }

  @Test
  void routeRejectsGeminiEvenWhenTheOperatorAllowListsIt() {
    WorkerAgent worker = mock(WorkerAgent.class);
    var root = java.nio.file.Path.of("/tmp/es-gemini-handoff-" + System.nanoTime());
    assertThatThrownBy(
            () ->
                ProviderHandoffFixtureHarness.run(
                    worker,
                    new ProviderHandoffFixtureHarness.Request(
                        "repository-localization-v1",
                        "v1",
                        "gemini",
                        "gemini-3.5-flash",
                        "r1",
                        "anthropic",
                        "claude-sonnet-5",
                        "r2",
                        Set.of("gemini", "anthropic"),
                        ProviderHandoffFixtureHarness.HandoffTrigger.ON_PROVIDER_ERROR),
                    "r",
                    root,
                    (a, b, c, d) -> {}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not admissible for quality-evidence comparison");
  }

  @Test
  void routeRejectsRemovedOrUnknownProvidersAsFallbackToo() {
    WorkerAgent worker = mock(WorkerAgent.class);
    var root = java.nio.file.Path.of("/tmp/es-sarvam-handoff-" + System.nanoTime());
    assertThatThrownBy(
            () ->
                ProviderHandoffFixtureHarness.run(
                    worker,
                    new ProviderHandoffFixtureHarness.Request(
                        "repository-localization-v1",
                        "v1",
                        "deepseek",
                        "deepseek-v4-pro-native",
                        "r1",
                        "sarvam",
                        "sarvam-m",
                        "r2",
                        Set.of("deepseek", "sarvam"),
                        ProviderHandoffFixtureHarness.HandoffTrigger.ALWAYS),
                    "r",
                    root,
                    (a, b, c, d) -> {}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not admissible for quality-evidence comparison");
  }
}
