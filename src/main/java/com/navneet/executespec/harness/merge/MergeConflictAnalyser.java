package com.navneet.executespec.harness.merge;

import com.navneet.executespec.state.repository.MergeConflictRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes historical merge conflict data and produces a report
 * that can be injected into the planner prompt to prevent future conflicts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MergeConflictAnalyser {

    private final MergeConflictRepository conflictRepo;

    /**
     * Produces a human-readable conflict report suitable for injection
     * into the planner system prompt.
     *
     * @param topFileLimit max number of conflict-prone files to report
     * @param runLimit     number of recent runs to analyze for stats
     * @return formatted conflict history, or empty string if no data
     */
    public String buildPlannerContext(int topFileLimit, int runLimit) {
        List<Object[]> topFiles = conflictRepo.findTopConflictedFiles(topFileLimit);
        List<Object[]> stats = conflictRepo.getResolutionStats(runLimit);

        if (topFiles.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n## Historical Merge Conflict Data\n\n");
        sb.append("The following files have caused merge conflicts in recent runs:\n");

        for (Object[] row : topFiles) {
            String filePath = (String) row[0];
            long total = ((Number) row[1]).longValue();
            long resolved = ((Number) row[2]).longValue();
            long pct = total > 0 ? (resolved * 100 / total) : 0;
            sb.append(String.format("- %s: %d conflicts (%d%% auto-resolved)%n",
                filePath, total, pct));
        }

        if (!stats.isEmpty()) {
            Object[] stat = stats.get(0);
            long totalConflicts = ((Number) stat[0]).longValue();
            long autoResolved = ((Number) stat[1]).longValue();
            if (totalConflicts > 0) {
                sb.append(String.format("%nOverall auto-resolution rate: %d/%d (%d%%)%n",
                    autoResolved, totalConflicts, autoResolved * 100 / totalConflicts));
            }
        }

        sb.append("\nRECOMMENDATION: Assign files that frequently conflict to a single task, ");
        sb.append("or create explicit depends_on relationships between tasks that share these files.\n");

        return sb.toString();
    }

    /**
     * Returns the top conflict-prone file paths for programmatic use.
     */
    public List<String> getTopConflictedFilePaths(int limit) {
        List<Object[]> rows = conflictRepo.findTopConflictedFiles(limit);
        List<String> paths = new ArrayList<>();
        for (Object[] row : rows) {
            paths.add((String) row[0]);
        }
        return paths;
    }
}
