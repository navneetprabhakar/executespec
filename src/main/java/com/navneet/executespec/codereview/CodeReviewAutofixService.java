package com.navneet.executespec.codereview;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.codereview.entity.CodeReviewIssue;
import com.navneet.executespec.codereview.entity.CodeReviewScan;
import com.navneet.executespec.finding.Finding;
import com.navneet.executespec.finding.FindingPatchSubmitter;
import com.navneet.executespec.finding.FindingType;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 4 (FindingPatchSubmitter rollout) — dispatches a patch-mode run that fixes every OPEN issue
 * of one severity bucket from a {@link CodeReviewScan}. The {@code CodeReviewAgent} produces a
 * markdown report; this service makes that report actionable.
 *
 * <p>Per-severity batching is deliberate: a single PR-per-severity is easier for reviewers to grade
 * than "300 issues across 4 categories in one PR", and it lets customers prioritise — landing the
 * CRITICAL bucket fast while MINOR / INFO follow on a slower cadence. The user prompt embeds up to
 * {@link #MAX_ISSUES_IN_PROMPT} issues verbatim (file + line + message + suggested-fix); over-cap,
 * the agent gets a "long tail" hint so it knows there's more to come in a follow-up run.
 *
 * <p>Risk control: build-mode-style smoke is not yet integrated into patch mode (the agent runs
 * tests via {@code runTests} per-iteration but there's no terminal build gate). Severity buckets
 * ship cleanly because the diff is bounded by the number of issues (≤ MAX_ISSUES_IN_PROMPT ×
 * per-issue edit), but the auto-pushed PR is opened as a draft so the user can grade before
 * merging.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodeReviewAutofixService {

  /** Allowed severity values from the {@code CodeReviewIssue.severity} contract. */
  private static final Set<String> ALLOWED_SEVERITIES =
      Set.of("CRITICAL", "MAJOR", "MINOR", "INFO");

  /** Hard cap on issues embedded in the user prompt — keeps the message under ~7 KB. */
  private static final int MAX_ISSUES_IN_PROMPT = 25;

  /** Cap on suggested-fix snippet per issue so a few large suggestions don't drown the prompt. */
  private static final int SUGGESTED_FIX_PER_ISSUE_CAP = 600;

  private final CodeReviewService scanService;
  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepoRepository repoRepo;
  private final FindingPatchSubmitter submitter;

  public FindingPatchSubmitter.SubmitResult dispatchFixForSeverity(
      UUID scanId, String severityRaw, AppUser user) {

    String severity = severityRaw == null ? "" : severityRaw.trim().toUpperCase(Locale.ROOT);
    if (!ALLOWED_SEVERITIES.contains(severity)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "severity must be one of " + ALLOWED_SEVERITIES + " — got " + severityRaw);
    }

    CodeReviewScan scan = scanService.findScan(scanId);
    if (scan == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Code review scan not found");
    }
    if (!"COMPLETE".equals(scan.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Scan is in status "
              + scan.getStatus()
              + " — wait for COMPLETE before dispatching autofix.");
    }

    List<CodeReviewIssue> issues =
        scanService.openIssuesFor(scanId).stream()
            .filter(i -> severity.equals(i.getSeverity()))
            .toList();
    if (issues.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Scan has no OPEN " + severity + " issues — nothing to autofix.");
    }

    UUID linkedRepoId = resolveLinkedRepoId(scan.getProjectId(), scan.getRepoFullName());

    String prompt = buildPrompt(scan, severity, issues);

    Finding finding =
        new Finding(
            FindingType.CODE_REVIEW_ISSUE,
            // Combined scanId + severity so dedupe is per (scan, severity).
            scanId + "|" + severity,
            scan.getProjectId(),
            List.of(linkedRepoId),
            prompt,
            // No branchOverride — code-review autofix opens a fresh PR per severity.
            null,
            user.getId(),
            null);

    FindingPatchSubmitter.SubmitResult result = submitter.submit(finding);
    log.info(
        "CodeReviewAutofix dispatch scan={} severity={} issues={} -> run={} ({})",
        scanId,
        severity,
        issues.size(),
        result.runId(),
        result.outcome());
    return result;
  }

  // ── Helpers ─────────────────────────────────────────────────────────────

  private UUID resolveLinkedRepoId(String projectId, String repoFullName) {
    List<ProjectRepoLink> links = linkRepo.findByProjectId(projectId);
    for (ProjectRepoLink l : links) {
      Optional<ProjectRepo> r = repoRepo.findById(l.getRepoId());
      if (r.isPresent() && repoFullName.equalsIgnoreCase(r.get().getGithubRepoFullName())) {
        return r.get().getId();
      }
    }
    throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "Repo "
            + repoFullName
            + " is not linked to project "
            + projectId
            + " — link it first under Settings → Repos.");
  }

  private String buildPrompt(CodeReviewScan scan, String severity, List<CodeReviewIssue> issues) {
    int total = issues.size();
    List<CodeReviewIssue> shown =
        issues.size() > MAX_ISSUES_IN_PROMPT
            ? new ArrayList<>(issues.subList(0, MAX_ISSUES_IN_PROMPT))
            : issues;

    StringBuilder sb = new StringBuilder();
    sb.append("Address the ").append(total).append(" OPEN ").append(severity);
    sb.append(" code-review issue").append(total == 1 ? "" : "s");
    sb.append(" identified in scan `")
        .append(scan.getId())
        .append("` against `")
        .append(scan.getRepoFullName())
        .append("` @ ")
        .append(scan.getScannedSha())
        .append(".\n\n");

    sb.append("# Issues\n\n");
    int n = 1;
    for (CodeReviewIssue i : shown) {
      sb.append(n++).append(". `").append(i.getFilePath());
      Integer ls = i.getLineStart();
      Integer le = i.getLineEnd();
      if (ls != null) {
        sb.append(":").append(ls);
        if (le != null && !le.equals(ls)) sb.append("-").append(le);
      }
      sb.append("` — ").append(i.getCategory());
      if (i.getRuleId() != null && !i.getRuleId().isBlank()) {
        sb.append(" / ").append(i.getRuleId());
      }
      sb.append("\n   ");
      sb.append(i.getMessage().replace("\n", "\n   ")).append("\n");
      if (i.getSuggestedFix() != null && !i.getSuggestedFix().isBlank()) {
        String fix = i.getSuggestedFix();
        if (fix.length() > SUGGESTED_FIX_PER_ISSUE_CAP) {
          fix = fix.substring(0, SUGGESTED_FIX_PER_ISSUE_CAP - 20) + "\n   …[truncated]";
        }
        sb.append("   Suggested fix:\n   ```\n   ");
        sb.append(fix.replace("\n", "\n   ")).append("\n   ```\n");
      }
      sb.append("\n");
    }
    if (total > MAX_ISSUES_IN_PROMPT) {
      sb.append("…")
          .append(total - MAX_ISSUES_IN_PROMPT)
          .append(" more ")
          .append(severity)
          .append(
              " issues — focus on the ones above first; a follow-up run can sweep the rest.\n\n");
    }

    sb.append("# How to work\n\n");
    sb.append("1. Group the issues by file. Read each file once + apply every fix in it\n");
    sb.append("   in the same edit pass to keep the diff coherent.\n");
    sb.append("2. The static-analysis suggested fix (when present) is a strong hint, but\n");
    sb.append("   not gospel — sanity-check it against the surrounding code before applying.\n");
    sb.append("3. Run the project's tests via `runTests` to confirm no regression.\n");
    sb.append("4. Commit will be auto-pushed to a fresh `executespec/<runShort>` branch +\n");
    sb.append("   draft PR opened on completion.\n");
    sb.append("5. Call completeTask once every issue above has been addressed.\n\n");

    sb.append("# Constraints\n\n");
    sb.append("- One PR per severity bucket — do NOT mix in fixes for other severities or\n");
    sb.append("  for issues outside this scan.\n");
    sb.append("- Do NOT touch tests unless an issue's message explicitly calls for one.\n");
    sb.append("- Do NOT reformat / reorganise unrelated code in the same files.\n");
    sb.append("- Mark the dispatched PR as DRAFT (not Ready for Review) — the scan author\n");
    sb.append("  reviews before merge.\n");
    return sb.toString();
  }
}
