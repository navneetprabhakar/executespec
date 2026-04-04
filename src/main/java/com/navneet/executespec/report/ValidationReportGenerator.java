package com.navneet.executespec.report;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.reviewer.model.ReviewIssue;
import com.navneet.executespec.reviewer.model.ReviewReportV2;
import com.navneet.executespec.compiler.CompileResult;
import com.navneet.executespec.report.model.ReportInput;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.testgen.model.TestResult;
import com.navneet.executespec.testgen.model.TestResultStatus;
import com.navneet.executespec.state.entity.AgentTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class ValidationReportGenerator {

    private final LlmRouter llmRouter;
    private final ModelResolver modelResolver;

    @Value("classpath:prompts/validation-report.txt")
    private Resource promptTemplate;

    public record LlmReportResult(
        String markdown,
        String model,
        int inputTokens,
        int outputTokens
    ) {}

    public LlmReportResult generate(ReportInput input) throws IOException {
        String systemPrompt = promptTemplate.getContentAsString(StandardCharsets.UTF_8);
        String userMessage = buildUserMessage(input);

        Prompt prompt = new Prompt(List.of(
            new SystemMessage(systemPrompt),
            new UserMessage(userMessage)
        ));

        String runId = input.run().getRunId();
        ResolvedModel resolved = modelResolver.resolve("ValidationReportGenerator", input.run().getAiProviderEnum());
        LlmRouter.LlmResponse lr = llmRouter.callWithFallbackTracked(
                resolved.providerName(), resolved.getModel(), prompt, runId,
                "validation-report", LlmRouter.FallbackMode.IMMEDIATE, 0, false);

        ChatResponse response = lr.response();
        String markdown = response.getResult().getOutput().getText();
        int inTokens = response.getMetadata().getUsage().getPromptTokens().intValue();
        int outTokens = response.getMetadata().getUsage().getCompletionTokens().intValue();

        return new LlmReportResult(markdown, lr.model(), inTokens, outTokens);
    }

    private String buildUserMessage(ReportInput input) {
        var sb = new StringBuilder();
        sb.append("Generate a Validation Report for this pipeline run. Frame all findings as automated quality checks — never mention AI, models, or agents.\n\n");

        // File stats from worker reports
        int filesCreated = 0;
        int filesModified = 0;
        Set<String> languagesDetected = new LinkedHashSet<>();
        if (input.workerReports() != null) {
            for (WorkerReport wr : input.workerReports()) {
                if (wr.getFilesCreated() != null) {
                    filesCreated += wr.getFilesCreated().size();
                    wr.getFilesCreated().forEach(f -> languagesDetected.add(detectLanguage(f)));
                }
                if (wr.getFilesModified() != null) {
                    filesModified += wr.getFilesModified().size();
                    wr.getFilesModified().forEach(f -> languagesDetected.add(detectLanguage(f)));
                }
            }
        }
        languagesDetected.remove("Other");

        sb.append("## Run Data\n");
        sb.append("- Files created: %d\n".formatted(filesCreated));
        sb.append("- Files modified: %d\n".formatted(filesModified));
        sb.append("- Languages: %s\n".formatted(
            languagesDetected.isEmpty() ? "N/A" : String.join(", ", languagesDetected)));

        // Task stats
        int totalTasks = input.tasks() != null ? input.tasks().size() : 0;
        long completedTasks = input.tasks() != null
            ? input.tasks().stream().filter(t -> "COMPLETE".equals(t.getStatus())).count()
            : 0;
        sb.append("- Tasks: %d total, %d completed\n".formatted(totalTasks, completedTasks));

        // Compile result
        List<String> checksRun = new ArrayList<>();
        List<String> checksPassed = new ArrayList<>();
        CompileResult compile = input.compileResult();
        if (compile != null && !compile.skipped()) {
            checksRun.add("COMPILATION");
            if (compile.passed()) {
                checksPassed.add("COMPILATION");
            }
        }

        // Review result (V2 flat issue list)
        ReviewReportV2 review = input.review();
        List<String> allIssueDescriptions = new ArrayList<>();
        if (review != null) {
            checksRun.add("CODE_REVIEW");
            if (review.getIssues() != null && !review.getIssues().isEmpty()) {
                boolean hasCritical = false;
                for (ReviewIssue issue : review.getIssues()) {
                    String severity = issue.getSeverity() != null ? issue.getSeverity().name() : "MINOR";
                    String desc = "[%s] %s".formatted(severity, issue.getDescription());
                    if (issue.getFile() != null) {
                        desc += " (file: %s)".formatted(issue.getFile());
                    }
                    if (issue.isAutoFixed() && issue.isFixSucceeded()) {
                        desc += " [AUTO-FIXED]";
                    }
                    allIssueDescriptions.add(desc);
                    if (issue.getSeverity() == IssueSeverity.CRITICAL) {
                        hasCritical = true;
                    }
                }
                boolean hasBlockers = review.getMergeBlockers() != null && !review.getMergeBlockers().isEmpty();
                if (!hasCritical && !hasBlockers) {
                    checksPassed.add("CODE_REVIEW");
                }
            } else {
                checksPassed.add("CODE_REVIEW");
            }
        }

        // Compile errors as issues
        if (compile != null && !compile.passed() && !compile.skipped() && compile.errorSummary() != null) {
            allIssueDescriptions.add("[BUILD_ERROR] Compilation failed: " + truncate(compile.errorSummary(), 200));
        }

        sb.append("- Checks run: %s\n".formatted(String.join(", ", checksRun)));
        sb.append("- Checks passed: %s\n".formatted(String.join(", ", checksPassed)));
        sb.append("- Total issues found: %d\n\n".formatted(allIssueDescriptions.size()));

        if (allIssueDescriptions.isEmpty()) {
            sb.append("No issues were found. This was a clean run. All checks passed on first attempt.\n");
        } else {
            sb.append("## Issues Found\n\n");
            // Limit to 20 issues for LLM token budget
            List<String> issuesToReport = allIssueDescriptions.size() > 20
                ? allIssueDescriptions.subList(0, 20) : allIssueDescriptions;
            for (int i = 0; i < issuesToReport.size(); i++) {
                sb.append("%d. %s\n".formatted(i + 1, issuesToReport.get(i)));
            }
            if (allIssueDescriptions.size() > 20) {
                sb.append("\n(%d additional issues omitted for brevity)\n".formatted(
                    allIssueDescriptions.size() - 20));
            }
        }

        // Review summary
        if (review != null && review.getSummary() != null) {
            sb.append("\n## Reviewer Summary\n");
            sb.append(review.getSummary()).append("\n");
            sb.append("Recommendation: %s\n".formatted(
                review.getRecommendationCompat() != null ? review.getRecommendationCompat() : "N/A"));
        }

        // Integration test results
        TestExecutionSummary testSummary = input.testSummary();
        if (testSummary != null && testSummary.getTotalTests() > 0) {
            checksRun.add("INTEGRATION_TESTS");
            if (testSummary.getFailed() == 0 && testSummary.getErrors() == 0) {
                checksPassed.add("INTEGRATION_TESTS");
            }

            sb.append("\n## Integration Test Results\n");
            sb.append("- Total: %d\n".formatted(testSummary.getTotalTests()));
            sb.append("- Passed: %d\n".formatted(testSummary.getPassed()));
            sb.append("- Failed: %d\n".formatted(testSummary.getFailed()));
            sb.append("- Errors: %d\n".formatted(testSummary.getErrors()));
            sb.append("- Skipped: %d\n".formatted(testSummary.getSkipped()));
            sb.append("- Duration: %dms\n".formatted(testSummary.getExecutionDurationMs()));

            if (testSummary.isAllConnectionRefused()) {
                sb.append("- WARNING: All failures were 'Connection refused' — the preview app likely crashed during testing.\n");
            }

            // List failed tests (limit to 15)
            List<TestResult> failures = input.testResults().stream()
                    .filter(r -> r.getStatus() == TestResultStatus.FAILED || r.getStatus() == TestResultStatus.ERROR)
                    .limit(15)
                    .toList();
            if (!failures.isEmpty()) {
                sb.append("\n### Failed Tests\n");
                for (TestResult tr : failures) {
                    sb.append("- `%s`: %s\n".formatted(
                            tr.getTestMethod(),
                            tr.getFailureMessage() != null ? truncate(tr.getFailureMessage(), 150) : "no details"));
                    allIssueDescriptions.add("[TEST_FAILURE] %s: %s".formatted(
                            tr.getTestMethod(),
                            tr.getFailureMessage() != null ? truncate(tr.getFailureMessage(), 100) : "failed"));
                }
            }
        }

        return sb.toString();
    }

    private String detectLanguage(String filePath) {
        if (filePath == null) return "Other";
        if (filePath.endsWith(".java")) return "Java";
        if (filePath.endsWith(".ts") || filePath.endsWith(".tsx")) return "TypeScript";
        if (filePath.endsWith(".js") || filePath.endsWith(".jsx")) return "JavaScript";
        if (filePath.endsWith(".py")) return "Python";
        if (filePath.endsWith(".kt")) return "Kotlin";
        if (filePath.endsWith(".sql")) return "SQL";
        if (filePath.endsWith(".yaml") || filePath.endsWith(".yml")) return "YAML";
        if (filePath.endsWith(".xml")) return "XML";
        if (filePath.endsWith(".html")) return "HTML";
        if (filePath.endsWith(".css") || filePath.endsWith(".scss")) return "CSS";
        if (filePath.endsWith(".json")) return "JSON";
        return "Other";
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
