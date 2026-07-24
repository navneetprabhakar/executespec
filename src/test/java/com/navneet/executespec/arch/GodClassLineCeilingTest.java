package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Enforces the enterprise-hardening programme rule that the known god classes must not grow.
 *
 * <p>The plan states: "Do not add net policy/lifecycle responsibility to RunOrchestrator,
 * SequentialExecutor, WorkerAgent, agentPanels.tsx or API core.ts." That rule was advisory and was
 * violated by +461 net lines across the branch. This test makes it enforced.
 *
 * <p>Each ceiling below is the measured line count at the moment the ratchet was installed, with no
 * headroom. Slack is permission to grow, so there is none. The ceilings may only ever move DOWN.
 *
 * <p>Two of the five files are frontend TypeScript, so measurement reads bytes from disk rather
 * than inspecting bytecode. Counting is deterministic: it counts {@code \n} bytes and adds one for
 * a final unterminated line. It does not consult git, the working-tree state, or file ordering.
 */
class GodClassLineCeilingTest {

  /** Files a shrink must reclaim rather than bank as invisible headroom. */
  private static final int MAX_UNCLAIMED_HEADROOM_LINES = 50;

  /** The general plan rule: no NEW production class over roughly this many lines. */
  private static final int NEW_CLASS_LINE_LIMIT = 600;

  private static final Path JAVA_SOURCE_ROOT = Path.of("src/main/java");

  /**
   * Ratchet ceilings, set to the exact measured line count on 2026-07-19. Lower these whenever the
   * file shrinks; never raise them.
   */
  private static final Map<String, Integer> CEILINGS = ceilings();

  private static Map<String, Integer> ceilings() {
    Map<String, Integer> ceilings = new LinkedHashMap<>();
    ceilings.put("src/main/java/com/navneet/executespec/integration/RunOrchestrator.java", 4322);
    // Lowered 2026-07-22 (P1 audit-remediation): SequentialExecutor shed the legacy cross-provider
    // retry lane (RR-269) and WorkerAgent extracted helpers into ContinuationEscalationBudget /
    // WorkerAgentSupport. The ratchet only moves DOWN — these are the new measured line counts.
    ceilings.put("src/main/java/com/navneet/executespec/harness/SequentialExecutor.java", 4866);
    ceilings.put("src/main/java/com/navneet/executespec/engine/WorkerAgent.java", 3415);
    ceilings.put("frontend/src/lib/api/core.ts", 1803);
    ceilings.put("frontend/src/ide/agentPanels.tsx", 1780);
    return Map.copyOf(ceilings);
  }

  @Test
  void godClassesStayAtOrBelowTheirCommittedCeiling() throws IOException {
    List<String> breaches = new ArrayList<>();

    for (Map.Entry<String, Integer> entry : CEILINGS.entrySet()) {
      Path file = Path.of(entry.getKey());
      int ceiling = entry.getValue();
      int actual = countLines(file);

      if (actual > ceiling) {
        breaches.add(
            ("%s is %d lines, ceiling is %d (+%d). Extract the new responsibility into a focused"
                    + " domain service/module instead of raising the ceiling.")
                .formatted(entry.getKey(), actual, ceiling, actual - ceiling));
      }
    }

    assertThat(breaches)
        .as(
            """
            God-class line ceilings breached.

            These five files carry the programme's do-not-grow rule. A breach means net policy or
            lifecycle responsibility was added to a file that is already too large. The fix is to
            extract that responsibility into a focused domain service/module, not to raise the
            ceiling in %s.

            Lowering a ceiling when a file shrinks is expected and welcome.
            """
                .formatted(GodClassLineCeilingTest.class.getSimpleName()))
        .isEmpty();
  }

  @Test
  void shrunkGodClassesHaveTheirCeilingLowered() throws IOException {
    List<String> unclaimed = new ArrayList<>();

    for (Map.Entry<String, Integer> entry : CEILINGS.entrySet()) {
      int ceiling = entry.getValue();
      int actual = countLines(Path.of(entry.getKey()));
      int headroom = ceiling - actual;

      if (headroom > MAX_UNCLAIMED_HEADROOM_LINES) {
        unclaimed.add(
            ("%s is %d lines but its ceiling is still %d (%d lines of unclaimed headroom). Lower"
                    + " the ceiling to %d.")
                .formatted(entry.getKey(), actual, ceiling, headroom, actual));
      }
    }

    assertThat(unclaimed)
        .as(
            """
            God-class ceilings have drifted above the real line counts.

            A shrink that does not lower the ceiling banks invisible headroom, which the file can
            then regrow into for free. That defeats the ratchet. Lower each ceiling listed below to
            the current measured value.
            """)
        .isEmpty();
  }

  @Test
  void noNewProductionClassExceedsTheLineLimit() throws IOException {
    List<String> newOffenders = new ArrayList<>();

    for (Path file : javaSources()) {
      String relative = normalise(JAVA_SOURCE_ROOT.relativize(file));
      int lines = countLines(file);

      if (lines > NEW_CLASS_LINE_LIMIT && !PRE_EXISTING_OVERSIZED_CLASSES.contains(relative)) {
        newOffenders.add("%s is %d lines".formatted(relative, lines));
      }
    }

    assertThat(newOffenders)
        .as(
            """
            New production classes exceed the %d-line limit.

            The plan allows no new production class over roughly %d lines without a documented
            exception. %d pre-existing classes are allowlisted in this test; that allowlist is the
            honest starting point and is expected to shrink, never grow.

            Split the class into focused collaborators. If an exception is genuinely warranted,
            document the reason and add it to the allowlist deliberately.
            """
                .formatted(
                    NEW_CLASS_LINE_LIMIT,
                    NEW_CLASS_LINE_LIMIT,
                    PRE_EXISTING_OVERSIZED_CLASSES.size()))
        .isEmpty();
  }

  @Test
  void oversizedAllowlistHasNoStaleEntries() throws IOException {
    Set<String> stillOversized = new TreeSet<>();

    for (Path file : javaSources()) {
      if (countLines(file) > NEW_CLASS_LINE_LIMIT) {
        stillOversized.add(normalise(JAVA_SOURCE_ROOT.relativize(file)));
      }
    }

    List<String> stale =
        PRE_EXISTING_OVERSIZED_CLASSES.stream()
            .filter(entry -> !stillOversized.contains(entry))
            .toList();

    assertThat(stale)
        .as(
            """
            The oversized-class allowlist contains entries that are no longer oversized (or no
            longer exist).

            Remove them. A stale allowlist entry is standing permission for the file to grow back
            over %d lines without anyone noticing.
            """
                .formatted(NEW_CLASS_LINE_LIMIT))
        .isEmpty();
  }

  private static List<Path> javaSources() throws IOException {
    try (var paths = Files.walk(JAVA_SOURCE_ROOT)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .toList();
    }
  }

  private static String normalise(Path relative) {
    return relative.toString().replace('\\', '/');
  }

  /**
   * Counts lines deterministically from the raw bytes: one per {@code \n}, plus a final line if the
   * file does not end with a newline. Independent of git state, encoding heuristics, and traversal
   * order.
   */
  private static int countLines(Path file) throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    int lines = 0;
    for (byte value : bytes) {
      if (value == '\n') {
        lines++;
      }
    }
    if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
      lines++;
    }
    return lines;
  }

  /**
   * Production classes already over {@link #NEW_CLASS_LINE_LIMIT} lines when this ratchet was
   * installed on 2026-07-19. Generated from the tree as-is: this is the honest starting point, not
   * an endorsement. Entries should be deleted as classes are split, never added casually.
   */
  private static final Set<String> PRE_EXISTING_OVERSIZED_CLASSES =
      Set.of(
          "com/navneet/executespec/agent/ConversationAgent.java",
          "com/navneet/executespec/agent/GeminiNativeToolClient.java",
          "com/navneet/executespec/agent/LlmRouter.java",
          "com/navneet/executespec/agent/SmartClassifierAgent.java",
          "com/navneet/executespec/agentsession/subagent/SubagentProtocolService.java",
          "com/navneet/executespec/api/controller/RunController.java",
          "com/navneet/executespec/auth/controller/AuthController.java",
          "com/navneet/executespec/auth/service/AuthService.java",
          "com/navneet/executespec/auth/service/InvitationService.java",
          "com/navneet/executespec/auth/service/MailService.java",
          "com/navneet/executespec/auth/service/OrgService.java",
          "com/navneet/executespec/auth/service/ProjectService.java",
          "com/navneet/executespec/auth/service/TierEnforcementService.java",
          "com/navneet/executespec/benchmark/BenchmarkGenerationService.java",
          "com/navneet/executespec/benchmark/BenchmarkTrackingService.java",
          "com/navneet/executespec/billing/service/AnalyticsService.java",
          "com/navneet/executespec/billing/service/BillingService.java",
          "com/navneet/executespec/codeintel/ProjectArchitectureService.java",
          "com/navneet/executespec/codereview/CodeReviewService.java",
          "com/navneet/executespec/cost/LlmUsageAuditService.java",
          "com/navneet/executespec/cost/RunCostTracker.java",
          "com/navneet/executespec/cost/RunStepUsageRecorder.java",
          "com/navneet/executespec/deployment/WorkspaceFileService.java",
          "com/navneet/executespec/deployment/preview/DevDockerfileGenerator.java",
          "com/navneet/executespec/deployment/preview/PreviewService.java",
          "com/navneet/executespec/engine/WorkerAgent.java",
          "com/navneet/executespec/eval/EvalTestCaseRegistry.java",
          "com/navneet/executespec/eval/ModelEvaluator.java",
          "com/navneet/executespec/eval/v2/TierEvalRunner.java",
          "com/navneet/executespec/execution/service/ExecutionOrchestrator.java",
          "com/navneet/executespec/execution/service/SolutionExecutionService.java",
          "com/navneet/executespec/executioncontext/LlmExecutionPolicyFactory.java",
          "com/navneet/executespec/harness/SequentialExecutor.java",
          "com/navneet/executespec/integration/RunLifecycleService.java",
          "com/navneet/executespec/integration/RunOrchestrator.java",
          "com/navneet/executespec/integration/RunPlanningCoordinator.java",
          "com/navneet/executespec/integration/scm/ScmPushService.java",
          "com/navneet/executespec/memory/ProjectChatService.java",
          "com/navneet/executespec/memory/chattools/ProjectChatTools.java",
          "com/navneet/executespec/project/rag/CodeIndexingService.java",
          "com/navneet/executespec/project/rag/ProjectDocumentService.java",
          "com/navneet/executespec/project/service/ProjectRepoService.java",
          "com/navneet/executespec/repo/GitHubRepoService.java",
          "com/navneet/executespec/report/ValidationReportService.java",
          "com/navneet/executespec/reviewer/AccuracyAssessmentService.java",
          "com/navneet/executespec/reviewer/ReviewerAgent.java",
          // RR-818 (2026-07-22): ModelProviderCandidateService's benchmark-evidence machinery was
          // extracted to BenchmarkQualityPriorProvider, shrinking it from ~690 to ~460 lines. It is
          // no longer oversized, so its allowlist entry was removed (the arch test requires stale
          // entries be dropped rather than banking invisible headroom).
          "com/navneet/executespec/specchat/SpecChatService.java",
          "com/navneet/executespec/sse/RunEvent.java",
          "com/navneet/executespec/state/StateStore.java",
          "com/navneet/executespec/state/entity/PipelineRun.java",
          "com/navneet/executespec/swebench/SwebenchTaskService.java",
          "com/navneet/executespec/tool/CompatibilityTools.java",
          "com/navneet/executespec/tool/GitTool.java",
          "com/navneet/executespec/tool/RepositorySearchService.java",
          "com/navneet/executespec/tool/remote/RemoteToolDispatcher.java");
}
