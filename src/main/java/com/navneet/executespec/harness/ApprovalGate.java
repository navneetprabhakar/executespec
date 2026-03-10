package com.navneet.executespec.harness;

import com.navneet.executespec.agent.CostEstimator;
import com.navneet.executespec.agent.CostEstimator.CostBreakdown;
import com.navneet.executespec.agent.model.ReviewReport;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Scanner;

@Component
@Slf4j
@RequiredArgsConstructor
public class ApprovalGate {

    // Single scanner instance — multiple Scanner objects on System.in cause buffering/EOF issues
    private final Scanner scanner = new Scanner(System.in);
    private final CostEstimator costEstimator;

    public boolean requestApproval(TaskPlan plan, PipelineRun run) {
        log.info("");
        log.info("{}", "=".repeat(70));
        log.info("PLAN READY -- {}", plan.getPlanSummary());
        log.info("{}", "=".repeat(70));
        log.info("  Run ID : {}", run.getRunId());
        log.info("  Models : planner={}  worker={}  reviewer={}",
            run.getPlannerModel(), run.getWorkerModel(), run.getReviewerModel());

        // Step 9: Show cost estimate at plan approval
        try {
            CostBreakdown cost = costEstimator.estimate(plan);
            log.info("  {}", cost.formatted());
        } catch (Exception e) {
            log.debug("Could not compute cost estimate: {}", e.getMessage());
        }
        log.info("");
        log.info("  Tasks:");

        for (int i = 0; i < plan.getTasks().size(); i++) {
            TaskDefinition t = plan.getTasks().get(i);
            String deps = (t.getDependsOn() == null || t.getDependsOn().isEmpty())
                ? "none" : String.join(", ", t.getDependsOn());
            log.info("  [{}] {}  [{}]  depends: {}", i + 1, t.getTaskId(), t.getComplexity(), deps);
            log.info("      {}", t.getTitle());
        }

        log.info("");
        log.info("  Approve? [y]es / [n]o / [q]uit: ");

        // use shared scanner field
        String input = scanner.nextLine().trim().toLowerCase();
        log.info("  User input: {}", input);

        return input.equals("y") || input.equals("yes");
    }

    public boolean requestMerge(ReviewReport review) {
        log.info("");
        log.info("{}", "=".repeat(70));
        log.info("REVIEW COMPLETE -- {} (confidence: {})",
            review.getRecommendation(), review.getOverallConfidence());
        log.info("{}", "=".repeat(70));
        log.info("  {}", review.getSummary());
        log.info("");

        boolean hasMergeBlockers = review.getMergeBlockers() != null
            && !review.getMergeBlockers().isEmpty();

        if (hasMergeBlockers) {
            log.warn("  MERGE BLOCKERS:");
            review.getMergeBlockers().forEach(b -> log.warn("     - {}", b));
        } else {
            log.info("  No merge blockers.");
        }

        boolean hasPostMergeSuggestions = review.getPostMergeSuggestions() != null
            && !review.getPostMergeSuggestions().isEmpty();

        if (hasPostMergeSuggestions) {
            log.info("  Post-merge suggestions:");
            review.getPostMergeSuggestions().forEach(s -> log.info("     - {}", s));
        }

        log.info("");
        log.info("  Merge to main? [y]es / [n]o: ");

        // use shared scanner field
        String input = scanner.nextLine().trim().toLowerCase();
        log.info("  User input: {}", input);
        return input.equals("y") || input.equals("yes");
    }
}
