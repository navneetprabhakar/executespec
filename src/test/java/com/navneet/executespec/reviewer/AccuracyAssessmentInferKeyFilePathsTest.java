package com.navneet.executespec.reviewer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.tool.FileSystemTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * V162 — unit tests for the R-343 P1 fix on
 * {@link AccuracyAssessmentService#inferKeyFilePaths}.
 *
 * <p>Background: the v2 R-343 report mis-attributed reviewer false-positives
 * to "context-exhaustion / file content truncation". Reading the source showed
 * the actual cause: when ANY file matched a structural keyword (route, main,
 * service, etc.) the method returned ONLY structural matches, even if just 1
 * out of a 6-slot read budget. Small projects with one structural file and
 * the actual logic in a non-structural file (e.g. {@code wordfreq.py} alongside
 * {@code main.py}) had wordfreq.py never read. The LLM saw it in the file tree
 * but no content, then emitted "exists but content not readable" issues across
 * every spec requirement.
 *
 * <p>Fix: top up structural matches with smallest-first non-structural source
 * files until the 6-file read budget is filled.
 *
 * <p>Tests use reflection on the private method to avoid changing visibility.
 */
class AccuracyAssessmentInferKeyFilePathsTest {

    private AccuracyAssessmentService svc;
    private Method inferKeyFilePaths;

    @BeforeEach
    void setUp() throws Exception {
        svc = new AccuracyAssessmentService(
                mock(LlmRouter.class),
                mock(ModelResolver.class),
                mock(FileSystemTool.class),
                mock(StateStore.class),
                new ObjectMapper());
        inferKeyFilePaths = AccuracyAssessmentService.class
                .getDeclaredMethod("inferKeyFilePaths", String.class);
        inferKeyFilePaths.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private List<String> infer(String fileTree) throws Exception {
        return (List<String>) inferKeyFilePaths.invoke(svc, fileTree);
    }

    @Test
    void regressionR343_smallProjectWithOneStructuralFile_topsUpWithNonStructural() throws Exception {
        // The exact failure shape from the R-343 deeper persona test:
        // wordfreq.py (non-structural, holds the actual logic) was being skipped
        // because main.py (structural) was the only "key" file picked.
        String tree = String.join("\n",
                "main.py",
                "wordfreq.py",
                "fixtures/sample.txt",  // non-source — should be filtered by ext
                "test_wordfreq.py",     // excluded by 'test' filter
                "README.md",            // non-source
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
        // A project with 6 structural files + 2 utility files — the structural
        // list alone fills the 6-slot budget, so utilities don't get pulled in.
        // Note: paths must avoid common structural substrings ('main', 'app',
        // 'api') in directory components or even Helpers/Utils land in the
        // structural bucket. Using top-level paths to keep the test crisp.
        String tree = String.join("\n",
                "UserController.java",
                "OrderController.java",
                "PaymentController.java",
                "AuthService.java",
                "UserRepository.java",
                "OrderRepository.java",
                "StringHelpers.java",   // non-structural
                "DateUtils.java");      // non-structural

        List<String> picked = infer(tree);

        assertThat(picked).hasSize(6);
        // Utility classes were not pulled in (would have been if top-up ran)
        assertThat(picked).doesNotContain("StringHelpers.java", "DateUtils.java");
    }

    @Test
    void manyStructuralFiles_capAt10ProtectsAgainst30Controllers() throws Exception {
        // A Spring Boot project with 30 controllers — the structural matcher
        // caps at 10 to keep token budgets bounded. Top-up doesn't expand
        // beyond the cap.
        StringBuilder tree = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            tree.append(String.format("Controller%02d.java%n", i));
        }
        tree.append("Helper.java\n");  // non-structural fallback

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
    void emptyOrBlankTree_returnsEmpty() throws Exception {
        assertThat(infer("")).isEmpty();
        assertThat(infer("   ")).isEmpty();
        assertThat(infer(null)).isEmpty();
    }

    @Test
    void excludesTestSpecAndBuildOutput() throws Exception {
        // The exclude pattern must continue to drop test/spec/cache paths,
        // even when topping up the candidate list.
        String tree = String.join("\n",
                "src/Server.java",
                "src/Helper.java",
                "src/test/ServerTest.java",          // 'test' → excluded
                "src/test/integration_spec.java",    // 'spec' → excluded
                "target/classes/Server.class",       // 'target' → excluded
                "node_modules/foo/index.js",         // 'node_modules' → excluded
                "build/output.js",                   // 'build' → excluded
                "dist/bundle.js");                   // 'dist' → excluded

        List<String> picked = infer(tree);

        assertThat(picked).containsExactlyInAnyOrder("src/Server.java", "src/Helper.java");
    }
}
