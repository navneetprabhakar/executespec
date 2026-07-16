package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.agent.RunKeyStore;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.smoketest.ExecutableVerification;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.testgen.model.TestExecutionSummary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

class GaneshWorkerContinuationTest {
  private final FeatureFlagService flags = mock(FeatureFlagService.class);
  private final RunKeyStore keys = mock(RunKeyStore.class);
  private final WorkspaceTestRunner verifier = mock(WorkspaceTestRunner.class);
  private final SseBroadcaster events = mock(SseBroadcaster.class);
  private final GaneshWorkerAttemptRepository attempts = mock(GaneshWorkerAttemptRepository.class);
  private final GaneshWorkerContinuation continuation =
      new GaneshWorkerContinuation(flags, keys, verifier, events, attempts, new ObjectMapper());

  @BeforeEach
  void configure() {
    ReflectionTestUtils.setField(continuation, "endpoint", "http://ganesh.invalid/v1");
    ReflectionTestUtils.setField(continuation, "platformToken", "platform-token");
    when(keys.get("12345678-run")).thenReturn(ResolvedKeys.SYSTEM_ONLY);
    when(attempts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void disabledPolicyPreservesFrontierPathWithoutE2OrPersistence() {
    when(flags.isEnabled(GaneshWorkerContinuation.FLAG)).thenReturn(false);
    List<String> routes = new ArrayList<>();

    var result =
        continuation.execute(
            request("app.py"),
            (provider, model, evidence) -> {
              routes.add(provider + "/" + model);
              return completed();
            });

    assertThat(result.ganeshRouted()).isFalse();
    assertThat(routes).containsExactly("anthropic/claude-sonnet-5");
    verifyNoInteractions(verifier, attempts, events);
  }

  @Test
  void failedGaneshAttemptRetriesFrontierExactlyOnceAndRetainsBothAttempts() {
    when(flags.isEnabled(GaneshWorkerContinuation.FLAG)).thenReturn(true);
    when(verifier.verify(any(), any())).thenReturn(failed(), passed());
    List<String> routes = new ArrayList<>();

    var result =
        continuation.execute(
            request("PATCH", "src/main.ts"),
            (provider, model, evidence) -> {
              routes.add(provider + "/" + model);
              return completed();
            });

    assertThat(routes).containsExactly("ganesh/ganesh-typescript", "anthropic/claude-sonnet-5");
    assertThat(result.frontierRetried()).isTrue();
    assertThat(result.accepted()).isTrue();
    verify(attempts, times(2)).saveAndFlush(any());
    verify(verifier, times(2)).verify("12345678-run", Path.of("/tmp/work"));
  }

  @Test
  void confirmationGatedGaneshFailureStopsBeforeFrontierRetryAndRetainsFirstAttempt() {
    when(flags.isEnabled(GaneshWorkerContinuation.FLAG)).thenReturn(true);
    when(verifier.verify(any(), any())).thenReturn(failed());
    List<String> routes = new ArrayList<>();

    var result =
        continuation.execute(
            request("PATCH", "src/main.ts", true),
            (provider, model, evidence) -> {
              routes.add(provider + "/" + model);
              return completed();
            });

    assertThat(routes).containsExactly("ganesh/ganesh-typescript");
    assertThat(result.frontierRetried()).isFalse();
    assertThat(result.accepted()).isFalse();
    verify(attempts).saveAndFlush(any());
    verify(verifier).verify("12345678-run", Path.of("/tmp/work"));
  }

  @Test
  void failedFrontierE2IsTerminalAndNeverRecurses() {
    when(flags.isEnabled(GaneshWorkerContinuation.FLAG)).thenReturn(true);
    when(verifier.verify(any(), any())).thenReturn(failed(), failed());
    var runner = mock(GaneshWorkerContinuation.AttemptRunner.class);
    when(runner.run(any(), any(), any())).thenReturn(completed());

    var result = continuation.execute(request("Main.java"), runner);

    assertThat(result.accepted()).isFalse();
    verify(runner, times(2)).run(any(), any(), any());
    verify(attempts, times(2)).saveAndFlush(any());
  }

  @Test
  void missingE2EvidenceRetriesThenFailsClosed() {
    when(flags.isEnabled(GaneshWorkerContinuation.FLAG)).thenReturn(true);
    ExecutableVerification missing =
        new ExecutableVerification(
            ExecutableVerification.Status.MISSING_EVIDENCE,
            ExecutableVerification.FailureStage.EVIDENCE,
            false,
            false,
            null,
            null,
            null,
            null,
            null,
            0,
            "",
            "No recognized test runner was found.");
    when(verifier.verify(any(), any())).thenReturn(missing, missing);
    var runner = mock(GaneshWorkerContinuation.AttemptRunner.class);
    when(runner.run(any(), any(), any())).thenReturn(completed());

    assertThat(continuation.execute(request("PATCH", "app.py"), runner).accepted()).isFalse();
    verify(runner, times(2)).run(any(), any(), any());
  }

  @Test
  void passedPatchE2CompletesOnGaneshWithoutFrontierRetry() {
    when(flags.isEnabled(GaneshWorkerContinuation.FLAG)).thenReturn(true);
    when(verifier.verify(any(), any())).thenReturn(passed());
    List<String> routes = new ArrayList<>();

    var result =
        continuation.execute(
            request("PATCH", "src/main.go"),
            (provider, model, evidence) -> {
              routes.add(provider + "/" + model);
              return completed();
            });

    assertThat(result.accepted()).isTrue();
    assertThat(result.frontierRetried()).isFalse();
    assertThat(routes).containsExactly("ganesh/ganesh-go");
    verify(attempts).saveAndFlush(any());
  }

  @Test
  void evidencePersistenceFailureStopsBeforeFrontierSpend() {
    when(flags.isEnabled(GaneshWorkerContinuation.FLAG)).thenReturn(true);
    when(verifier.verify(any(), any())).thenReturn(failed());
    when(attempts.saveAndFlush(any())).thenThrow(new IllegalStateException("db unavailable"));
    var runner = mock(GaneshWorkerContinuation.AttemptRunner.class);
    when(runner.run(any(), any(), any())).thenReturn(completed());

    assertThatThrownBy(() -> continuation.execute(request("app.py"), runner))
        .hasMessageContaining("retain Ganesh E2 evidence");
    verify(runner, times(1)).run(any(), any(), any());
  }

  @Test
  void ownKeysModeNeverRoutesToPlatformGanesh() {
    when(flags.isEnabled(GaneshWorkerContinuation.FLAG)).thenReturn(true);
    when(keys.get("12345678-run")).thenReturn(new ResolvedKeys(null, null, null, "own_keys", null));

    var result = continuation.execute(request("app.js"), (p, m, e) -> completed());

    assertThat(result.ganeshRouted()).isFalse();
    verifyNoInteractions(verifier, attempts);
  }

  @Test
  void absentEndpointOrTokenLeavesExactFrontierPathUnchanged() {
    when(flags.isEnabled(GaneshWorkerContinuation.FLAG)).thenReturn(true);
    ReflectionTestUtils.setField(continuation, "endpoint", "");
    List<String> routes = new ArrayList<>();

    var result =
        continuation.execute(
            request("app.py"),
            (p, m, e) -> {
              routes.add(p + "/" + m);
              return completed();
            });

    assertThat(result.ganeshRouted()).isFalse();
    assertThat(routes).containsExactly("anthropic/claude-sonnet-5");
    verifyNoInteractions(verifier, attempts);
  }

  @Test
  void unsupportedOrMultiFileTargetsLeaveExactFrontierPathUnchanged() {
    when(flags.isEnabled(GaneshWorkerContinuation.FLAG)).thenReturn(true);
    List<GaneshWorkerContinuation.Request> requests =
        List.of(
            new GaneshWorkerContinuation.Request(
                "12345678-run",
                "T1",
                Path.of("/tmp/work"),
                "BUILD",
                "anthropic",
                "claude-sonnet-5",
                List.of("README.md"),
                false),
            new GaneshWorkerContinuation.Request(
                "12345678-run",
                "T1",
                Path.of("/tmp/work"),
                "BUILD",
                "anthropic",
                "claude-sonnet-5",
                List.of("a.py", "b.py"),
                false));
    List<String> routes = new ArrayList<>();

    requests.forEach(
        request ->
            continuation.execute(
                request,
                (p, m, e) -> {
                  routes.add(p + "/" + m);
                  return completed();
                }));

    assertThat(routes).containsExactly("anthropic/claude-sonnet-5", "anthropic/claude-sonnet-5");
    verifyNoInteractions(verifier, attempts);
  }

  @Test
  void directGaneshRunIsRejectedBeforePlannerOrWorkerTransport() {
    var request =
        new GaneshWorkerContinuation.Request(
            "12345678-run",
            "T1",
            Path.of("/tmp/work"),
            "BUILD",
            "ganesh",
            "ganesh-python",
            List.of("app.py"),
            false);
    assertThatThrownBy(() -> continuation.execute(request, (p, m, e) -> completed()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("worker-only");
  }

  @ParameterizedTest
  @CsvSource({
    "x.py,ganesh-python",
    "x.go,ganesh-go",
    "x.ts,ganesh-typescript",
    "x.js,ganesh-javascript",
    "x.java,ganesh-java"
  })
  void exactLanguageModelsAreStable(String file, String model) {
    assertThat(GaneshWorkerContinuation.modelFor(file)).isEqualTo(model);
  }

  private static GaneshWorkerContinuation.Request request(String file) {
    return request("BUILD", file);
  }

  private static GaneshWorkerContinuation.Request request(String mode, String file) {
    return request(mode, file, false);
  }

  private static GaneshWorkerContinuation.Request request(
      String mode, String file, boolean requireConfirmation) {
    return new GaneshWorkerContinuation.Request(
        "12345678-run",
        "T1",
        Path.of("/tmp/work"),
        mode,
        "anthropic",
        "claude-sonnet-5",
        List.of(file),
        requireConfirmation);
  }

  private static WorkerResult completed() {
    return WorkerResult.builder()
        .outcome(WorkerResult.Outcome.COMPLETED)
        .toolCallLog(List.of())
        .build();
  }

  private static ExecutableVerification passed() {
    var summary = TestExecutionSummary.builder().totalTests(2).passed(2).build();
    return new ExecutableVerification(
        ExecutableVerification.Status.PASSED,
        ExecutableVerification.FailureStage.NONE,
        true,
        true,
        summary,
        "PYTEST",
        "resolver",
        "pytest -q",
        0,
        10,
        "2 passed",
        "2/2 tests passed");
  }

  private static ExecutableVerification failed() {
    var summary = TestExecutionSummary.builder().totalTests(2).passed(1).failed(1).build();
    return new ExecutableVerification(
        ExecutableVerification.Status.ASSERTION_FAILED,
        ExecutableVerification.FailureStage.TEST_ASSERTION,
        true,
        false,
        summary,
        "PYTEST",
        "resolver",
        "pytest -q",
        1,
        10,
        "1 failed, 1 passed",
        "1/2 tests passed");
  }
}
