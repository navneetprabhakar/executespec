package com.navneet.executespec.report.model;

import com.navneet.executespec.agent.model.ReviewReport;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.compiler.CompileResult;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.testgen.model.TestResult;

import java.util.List;

public record ReportInput(
    PipelineRun run,
    List<WorkerReport> workerReports,
    ReviewReport review,
    CompileResult compileResult,
    List<AgentTask> tasks,
    TestExecutionSummary testSummary,
    List<TestResult> testResults
) {
    /** Backward-compatible constructor for call sites that don't have test data. */
    public ReportInput(PipelineRun run, List<WorkerReport> workerReports,
                       ReviewReport review, CompileResult compileResult,
                       List<AgentTask> tasks) {
        this(run, workerReports, review, compileResult, tasks, null, List.of());
    }
}
