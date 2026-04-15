package com.navneet.executespec.project.pr;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.repo.GitHubRepoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Pilot R5 — list PRs opened by a single run. Auth is inherited from the
 * global security config (authenticated users only); ownership checks are
 * intentionally loose here because the record only exposes the PR URL which
 * GitHub will re-authorise on click.
 */
@RestController
@RequiredArgsConstructor
public class PullRequestController {

    private final PullRequestRecordService service;
    private final GitHubRepoService gitHub;

    public record PrSummary(
            String repoFullName,
            Integer prNumber,
            String prUrl,
            String status,
            String headBranch,
            String baseBranch,
            String createdAt,
            String mergedAt) {
        static PrSummary from(PullRequestRecord r) {
            return new PrSummary(
                    r.getRepoFullName(),
                    r.getPrNumber(),
                    r.getPrUrl(),
                    r.getStatus().name(),
                    r.getHeadBranch(),
                    r.getBaseBranch(),
                    r.getCreatedAt() != null ? r.getCreatedAt().toString() : null,
                    r.getMergedAt() != null ? r.getMergedAt().toString() : null);
        }
    }

    @GetMapping("/api/runs/{runId}/prs")
    public ResponseEntity<List<PrSummary>> listPrsForRun(@PathVariable String runId) {
        List<PrSummary> out = service.listForRun(runId).stream()
                .map(PrSummary::from)
                .toList();
        return ResponseEntity.ok(out);
    }

    /**
     * Inline PR snapshot (title + body + diff preview) for the project dashboard
     * to render expanded PR cards without making the user leave for GitHub.
     * Uses the caller's stored GitHub PAT.
     */
    @GetMapping("/api/prs/snapshot")
    public ResponseEntity<GitHubRepoService.PullRequestSnapshot> snapshot(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestParam("repo") String repoFullName,
            @RequestParam("number") int prNumber,
            @RequestParam(value = "maxChars", defaultValue = "4000") int maxChars) {
        String pat = userDetails != null ? userDetails.getUser().getGithubPat() : null;
        var snap = gitHub.getPullRequestSnapshot(repoFullName, prNumber, pat, maxChars);
        if (snap == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(snap);
    }
}
