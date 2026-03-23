package com.navneet.executespec.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * One-time startup backfill: extracts run_title and run_summary from plan_json
 * for existing runs that were created before V36.
 * Runs once on boot, processes only runs where run_title IS NULL but plan_json exists.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunTitleBackfillService {

    private final PipelineRunRepository runRepo;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfillTitles() {
        var runs = runRepo.findRunsWithoutTitle();
        if (runs.isEmpty()) return;

        log.info("Backfilling run titles for {} runs", runs.size());
        int updated = 0;

        for (var run : runs) {
            try {
                var json = objectMapper.readTree(run.getPlanJson());

                String summary = json.has("plan_summary")
                        ? json.get("plan_summary").asText(null) : null;
                String title = json.has("plan_title")
                        ? json.get("plan_title").asText(null) : null;

                if (summary != null) {
                    run.setRunSummary(summary);
                }
                if (title != null) {
                    run.setRunTitle(title);
                } else if (summary != null) {
                    // Old plans don't have plan_title — derive from summary
                    run.setRunTitle(extractTitle(summary));
                }
                updated++;
            } catch (Exception e) {
                log.warn("Failed to backfill title for run {}: {}", run.getRunId(), e.getMessage());
            }
        }

        runRepo.saveAll(runs);
        log.info("Backfilled {} run titles successfully", updated);
    }

    /**
     * Extract a short title (up to 8 words) from the plan summary.
     * Trims at the first sentence boundary or clause separator.
     */
    static String extractTitle(String summary) {
        if (summary == null || summary.isBlank()) return null;

        // Trim to first sentence or clause
        String s = summary.split("[.;—]")[0].trim();
        String[] words = s.split("\\s+");
        if (words.length <= 8) return s;
        return String.join(" ", Arrays.copyOf(words, 8));
    }
}
