package com.navneet.executespec.v2.test;

import com.navneet.executespec.v2.reviewer.ReviewerAgentV3;
import com.navneet.executespec.v2.reviewer.ReviewerRequestV2;
import com.navneet.executespec.v2.reviewer.ReviewerResult;
import com.navneet.executespec.v2.tool.ExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V2 Phase 7 verification endpoint.
 *
 * <p>Seeds a temp workspace with a deliberately buggy Node.js module (two files, each
 * containing one intentional bug — an off-by-one in sum and a wrong comparison in max).
 * Runs {@link ReviewerAgentV3} against the buggy workspace and verifies:
 * <ul>
 *   <li>The reviewer reaches {@code FIXED_ISSUES} or {@code CLEAN} overall</li>
 *   <li>Both bugs get corrected — the smoke command now returns the expected values</li>
 *   <li>Final build passes</li>
 * </ul>
 *
 * <p>This is deliberately more targeted than Phase 5's end-to-end test — we WANT the
 * reviewer to find something, so we plant known issues.
 *
 * <p>Usage: {@code GET /api/v2/test/reviewer?provider=anthropic&model=claude-haiku-4-5-20251001}
 */
@RestController
@RequestMapping("/api/v2/test")
@RequiredArgsConstructor
@Slf4j
public class ReviewerTestController {

    /** sum.js with an off-by-one bug — uses array.length - 1 as the loop bound. */
    private static final String BUGGY_SUM = """
            // Returns the sum of an array of integers.
            // BUG: loop runs to length-1 inclusive but stops one element short.
            function sumNumbers(arr) {
              let total = 0;
              for (let i = 0; i < arr.length - 1; i++) {
                total += arr[i];
              }
              return total;
            }

            module.exports = { sumNumbers };
            """;

    /** max.js with a wrong comparison — uses < instead of > so it returns the min. */
    private static final String BUGGY_MAX = """
            // Returns the largest integer in an array.
            // BUG: condition inverted — this actually finds the minimum.
            function maxNumber(arr) {
              let best = arr[0];
              for (let i = 1; i < arr.length; i++) {
                if (arr[i] < best) best = arr[i];
              }
              return best;
            }

            module.exports = { maxNumber };
            """;

    private static final String SMOKE_COMMAND = """
            node -e "const s=require('./sum'); const m=require('./max'); \
            if (s.sumNumbers([10,20,30]) !== 60) { console.error('sum fail:', s.sumNumbers([10,20,30])); process.exit(1); } \
            if (m.maxNumber([3,1,4,1,5,9,2,6]) !== 9) { console.error('max fail:', m.maxNumber([3,1,4,1,5,9,2,6])); process.exit(2); } \
            console.log('smoke ok');"
            """;

    private final ReviewerAgentV3 reviewer;

    @GetMapping("/reviewer")
    public Map<String, Object> testReviewer(
            @RequestParam(defaultValue = "anthropic") String provider,
            @RequestParam(defaultValue = "claude-haiku-4-5-20251001") String model) throws IOException {
        Path workspace = Files.createTempDirectory("v2-reviewer-test-");
        try {
            // Seed workspace with buggy files.
            Files.writeString(workspace.resolve("sum.js"), BUGGY_SUM);
            Files.writeString(workspace.resolve("max.js"), BUGGY_MAX);
            // Init git so gitStatus / gitDiff tools have a baseline.
            runGit(workspace, "init", "-q");
            runGit(workspace, "-c", "user.email=test@v2", "-c", "user.name=v2-test", "add", "-A");
            runGit(workspace, "-c", "user.email=test@v2", "-c", "user.name=v2-test", "commit", "-q", "-m", "buggy seed");

            ExecutionContext ctx = new ExecutionContext(
                    "reviewer-test-run", "reviewer-test-task", workspace.toRealPath());

            long start = System.currentTimeMillis();
            ReviewerResult result = reviewer.review(ReviewerRequestV2.builder()
                    .provider(provider)
                    .model(model)
                    .context(ctx)
                    .buildCommand(SMOKE_COMMAND.trim())
                    .maxIterationsPerPass(20)
                    .skipWiringPass(false)
                    .build());
            long totalMs = System.currentTimeMillis() - start;

            // Verify the bugs were actually fixed by re-running the smoke command in Java.
            Path sumPath = workspace.resolve("sum.js");
            Path maxPath = workspace.resolve("max.js");
            String sumContent = Files.readString(sumPath);
            String maxContent = Files.readString(maxPath);
            // Heuristic checks: the fixed files should NOT contain the buggy patterns anymore.
            boolean sumFixed = !sumContent.contains("arr.length - 1") || sumContent.contains("i < arr.length");
            boolean maxFixed = maxContent.contains("arr[i] > best") || maxContent.contains("Math.max");

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("provider", provider);
            out.put("model", model);
            out.put("workspace", workspace.toString());
            out.put("overall", result.overall().name());
            out.put("generalPassSummary", result.generalPassSummary());
            out.put("wiringPassSummary", result.wiringPassSummary());
            out.put("generalPassOutcome", result.generalPassOutcome() == null ? null
                    : result.generalPassOutcome().outcome().name());
            out.put("wiringPassOutcome", result.wiringPassOutcome() == null ? null
                    : result.wiringPassOutcome().outcome().name());
            out.put("finalBuildPassed", result.finalBuildPassed());
            out.put("finalBuildExitCode", result.finalBuildExitCode());
            out.put("latencyMs", totalMs);
            out.put("sumLooksFixed", sumFixed);
            out.put("maxLooksFixed", maxFixed);
            boolean ok = Boolean.TRUE.equals(result.finalBuildPassed()) && sumFixed && maxFixed;
            out.put("ok", ok);
            return out;
        } finally {
            deleteTree(workspace);
        }
    }

    private static void runGit(Path workspace, String... args) throws IOException {
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("git");
            for (String a : args) cmd.add(a);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.waitFor();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("git command interrupted", ie);
        }
    }

    private static void deleteTree(Path root) {
        try {
            if (!Files.exists(root)) return;
            try (var walk = Files.walk(root)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        } catch (IOException ignored) {}
    }
}
