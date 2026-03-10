package com.navneet.executespec.compiler;

/**
 * Outcome of a CompilerAgent compile-and-fix cycle.
 *
 * @param passed       true when compile exited 0 (or was skipped)
 * @param skipped      true when no build tool was detected in the workspace
 * @param attempts     number of compile attempts made (0 if skipped)
 * @param errorSummary final compiler error text when still failing; null when passed/skipped
 */
public record CompileResult(boolean passed, boolean skipped, int attempts, String errorSummary) {

    public static CompileResult passed(int attempts) {
        return new CompileResult(true, false, attempts, null);
    }

    public static CompileResult failed(int attempts, String errorSummary) {
        return new CompileResult(false, false, attempts, errorSummary);
    }

    public static CompileResult skip() {
        return new CompileResult(true, true, 0, null);
    }
}
