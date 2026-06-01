package com.navneet.executespec.report.model;

import java.util.List;

import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootVerification;
import com.navneet.executespec.reviewer.model.ReviewReport;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.testgen.model.TestResult;

public record ReportInput(
    PipelineRun run,
    List<WorkerReport> workerReports,
    ReviewReport review,
    CompileResult compileResult,
    List<AgentTask> tasks,
    TestExecutionSummary testSummary,
    List<TestResult> testResults,
    boolean generatedAfterImprovements,
    /**
     * Preview URL at the time the report was generated. Null if preview expired or never started.
     */
    String previewUrl,
    /**
     * Deterministic app-boot verification (RL-2026-05-066, verifyBoot). Null when no boot signal
     * was captured for the run (default — the flag is OFF) — the BUILD scoring slot then falls back
     * to the compile-only signal, so reports are byte-identical until verifyBoot is enabled.
     */
    BootVerification bootVerification) {
  /** Backward-compatible constructor for call sites that don't have test data. */
  public ReportInput(
      PipelineRun run,
      List<WorkerReport> workerReports,
      ReviewReport review,
      CompileResult compileResult,
      List<AgentTask> tasks) {
    this(run, workerReports, review, compileResult, tasks, null, List.of(), false, null, null);
  }

  /** Constructor with test data but no afterImprovements flag. */
  public ReportInput(
      PipelineRun run,
      List<WorkerReport> workerReports,
      ReviewReport review,
      CompileResult compileResult,
      List<AgentTask> tasks,
      TestExecutionSummary testSummary,
      List<TestResult> testResults) {
    this(
        run,
        workerReports,
        review,
        compileResult,
        tasks,
        testSummary,
        testResults,
        false,
        null,
        null);
  }

  /** Constructor with test data + a deterministic boot verification (verifyBoot population). */
  public ReportInput(
      PipelineRun run,
      List<WorkerReport> workerReports,
      ReviewReport review,
      CompileResult compileResult,
      List<AgentTask> tasks,
      TestExecutionSummary testSummary,
      List<TestResult> testResults,
      BootVerification bootVerification) {
    this(
        run,
        workerReports,
        review,
        compileResult,
        tasks,
        testSummary,
        testResults,
        false,
        null,
        bootVerification);
  }
}
