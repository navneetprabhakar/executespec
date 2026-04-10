package com.navneet.executespec.report.model;

import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.compiler.CompileResult;
import com.navneet.executespec.reviewer.model.ReviewReportV2;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.testgen.model.TestResult;

import java.util.List;

public record ReportInput(
    PipelineRun run,
    List<WorkerReport> workerReports,
    ReviewReportV2 review,
    CompileResult compileResult,
    List<AgentTask> tasks,
    TestExecutionSummary testSummary,
    List<TestResult> testResults,
    boolean generatedAfterImprovements,
    /** Preview URL at the time the report was generated. Null if preview expired or never started. */
    String previewUrl
) {
    /** Backward-compatible constructor for call sites that don't have test data. */
    public ReportInput(PipelineRun run, List<WorkerReport> workerReports,
                       ReviewReportV2 review, CompileResult compileResult,
                       List<AgentTask> tasks) {
        this(run, workerReports, review, compileResult, tasks, null, List.of(), false, null);
    }

    /** Constructor with test data but no afterImprovements flag. */
    public ReportInput(PipelineRun run, List<WorkerReport> workerReports,
                       ReviewReportV2 review, CompileResult compileResult,
                       List<AgentTask> tasks, TestExecutionSummary testSummary,
                       List<TestResult> testResults) {
        this(run, workerReports, review, compileResult, tasks, testSummary, testResults, false, null);
    }
}
