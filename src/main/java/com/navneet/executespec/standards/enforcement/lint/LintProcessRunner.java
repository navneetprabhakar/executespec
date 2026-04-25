package com.navneet.executespec.standards.enforcement.lint;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * E3.4 — shared subprocess runner used by the lint adapters. Bounded
 * timeout + stdout/stderr capture + graceful CLI-not-found degradation
 * (returns {@code executed=false} so the adapter can return UNAVAILABLE
 * without crashing).
 *
 * <p>Same shape as {@code CliBreakingChangeRunner} from E2.7; could be
 * unified later but kept separate for now so each subsystem owns its
 * timeout policy.
 */
@Component
@Slf4j
public class LintProcessRunner {

    public record Result(boolean executed, int exitCode, String stdout, String stderr,
                         String unavailableReason) {
        public boolean ok() { return executed && exitCode == 0; }
    }

    public Result run(Path workingDir, List<String> argv, long timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            if (workingDir != null) pb.directory(workingDir.toFile());
            pb.redirectErrorStream(false);
            Process p;
            try {
                p = pb.start();
            } catch (IOException ex) {
                String hint = argv.isEmpty() ? "(empty argv)" : argv.get(0);
                return new Result(false, -1, null, null,
                        "CLI '" + hint + "' not available on PATH (" + ex.getMessage() + ")");
            }
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new Result(true, -1, "", "(timeout)",
                        "CLI exceeded " + timeoutSeconds + "s timeout");
            }
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Result(true, p.exitValue(), stdout, stderr, null);
        } catch (Exception ex) {
            log.warn("[LintProcessRunner] failed: {}", ex.getMessage());
            return new Result(false, -1, null, null, "runner failed: " + ex.getMessage());
        }
    }
}
