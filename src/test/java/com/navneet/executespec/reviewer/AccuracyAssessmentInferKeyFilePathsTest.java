package com.navneet.executespec.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.tool.FileSystemTool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V162 — unit tests for the R-343 P1 fix on {@link AccuracyAssessmentService#inferKeyFilePaths}.
 *
 * <p>Background: the v2 R-343 report mis-attributed reviewer false-positives to "context-exhaustion
 * / file content truncation". Reading the source showed the actual cause: when ANY file matched a
 * structural keyword (route, main, service, etc.) the method returned ONLY structural matches, even
 * if just 1 out of a 6-slot read budget. Small projects with one structural file and the actual
 * logic in a non-structural file (e.g. {@code wordfreq.py} alongside {@code main.py}) had
 * wordfreq.py never read. The LLM saw it in the file tree but no content, then emitted "exists but
 * content not readable" issues across every spec requirement.
 *
 * <p>Fix: top up structural matches with smallest-first non-structural source files until the
 * 6-file read budget is filled.
 *
 * <p>Tests use reflection on the private method to avoid changing visibility.
 */
class AccuracyAssessmentInferKeyFilePathsTest {

  private AccuracyAssessmentService svc;
  private Method inferKeyFilePaths;
  private Method inferTestFilePaths;
  private Method inferConfigEvidencePaths;

  @BeforeEach
  void setUp() throws Exception {
    svc =
        new AccuracyAssessmentService(
            mock(LlmRouter.class),
            mock(ModelResolver.class),
            mock(FileSystemTool.class),
            mock(StateStore.class),
            new ObjectMapper());
    inferKeyFilePaths =
        AccuracyAssessmentService.class.getDeclaredMethod("inferKeyFilePaths", String.class);
    inferKeyFilePaths.setAccessible(true);
    inferTestFilePaths =
        AccuracyAssessmentService.class.getDeclaredMethod("inferTestFilePaths", String.class);
    inferTestFilePaths.setAccessible(true);
    inferConfigEvidencePaths =
        AccuracyAssessmentService.class.getDeclaredMethod("inferConfigEvidencePaths", String.class);
    inferConfigEvidencePaths.setAccessible(true);
  }

  @SuppressWarnings("unchecked")
  private List<String> infer(String fileTree) throws Exception {
    return (List<String>) inferKeyFilePaths.invoke(svc, fileTree);
  }

  @SuppressWarnings("unchecked")
  private List<String> inferTests(String fileTree) throws Exception {
    return (List<String>) inferTestFilePaths.invoke(svc, fileTree);
  }

  @SuppressWarnings("unchecked")
  private List<String> inferConfigs(String fileTree) throws Exception {
    return (List<String>) inferConfigEvidencePaths.invoke(svc, fileTree);
  }

  @Test
  void testEvidenceSelectsCommonConventionsWithoutDisplacingProductionFiles() throws Exception {
    String tree =
        String.join(
            "\n",
            "src/email.py",
            "tests/test_email.py",
            "src/EmailServiceTest.java",
            "frontend/src/email.spec.ts",
            "frontend/src/email.test.tsx",
            "target/generated/GeneratedTest.java",
            "node_modules/pkg/test.js");

    assertThat(infer(tree)).contains("src/email.py").doesNotContain("tests/test_email.py");
    assertThat(inferTests(tree))
        .containsExactlyInAnyOrder(
            "tests/test_email.py",
            "src/EmailServiceTest.java",
            "frontend/src/email.spec.ts",
            "frontend/src/email.test.tsx");
  }

  @Test
  void testEvidenceIsBounded() throws Exception {
    StringBuilder tree = new StringBuilder();
    for (int i = 0; i < 12; i++) tree.append("tests/test_case_").append(i).append(".py\n");

    assertThat(inferTests(tree.toString())).hasSize(AccuracyAssessmentService.TEST_FILE_CAP);
  }

  @Test
  void workflowEvidenceSelectsBuildAndTestYamlButExcludesGeneratedDirectories() throws Exception {
    String tree =
        String.join(
            "\n",
            ".github/workflows/build.yml",
            ".github/workflows/test.yaml",
            ".github/workflows/deploy.yml",
            "target/workflows/release.yml",
            "build/generated.yml",
            "dist/copied.yaml",
            "src/main/resources/application.yml");

    assertThat(inferConfigs(tree))
        .contains(
            ".github/workflows/build.yml",
            ".github/workflows/test.yaml",
            ".github/workflows/deploy.yml",
            "src/main/resources/application.yml")
        .doesNotContain("target/workflows/release.yml", "build/generated.yml", "dist/copied.yaml");
  }

  @Test
  void workflowEvidenceIsPrioritisedAndBoundedIndependently() throws Exception {
    StringBuilder tree = new StringBuilder();
    for (int i = 0; i < 12; i++) tree.append("config/option-").append(i).append(".json\n");
    tree.append(".github/workflows/build.yml\n");
    tree.append(".github/workflows/test.yml\n");

    List<String> selected = inferConfigs(tree.toString());
    assertThat(selected).hasSize(AccuracyAssessmentService.CONFIG_FILE_CAP);
    assertThat(selected.subList(0, 2))
        .containsExactlyInAnyOrder(".github/workflows/build.yml", ".github/workflows/test.yml");
  }

  @Test
  void regressionR343_smallProjectWithOneStructuralFile_topsUpWithNonStructural() throws Exception {
    // The exact failure shape from the R-343 deeper persona test:
    // wordfreq.py (non-structural, holds the actual logic) was being skipped
    // because main.py (structural) was the only "key" file picked.
    String tree =
        String.join(
            "\n",
            "main.py",
            "wordfreq.py",
            "fixtures/sample.txt", // non-source — should be filtered by ext
            "test_wordfreq.py", // excluded by 'test' filter
            "README.md", // non-source
            ".pytest_cache/CACHEDIR.TAG",
            ".pytest_cache/README.md");

    List<String> picked = infer(tree);

    // Both source files must be returned — the bug was wordfreq.py being dropped
    assertThat(picked).containsExactlyInAnyOrder("main.py", "wordfreq.py");
    // Structural-first ordering preserved (main.py is structural, wordfreq.py isn't)
    assertThat(picked.indexOf("main.py")).isLessThan(picked.indexOf("wordfreq.py"));
  }

  @Test
  void manyStructuralFiles_returnsAllStructuralUpToBudget() throws Exception {
    // Structural files fill the bounded source budget, so utilities don't get pulled in.
    // Note: paths must avoid common structural substrings ('main', 'app',
    // 'api') in directory components or even Helpers/Utils land in the
    // structural bucket. Using top-level paths to keep the test crisp.
    String tree =
        String.join(
            "\n",
            "UserController.java",
            "OrderController.java",
            "PaymentController.java",
            "AuthService.java",
            "UserRepository.java",
            "OrderRepository.java",
            "PaymentRepository.java",
            "AccountRepository.java",
            "InventoryRepository.java",
            "AuditRepository.java",
            "StringHelpers.java", // non-structural
            "DateUtils.java"); // non-structural

    List<String> picked = infer(tree);

    assertThat(picked).hasSize(10);
    // Utility classes were not pulled in (would have been if top-up ran)
    assertThat(picked).doesNotContain("StringHelpers.java", "DateUtils.java");
  }

  @Test
  void manyStructuralFiles_capAtSingleRepoBudget() throws Exception {
    // A Spring Boot project with 30 controllers — single-repo runs cap at the read budget
    // (SINGLE_REPO_BUDGET = 10).
    StringBuilder tree = new StringBuilder();
    for (int i = 0; i < 30; i++) {
      tree.append(String.format("Controller%02d.java%n", i));
    }
    tree.append("Helper.java\n"); // non-structural fallback

    List<String> picked = infer(tree.toString());

    assertThat(picked).hasSize(10);
    assertThat(picked).allMatch(p -> p.startsWith("Controller"));
    assertThat(picked).doesNotContain("Helper.java");
  }

  @Test
  void noStructuralMatches_fallsBackToAllSource() throws Exception {
    // A single-file CLI that doesn't match any structural keyword
    // (the original fall-through case). Behavior must be preserved.
    String tree = "fib.py\nutils.py\nrunner.py";

    List<String> picked = infer(tree);

    // All three source files retained — pre-fix behavior preserved
    assertThat(picked).containsExactlyInAnyOrder("fib.py", "utils.py", "runner.py");
  }

  @Test
  void staticWebSourcesAreIncludedAsImplementationEvidence() throws Exception {
    String tree =
        String.join(
            "\n",
            "repos/site/index.html",
            "repos/site/styles.css",
            "repos/site/src/App.vue",
            "repos/site/src/widget.svelte",
            "repos/site/test/render.test.js");

    assertThat(infer(tree))
        .contains(
            "repos/site/index.html",
            "repos/site/styles.css",
            "repos/site/src/App.vue",
            "repos/site/src/widget.svelte")
        .doesNotContain("repos/site/test/render.test.js");
  }

  @Test
  void emptyOrBlankTree_returnsEmpty() throws Exception {
    assertThat(infer("")).isEmpty();
    assertThat(infer("   ")).isEmpty();
    assertThat(infer(null)).isEmpty();
  }

  // Multi-repo budget (2026-05-02)

  @Test
  void multiRepo_perRepoBudgetGivesEachRepoEvidence() throws Exception {
    // 3-repo workspace shape from b171fa52 — pplai-api + pplai-frontend-mobile + frontend.
    // Pre-fix: 6-file flat budget burned on routes from one repo, leaving the assessor unable
    // to confirm spec items in the others ("ai.py exists but content unread, cannot confirm" ×N).
    // Post-fix: each repo gets PER_REPO_BUDGET (6) picks, capped at MULTI_REPO_TOTAL_CAP (18).
    StringBuilder tree = new StringBuilder();
    // pplai-api (Python/Node) — 8 candidates, only 6 should make it past the budget
    for (int i = 0; i < 4; i++)
      tree.append(String.format("repos/pplai-api/src/routes/r%d.ts%n", i));
    for (int i = 0; i < 4; i++)
      tree.append(String.format("repos/pplai-api/app/services/s%d.py%n", i));
    // pplai-frontend-mobile (RN) — 5 candidates
    for (int i = 0; i < 5; i++)
      tree.append(String.format("repos/pplai-frontend-mobile/services/m%d.ts%n", i));
    // frontend (web) — 5 candidates
    for (int i = 0; i < 5; i++) tree.append(String.format("repos/frontend/src/pages/p%d.tsx%n", i));

    List<String> picked = infer(tree.toString());

    // Each repo represented; total ≤ MULTI_REPO_TOTAL_CAP (18).
    assertThat(picked).hasSizeLessThanOrEqualTo(18);
    assertThat(picked).anyMatch(p -> p.startsWith("repos/pplai-api/"));
    assertThat(picked).anyMatch(p -> p.startsWith("repos/pplai-frontend-mobile/"));
    assertThat(picked).anyMatch(p -> p.startsWith("repos/frontend/"));
    // At least 4 picks per repo (5 candidates each for mobile + frontend; 8 for pplai-api).
    long apiPicks = picked.stream().filter(p -> p.startsWith("repos/pplai-api/")).count();
    long mobilePicks =
        picked.stream().filter(p -> p.startsWith("repos/pplai-frontend-mobile/")).count();
    long fePicks = picked.stream().filter(p -> p.startsWith("repos/frontend/")).count();
    assertThat(apiPicks).isGreaterThanOrEqualTo(4);
    assertThat(mobilePicks).isGreaterThanOrEqualTo(4);
    assertThat(fePicks).isGreaterThanOrEqualTo(4);
  }

  @Test
  void multiRepo_totalCapBoundsTokenCost() throws Exception {
    // 5 repos × 10 candidates each — total picks must not exceed MULTI_REPO_TOTAL_CAP.
    StringBuilder tree = new StringBuilder();
    for (int r = 0; r < 5; r++) {
      for (int i = 0; i < 10; i++) {
        tree.append(String.format("repos/repo%d/src/route%d.ts%n", r, i));
      }
    }

    List<String> picked = infer(tree.toString());

    assertThat(picked).hasSizeLessThanOrEqualTo(24);
    assertThat(picked.stream().filter(p -> p.startsWith("repos/repo0/")).count()).isEqualTo(8);
  }

  @Test
  void singleRepo_keepsFlatBudget() throws Exception {
    // Single repos/ subdir (or no repos/ prefix at all) keeps the V162 single-repo budget
    // of 10.
    StringBuilder tree = new StringBuilder();
    for (int i = 0; i < 12; i++) {
      tree.append(String.format("repos/only-one/src/route%d.ts%n", i));
    }
    List<String> picked = infer(tree.toString());
    assertThat(picked).hasSize(10);
  }

  @Test
  void runtimeEvidenceCannotBeCrowdedOutByFastApiStructure() throws Exception {
    String tree =
        String.join(
            "\n",
            "app/routes/__init__.py",
            "app/routes/reservations.py",
            "app/services/__init__.py",
            "app/services/reservation_service.py",
            "alembic/versions/001_initial.py",
            "app/models.py",
            "app/schemas.py",
            "app/exceptions.py",
            "app/main.py",
            "app/database.py",
            "app/config.py",
            "app/dependencies.py");

    assertThat(infer(tree))
        .contains(
            "app/routes/reservations.py",
            "app/services/reservation_service.py",
            "app/main.py",
            "app/database.py",
            "app/config.py");
  }

  // Tiered structural detection (2026-05-02 b171fa52 follow-up)

  @Test
  void multiRepo_structuralDirBeatsTopLevelStructuralFilename() throws Exception {
    // b171fa52: pplai-api repo had top-level files (auth.py, cache.py, models.py, schemas.py)
    // that all matched the structural keyword regex AND were shorter than the actual deliverable
    // app/routers/ai.py — so the structural-then-shortest sort burned all 6 picks on the
    // top-level files and missed the route the assessor needed to confirm.
    // Post-fix: files inside a structural DIRECTORY (routers/, services/, …) jump to TIER A,
    // beating top-level structural-NAMED files (TIER B). Top-up still happens at TIER C.
    String tree =
        String.join(
            "\n",
            "repos/pplai-api/app/auth.py",
            "repos/pplai-api/app/cache.py",
            "repos/pplai-api/app/models.py",
            "repos/pplai-api/app/schemas.py",
            "repos/pplai-api/app/storage.py",
            "repos/pplai-api/app/database.py",
            "repos/pplai-api/app/routers/ai.py",
            "repos/pplai-api/app/routers/contacts.py",
            "repos/pplai-api/app/services/contact_ai.py",
            "repos/pplai-frontend-mobile/services/aiService.ts",
            "repos/pplai-frontend-mobile/hooks/useAISummary.ts",
            "repos/pplai-frontend-mobile/components/AISummaryCard.tsx",
            "repos/frontend/services/aiService.ts");

    List<String> picked = infer(tree);

    // The deliverable files (under structural dirs) MUST be picked.
    assertThat(picked).contains("repos/pplai-api/app/routers/ai.py");
    assertThat(picked).contains("repos/pplai-frontend-mobile/services/aiService.ts");
    assertThat(picked).contains("repos/pplai-frontend-mobile/hooks/useAISummary.ts");
    assertThat(picked).contains("repos/pplai-frontend-mobile/components/AISummaryCard.tsx");
    assertThat(picked).contains("repos/frontend/services/aiService.ts");
  }

  @Test
  void boilerplateFilenamesAreDeprioritised() throws Exception {
    // index/main/app/server.<ext> are entry-point boilerplate — they almost always exist
    // (Express server bootstrap / Next.js app shell / FastAPI main) and rarely contain the
    // business logic. Push them to TIER C (non-structural) so meaningful files win.
    String tree =
        String.join(
            "\n",
            "repos/api/app.ts",
            "repos/api/server.ts",
            "repos/api/index.js",
            "repos/api/main.py",
            "repos/api/routes/users.ts", // structural dir → TIER A
            "repos/api/routes/orders.ts");

    List<String> picked = infer(tree);

    // Routes win over boilerplate even though boilerplate paths are shorter.
    assertThat(picked).contains("repos/api/routes/users.ts");
    assertThat(picked).contains("repos/api/routes/orders.ts");
    assertThat(picked.indexOf("repos/api/routes/users.ts"))
        .isLessThan(picked.indexOf("repos/api/main.py"));
  }

  @Test
  void repoNameInPathDoesNotTriggerStructuralKeyword() throws Exception {
    // pplai-api in the path no longer counts toward the "api" keyword — only the part AFTER
    // the repos/<name>/ prefix is matched against structural keywords.
    String tree =
        String.join(
            "\n",
            "repos/pplai-api/start.sh", // not structural under any tier
            "repos/pplai-api/fix_files.py", // not structural under any tier
            "repos/pplai-api/app/routers/ai.py"); // TIER A

    List<String> picked = infer(tree);

    // ai.py should be FIRST (TIER A) regardless of length.
    assertThat(picked.get(0)).isEqualTo("repos/pplai-api/app/routers/ai.py");
  }

  @Test
  void mixedTree_workspaceRootCountsAsOneBucket() throws Exception {
    // Workspace-root files (no repos/ prefix) collapse into one bucket; if mixed with a repos/
    // bucket, the run is treated as multi-bucket and per-bucket budgets apply.
    String tree =
        String.join(
            "\n",
            ".agent-context/notes.md",
            "tools/probe.py",
            "tools/seed.py",
            "tools/migrate.py",
            "repos/api/src/routes/users.ts",
            "repos/api/src/services/auth.ts",
            "repos/api/src/models/user.ts");

    List<String> picked = infer(tree);

    // Both buckets represented (the _root_ bucket only has tools/*.py since
    // .agent-context/notes.md is not a source extension).
    assertThat(picked).anyMatch(p -> p.startsWith("repos/api/"));
    assertThat(picked).anyMatch(p -> p.startsWith("tools/"));
  }

  @Test
  void excludesTestSpecAndBuildOutput() throws Exception {
    // The exclude pattern must continue to drop test/spec/cache paths,
    // even when topping up the candidate list.
    String tree =
        String.join(
            "\n",
            "src/Server.java",
            "src/Helper.java",
            "src/test/ServerTest.java", // 'test' → excluded
            "src/test/integration_spec.java", // 'spec' → excluded
            "target/classes/Server.class", // 'target' → excluded
            "node_modules/foo/index.js", // 'node_modules' → excluded
            "build/output.js", // 'build' → excluded
            "dist/bundle.js"); // 'dist' → excluded

    List<String> picked = infer(tree);

    assertThat(picked).containsExactlyInAnyOrder("src/Server.java", "src/Helper.java");
  }
}
