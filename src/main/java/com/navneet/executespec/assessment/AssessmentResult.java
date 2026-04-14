package com.navneet.executespec.assessment;

import java.util.List;

/**
 * Pilot R4 — output of {@code FeatureAssessmentService}. Persisted as JSON on
 * {@code pipeline_run.assessment_result} and surfaced to the user before plan
 * approval. Also injected into the planner prompt so the plan respects
 * cross-repo constraints discovered during assessment.
 */
public record AssessmentResult(
        String summary,
        String complexity,           // S | M | L
        List<String> filesAffected,
        List<String> newFiles,
        List<String> risks,
        List<String> crossRepoDeps,
        List<String> repoFullNames   // repos considered during assessment
) {}
