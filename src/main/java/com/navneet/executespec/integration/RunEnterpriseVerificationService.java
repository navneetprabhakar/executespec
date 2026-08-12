package com.navneet.executespec.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.reviewer.model.IssueCategory;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.reviewer.model.ReviewIssue;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Runs final project standards, invariant, and contract checks on accepted disposable bytes. */
@Component
@RequiredArgsConstructor
@Slf4j
class RunEnterpriseVerificationService {

  private final FeatureFlagService featureFlags;
  private final AcceptedGenerationVerificationRunner acceptedVerification;
  private final RunStandardsEnforcer standards;
  private final RunInvariantEnforcer invariants;
  private final RunBreakingChangeDetector breakingChanges;

  List<ReviewIssue> verify(ExecutionContext context, String projectId, List<UUID> selectedRepoIds) {
    List<ReviewIssue> issues = new ArrayList<>();
    if (projectId == null) return issues;
    if (featureFlags.isEnabled("standards.enforcement.enabled")) {
      try {
        var report =
            acceptedVerification.execute(
                context,
                "STANDARDS",
                disposable ->
                    standards.runStandardsEnforcement(disposable, projectId, selectedRepoIds));
        issues.addAll(RunStandardsEnforcer.toReviewIssues(report));
      } catch (Exception failure) {
        log.warn(
            "Run {} standards enforcement unavailable: {}",
            context.runId(),
            failure.getClass().getSimpleName());
        issues.add(
            unavailable(
                "SYNTH-STANDARD-UNAVAILABLE-RUN",
                IssueCategory.CODE_QUALITY,
                "Configured engineering standards could not be verified.",
                "Restore standards enforcement and rerun."));
      }
    }
    try {
      issues.addAll(
          acceptedVerification.execute(
              context,
              "INVARIANTS",
              disposable ->
                  invariants.enforce(context.runId(), projectId, disposable.workspaceRoot())));
    } catch (Exception failure) {
      log.warn(
          "Run {} architecture invariant enforcement unavailable: {}",
          context.runId(),
          failure.getClass().getSimpleName());
      issues.add(
          unavailable(
              "SYNTH-INVARIANT-UNAVAILABLE-RUN",
              IssueCategory.CROSS_TASK_MISMATCH,
              "Configured architecture invariants could not be verified.",
              "Restore invariant enforcement and rerun."));
    }
    if (featureFlags.isEnabled("contracts.breaking-change.enabled")) {
      try {
        issues.addAll(
            acceptedVerification.execute(
                context,
                "BREAKING_CHANGE",
                disposable -> breakingChanges.detect(disposable, projectId, selectedRepoIds)));
      } catch (Exception failure) {
        log.warn(
            "Run {} breaking-change detection skipped: {}", context.runId(), failure.getMessage());
      }
    }
    return issues;
  }

  private static ReviewIssue unavailable(
      String id, IssueCategory category, String description, String fix) {
    return ReviewIssue.builder()
        .id(id)
        .severity(IssueSeverity.CRITICAL)
        .category(category)
        .description(description)
        .fixInstruction(fix)
        .autoFixed(false)
        .fixSucceeded(false)
        .build();
  }
}
