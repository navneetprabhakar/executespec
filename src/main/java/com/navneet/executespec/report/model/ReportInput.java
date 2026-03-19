package com.navneet.executespec.report.model;

import com.navneet.executespec.agent.model.ReviewReport;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.compiler.CompileResult;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;

import java.util.List;

public record ReportInput(
    PipelineRun run,
    List<WorkerReport> workerReports,
    ReviewReport review,
    CompileResult compileResult,
    List<AgentTask> tasks
) {}
