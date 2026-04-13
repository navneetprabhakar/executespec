package com.navneet.executespec.project.pr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PullRequestRecordService {

    private final PullRequestRecordRepository repo;

    @Transactional
    public PullRequestRecord recordCreated(
            String runId,
            UUID repoId,
            String repoFullName,
            String prUrl,
            String headBranch,
            String baseBranch) {
        Integer number = extractPrNumber(prUrl);
        if (number == null) {
            log.warn("Cannot extract PR number from URL {} — skipping record", prUrl);
            return null;
        }
        Optional<PullRequestRecord> existing = repo.findByRepoFullNameAndPrNumber(repoFullName, number);
        if (existing.isPresent()) {
            log.debug("PullRequestRecord already exists for {}#{}", repoFullName, number);
            return existing.get();
        }
        PullRequestRecord record = PullRequestRecord.builder()
                .runId(runId)
                .repoId(repoId)
                .repoFullName(repoFullName)
                .prNumber(number)
                .prUrl(prUrl)
                .headBranch(headBranch)
                .baseBranch(baseBranch)
                .status(PullRequestRecord.Status.OPEN)
                .build();
        return repo.save(record);
    }

    /**
     * Flip a stored PR to CLOSED or MERGED in response to a GitHub webhook.
     * No-op when no matching record exists (PR was not created by ExecuteSpec).
     */
    @Transactional
    public void applyWebhookUpdate(
            String repoFullName,
            Integer prNumber,
            boolean merged,
            Instant timestamp) {
        Optional<PullRequestRecord> found = repo.findByRepoFullNameAndPrNumber(repoFullName, prNumber);
        if (found.isEmpty()) {
            log.debug("Webhook for untracked PR {}#{} — ignoring", repoFullName, prNumber);
            return;
        }
        PullRequestRecord r = found.get();
        if (merged) {
            r.setStatus(PullRequestRecord.Status.MERGED);
            r.setMergedAt(timestamp != null ? timestamp : Instant.now());
        } else {
            r.setStatus(PullRequestRecord.Status.CLOSED);
            r.setClosedAt(timestamp != null ? timestamp : Instant.now());
        }
        r.setUpdatedAt(Instant.now());
        repo.save(r);
        log.info("PR {}#{} updated via webhook -> {}", repoFullName, prNumber, r.getStatus());
    }

    public List<PullRequestRecord> listForRun(String runId) {
        return repo.findByRunIdOrderByCreatedAtAsc(runId);
    }

    /** Extracts N from URLs like {@code https://github.com/owner/repo/pull/42}. */
    public static Integer extractPrNumber(String prUrl) {
        if (prUrl == null) return null;
        int idx = prUrl.lastIndexOf("/pull/");
        if (idx < 0) return null;
        String tail = prUrl.substring(idx + 6);
        int slash = tail.indexOf('/');
        String num = slash > 0 ? tail.substring(0, slash) : tail;
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
