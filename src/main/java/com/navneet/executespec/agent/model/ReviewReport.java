package com.navneet.executespec.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ReviewReport {

    private String overallConfidence;  // HIGH | MEDIUM | LOW
    private String recommendation;     // MERGE | REWORK | PARTIAL_MERGE
    private String summary;
    private List<TaskReview> taskReviews;
    private List<String> mergeBlockers;
    private List<String> postMergeSuggestions;

    // ── Tier 3 field ─────────────────────────────────────────────────────────
    // Default false: if LLM omits the field, treat as not updated (fail-safe).
    // Set true only when README.md is explicitly confirmed present in the diff.
    @Builder.Default
    private boolean readmeUpdated = false;
}
