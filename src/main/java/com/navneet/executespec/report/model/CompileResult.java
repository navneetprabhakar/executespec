package com.navneet.executespec.report.model;

/**
 * Outcome of a compile / smoke-test pass.
 *
 * <p>Kept as a data carrier inside {@link ReportInput} so the validation
 * report can describe a final build attempt. Moved here after the V1
 * {@code compiler/} package was deleted in deprecation P3 — the new V2 smoke
 * path uses {@code buildProject} tool results which are translated into this
 * shape when building a {@code ReportInput}.
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
