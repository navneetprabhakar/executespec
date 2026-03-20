package com.navneet.executespec.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.model.ReviewIssue;
import com.navneet.executespec.agent.model.ReviewReport;
import com.navneet.executespec.agent.model.TaskReview;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.*;
import com.navneet.executespec.state.StateStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ValidationReportService {

    private final ValidationReportRepository repository;
    private final ValidationReportGenerator generator;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final StateStore stateStore;

    /**
     * Main entry point — called after ReviewerAgent completes, before MergeGate.
     * Non-blocking on failure: catches all exceptions and returns Optional.empty().
     */
    public Optional<ValidationReport> generateAndPersist(String runId, ReportInput input) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. Collect structured data from pipeline outputs
            List<ReportIssueDTO> issues = extractIssues(input);
            String issuesJson = objectMapper.writeValueAsString(issues);

            Set<String> languages = extractLanguages(input);
            List<String> checksPassed = extractChecksPassed(input);
            int filesGenerated = countFilesGenerated(input);
            PipelineResult pipelineResult = computeResult(issues, input);

            // 2. Call LLM to generate markdown report
            var llmResult = generator.generate(input);

            // 3. Build and persist entity
            var report = ValidationReport.builder()
                .id(UUID.randomUUID().toString())
                .runId(runId)
                .filesGenerated(filesGenerated)
                .languages(String.join(",", languages))
                .pipelineResult(pipelineResult)
                .issuesJson(issuesJson)
                .checksPassed(String.join(",", checksPassed))
                .reportMarkdown(llmResult.markdown())
                .modelUsed(llmResult.model())
                .inputTokens(llmResult.inputTokens())
                .outputTokens(llmResult.outputTokens())
                .generationTimeMs(System.currentTimeMillis() - startTime)
                .build();

            // Accumulate report tokens into run total for billing accuracy
            try {
                stateStore.addTokensToRun(runId, llmResult.inputTokens(), llmResult.outputTokens());
            } catch (Exception tokenEx) {
                log.warn("Failed to accumulate report tokens to run {}: {}", runId, tokenEx.getMessage());
            }

            // Delete any existing report for this run (rework edge case)
            repository.findByRunId(runId).ifPresent(existing -> repository.delete(existing));
            var saved = repository.save(report);

            // 4. Metrics
            meterRegistry.counter("executespec.report.generated",
                "result", pipelineResult.name()).increment();
            meterRegistry.counter("executespec.report.issues_caught")
                .increment(issues.size());
            Timer.builder("executespec.report.generation_duration")
                .register(meterRegistry)
                .record(Duration.ofMillis(System.currentTimeMillis() - startTime));

            log.info("Validation report generated for run={} issues={} result={} tokens={}+{} time={}ms",
                runId, issues.size(), pipelineResult,
                llmResult.inputTokens(), llmResult.outputTokens(),
                report.getGenerationTimeMs());

            return Optional.of(saved);

        } catch (Exception e) {
            log.error("Failed to generate validation report for run={}: {}", runId, e.getMessage(), e);
            meterRegistry.counter("executespec.report.errors",
                "error_type", e.getClass().getSimpleName()).increment();
            return Optional.empty();
        }
    }

    public Optional<ValidationReportDTO> getByRunId(String runId) {
        return repository.findByRunId(runId).map(this::toDTO);
    }

    public Optional<com.navneet.executespec.report.entity.ValidationReport> findEntityByRunId(String runId) {
        return repository.findByRunId(runId);
    }

    public Optional<ValidationReportShareDTO> getByShareToken(String shareToken) {
        return repository.findByShareToken(shareToken).map(this::toShareDTO);
    }

    public String share(String runId) {
        var report = repository.findByRunId(runId)
            .orElseThrow(() -> new IllegalArgumentException("Report not found for run " + runId));

        if (report.getShareToken() != null) {
            return report.getShareToken();
        }

        String token = generateShareToken();
        report.setShareToken(token);
        report.setSharedAt(Instant.now());
        repository.save(report);

        log.info("Validation report shared: run={} token={}", runId, token);
        meterRegistry.counter("executespec.report.shared").increment();

        return token;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private List<ReportIssueDTO> extractIssues(ReportInput input) {
        List<ReportIssueDTO> issues = new ArrayList<>();

        // Issues from reviewer
        ReviewReport review = input.review();
        if (review != null && review.getTaskReviews() != null) {
            for (TaskReview tr : review.getTaskReviews()) {
                // Review issues (severity-tagged)
                if (tr.getIssues() != null) {
                    for (ReviewIssue ri : tr.getIssues()) {
                        issues.add(new ReportIssueDTO(
                            ri.getDescription(),
                            mapSeverity(ri.getSeverity()),
                            null,
                            "REVIEWER_AGENT",
                            ri.getFile()
                        ));
                    }
                }
                // Boundary violations (task touched files outside its scope)
                if (tr.getBoundaryViolations() != null) {
                    for (String violation : tr.getBoundaryViolations()) {
                        issues.add(new ReportIssueDTO(
                            "Boundary violation in task " + tr.getTaskId() + ": " + violation,
                            "WOULD_FAIL_REVIEW",
                            null,
                            "REVIEWER_AGENT",
                            null
                        ));
                    }
                }
                // Acceptance criteria not met
                if (!tr.isAcceptanceCriteriaMet()) {
                    issues.add(new ReportIssueDTO(
                        "Acceptance criteria not met for task: " + tr.getTaskId(),
                        "WOULD_FAIL_REVIEW",
                        null,
                        "REVIEWER_AGENT",
                        null
                    ));
                }
            }
        }

        // Issues from compiler
        if (input.compileResult() != null && !input.compileResult().passed()
                && !input.compileResult().skipped()) {
            issues.add(new ReportIssueDTO(
                "Compilation failed: " + truncate(input.compileResult().errorSummary(), 200),
                "WOULD_BREAK_BUILD",
                "Auto-fixed by CompilerAgent in " + input.compileResult().attempts() + " attempt(s)",
                "COMPILER_AGENT",
                null
            ));
        }

        // Merge blockers from reviewer
        if (review != null && review.getMergeBlockers() != null) {
            for (String blocker : review.getMergeBlockers()) {
                issues.add(new ReportIssueDTO(
                    blocker,
                    "WOULD_FAIL_REVIEW",
                    null,
                    "REVIEWER_AGENT",
                    null
                ));
            }
        }

        return issues;
    }

    private String mapSeverity(String reviewSeverity) {
        if (reviewSeverity == null) return "WOULD_FAIL_REVIEW";
        return switch (reviewSeverity.toUpperCase()) {
            case "HIGH" -> "WOULD_CAUSE_BUG";
            case "MEDIUM" -> "WOULD_FAIL_REVIEW";
            case "LOW" -> "WOULD_FAIL_LINT";
            default -> "WOULD_FAIL_REVIEW";
        };
    }

    private Set<String> extractLanguages(ReportInput input) {
        Set<String> languages = new LinkedHashSet<>();
        if (input.workerReports() != null) {
            for (WorkerReport wr : input.workerReports()) {
                if (wr.getFilesCreated() != null) {
                    wr.getFilesCreated().forEach(f -> {
                        String lang = detectLanguage(f);
                        if (!"Other".equals(lang)) languages.add(lang);
                    });
                }
                if (wr.getFilesModified() != null) {
                    wr.getFilesModified().forEach(f -> {
                        String lang = detectLanguage(f);
                        if (!"Other".equals(lang)) languages.add(lang);
                    });
                }
            }
        }
        return languages;
    }

    private List<String> extractChecksPassed(ReportInput input) {
        List<String> passed = new ArrayList<>();
        if (input.compileResult() != null && input.compileResult().passed() && !input.compileResult().skipped()) {
            passed.add("COMPILATION");
        }
        if (input.review() != null) {
            boolean hasBlockers = input.review().getMergeBlockers() != null
                && !input.review().getMergeBlockers().isEmpty();
            if (!hasBlockers) {
                passed.add("CODE_REVIEW");
            }
        }
        return passed;
    }

    private int countFilesGenerated(ReportInput input) {
        int count = 0;
        if (input.workerReports() != null) {
            for (WorkerReport wr : input.workerReports()) {
                if (wr.getFilesCreated() != null) count += wr.getFilesCreated().size();
                if (wr.getFilesModified() != null) count += wr.getFilesModified().size();
            }
        }
        return count;
    }

    private PipelineResult computeResult(List<ReportIssueDTO> issues, ReportInput input) {
        if (input.compileResult() != null && !input.compileResult().passed()
                && !input.compileResult().skipped()) {
            return PipelineResult.FAILED;
        }
        if (!issues.isEmpty()) return PipelineResult.PASSED_WITH_FIXES;
        return PipelineResult.PASSED;
    }

    private ValidationReportDTO toDTO(ValidationReport entity) {
        List<ReportIssueDTO> issues = parseIssues(entity.getIssuesJson());
        List<String> languages = entity.getLanguages() != null && !entity.getLanguages().isBlank()
            ? Arrays.asList(entity.getLanguages().split(",")) : List.of();
        List<String> checksPassed = entity.getChecksPassed() != null && !entity.getChecksPassed().isBlank()
            ? Arrays.asList(entity.getChecksPassed().split(",")) : List.of();

        return new ValidationReportDTO(
            entity.getId(),
            entity.getRunId(),
            entity.getFilesGenerated(),
            languages,
            entity.getPipelineResult(),
            issues,
            checksPassed,
            entity.getReportMarkdown(),
            entity.getModelUsed(),
            entity.getCreatedAt(),
            issues.size(),
            countBySeverity(issues, "WOULD_BREAK_BUILD"),
            countBySeverity(issues, "WOULD_FAIL_REVIEW"),
            countBySeverity(issues, "WOULD_CAUSE_BUG"),
            countBySeverity(issues, "WOULD_FAIL_LINT"),
            entity.getShareToken() != null,
            entity.getShareToken() != null
                ? "/reports/shared/" + entity.getShareToken()
                : null
        );
    }

    private ValidationReportShareDTO toShareDTO(ValidationReport entity) {
        List<ReportIssueDTO> issues = parseIssues(entity.getIssuesJson());
        List<String> languages = entity.getLanguages() != null && !entity.getLanguages().isBlank()
            ? Arrays.asList(entity.getLanguages().split(",")) : List.of();
        List<String> checksPassed = entity.getChecksPassed() != null && !entity.getChecksPassed().isBlank()
            ? Arrays.asList(entity.getChecksPassed().split(",")) : List.of();

        return new ValidationReportShareDTO(
            entity.getFilesGenerated(),
            languages,
            entity.getPipelineResult(),
            issues,
            checksPassed,
            entity.getReportMarkdown(),
            entity.getCreatedAt()
        );
    }

    private List<ReportIssueDTO> parseIssues(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse issues JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private int countBySeverity(List<ReportIssueDTO> issues, String severity) {
        return (int) issues.stream()
            .filter(i -> severity.equals(i.severity()))
            .count();
    }

    private String generateShareToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String detectLanguage(String filePath) {
        if (filePath == null) return "Other";
        if (filePath.endsWith(".java")) return "Java";
        if (filePath.endsWith(".ts") || filePath.endsWith(".tsx")) return "TypeScript";
        if (filePath.endsWith(".js") || filePath.endsWith(".jsx")) return "JavaScript";
        if (filePath.endsWith(".py")) return "Python";
        if (filePath.endsWith(".kt")) return "Kotlin";
        if (filePath.endsWith(".sql")) return "SQL";
        return "Other";
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
