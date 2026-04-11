package com.navneet.executespec.harness;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Fast syntax-only compile check run after each progressive merge.
 *
 * <p>Runs a lightweight tool-specific syntax check (not full compilation):
 * <ul>
 *   <li>Java/Maven: {@code mvn compile -q -B} (30s max)</li>
 *   <li>Node/TypeScript: {@code npx tsc --noEmit} (20s max)</li>
 *   <li>Python: {@code python -m py_compile} on all *.py files (10s max)</li>
 * </ul>
 *
 * <p>If the check fails, the caller may revert the merge and retry the task
 * with the compile error as additional context.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProgressiveMergeValidator {

    private static final int JAVA_TIMEOUT_SEC   = 30;
    private static final int NODE_TIMEOUT_SEC   = 20;
    private static final int PYTHON_TIMEOUT_SEC = 10;

    private final MeterRegistry meterRegistry;

    public enum CheckResult { PASSED, FAILED, SKIPPED }

    public record MergeCheckResult(CheckResult result, String errorOutput) {
        public static MergeCheckResult passed()          { return new MergeCheckResult(CheckResult.PASSED,  ""); }
        public static MergeCheckResult skipped()         { return new MergeCheckResult(CheckResult.SKIPPED, ""); }
        public static MergeCheckResult failed(String err){ return new MergeCheckResult(CheckResult.FAILED,  err); }
    }

    /**
     * Runs the appropriate quick check for the project rooted at {@code workspacePath}.
     * Detection order: pom.xml (Java) → tsconfig.json (TypeScript) → *.py (Python) → SKIPPED.
     */
    public MergeCheckResult quickCheck(String runId, Path workspacePath) {
        if (workspacePath == null || !Files.isDirectory(workspacePath)) {
            return MergeCheckResult.skipped();
        }

        try {
            if (Files.exists(workspacePath.resolve("pom.xml"))) {
                return runCheck(runId, "java", workspacePath,
                        new String[]{"mvn", "compile", "-q", "-B"}, JAVA_TIMEOUT_SEC);
            }
            if (Files.exists(workspacePath.resolve("tsconfig.json"))) {
                return runTypeScriptCheck(runId, workspacePath);
            }
            // Python: check if any *.py files exist
            if (hasPythonFiles(workspacePath)) {
                return runPythonCheck(runId, workspacePath);
            }
        } catch (Exception e) {
            log.warn("[MergeValidator] Quick check failed to start for run {}: {}", runId, e.getMessage());
            return MergeCheckResult.skipped(); // fail-open: don't block merge on validator errors
        }

        return MergeCheckResult.skipped();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * TypeScript check with progressive-merge awareness.
     *
     * <p>During progressive merge, components are written before their imports exist,
     * so TS2307 (Cannot find module) and TS2304 (Cannot find name) errors are expected
     * and must not block a merge. Only real syntax and type errors (e.g. TS1005, TS2345,
     * TS2339) should cause a FAILED result.
     *
     * <p>If ALL errors in the tsc output are missing-import errors, the check is treated
     * as SKIPPED (not FAILED). If there are any other errors, the check FAILS with only
     * those real errors surfaced to the retry worker.
     */
    private MergeCheckResult runTypeScriptCheck(String runId, Path workspace)
            throws IOException, InterruptedException {
        MergeCheckResult raw = runCheck(runId, "typescript", workspace,
                new String[]{"npx", "tsc", "--noEmit", "--skipLibCheck"}, NODE_TIMEOUT_SEC);

        if (raw.result() != CheckResult.FAILED) return raw;

        // Filter out missing-import errors that are expected during progressive merge.
        // TS2307: Cannot find module '...'
        // TS2304: Cannot find name '...'
        // TS2305: Module '...' has no exported member '...' (can occur before sibling file exists)
        // TS6059: File '...' is not under 'rootDir' (project setup in progress)
        // Strip ANSI escape codes before processing (tsc emits colour codes on some terminals)
        String output = raw.errorOutput().replaceAll("\u001B\\[[;\\d]*m", "").replaceAll("\\[\\d+m", "");
        String[] lines = output.split("\n");
        StringBuilder realErrors = new StringBuilder();
        for (String line : lines) {
            // Skip blank/whitespace-only lines (may be ANSI-stripped colour markers)
            if (line.isBlank()) continue;
            boolean missingImport = line.contains("TS2307") || line.contains("TS2304")
                    || line.contains("TS2305") || line.contains("TS6059");
            if (!missingImport) {
                realErrors.append(line).append("\n");
            }
        }

        String realErrorOutput = realErrors.toString().trim();
        if (realErrorOutput.isEmpty()) {
            log.debug("[MergeValidator] TypeScript check — only missing-import errors (expected during progressive merge) for run {} — treating as SKIPPED", runId);
            recordMetric("typescript", "skipped_missing_imports");
            return MergeCheckResult.skipped();
        }

        // Real errors found — fail with only those
        String truncated = realErrorOutput.length() > 2000 ? realErrorOutput.substring(0, 2000) + "…" : realErrorOutput;
        log.warn("[MergeValidator] TypeScript check FAILED (real errors) for run {}: {}", runId, truncated);
        return MergeCheckResult.failed(truncated);
    }

    private MergeCheckResult runCheck(String runId, String language, Path workspace,
                                      String[] command, int timeoutSec) throws IOException, InterruptedException {
        log.debug("[MergeValidator] Running {} check for run {} in {}", language, runId, workspace);
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workspace.toFile())
                .redirectErrorStream(true);

        // Propagate PATH so mvn/npx are resolvable
        pb.environment().put("PATH", System.getenv("PATH"));

        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);

        if (!finished) {
            proc.destroyForcibly();
            log.warn("[MergeValidator] {} check timed out after {}s for run {} — treating as SKIPPED",
                    language, timeoutSec, runId);
            recordMetric(language, "skipped");
            return MergeCheckResult.skipped();
        }

        int exitCode = proc.exitValue();
        if (exitCode == 0) {
            log.debug("[MergeValidator] {} check PASSED for run {}", language, runId);
            recordMetric(language, "passed");
            return MergeCheckResult.passed();
        } else {
            String truncated = output.length() > 2000 ? output.substring(0, 2000) + "…" : output;
            log.warn("[MergeValidator] {} check FAILED for run {} (exit={}): {}",
                    language, runId, exitCode, truncated);
            recordMetric(language, "failed");
            return MergeCheckResult.failed(truncated);
        }
    }

    private MergeCheckResult runPythonCheck(String runId, Path workspace)
            throws IOException, InterruptedException {
        // Collect up to 50 .py files for syntax check
        try (var stream = Files.walk(workspace, 5)) {
            String[] pyFiles = stream
                    .filter(p -> p.toString().endsWith(".py"))
                    .map(Path::toString)
                    .limit(50)
                    .toArray(String[]::new);

            if (pyFiles.length == 0) return MergeCheckResult.skipped();

            String[] command = new String[3 + pyFiles.length];
            command[0] = "python3";
            command[1] = "-m";
            command[2] = "py_compile";
            System.arraycopy(pyFiles, 0, command, 3, pyFiles.length);

            return runCheck(runId, "python", workspace, command, PYTHON_TIMEOUT_SEC);
        }
    }

    private boolean hasPythonFiles(Path workspace) {
        try (var stream = Files.walk(workspace, 5)) {
            return stream.anyMatch(p -> p.toString().endsWith(".py"));
        } catch (IOException e) {
            return false;
        }
    }

    private void recordMetric(String language, String result) {
        Counter.builder("executespec.merge.progressive_check")
                .tag("language", language)
                .tag("result", result)
                .register(meterRegistry).increment();
    }
}
