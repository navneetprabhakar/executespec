package com.navneet.executespec.prreview;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.finding.Finding;
import com.navneet.executespec.finding.FindingPatchSubmitter;
import com.navneet.executespec.finding.FindingType;
import com.navneet.executespec.prreview.entity.PrReview;
import com.navneet.executespec.prreview.entity.PrReviewComment;
import com.navneet.executespec.prreview.repository.PrReviewCommentRepository;
import com.navneet.executespec.prreview.repository.PrReviewRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 1 (FindingPatchSubmitter rollout) — dispatches a patch-mode run to address every
 * comment on a PR review. Owns the adapter logic between {@link PrReview} +
 * {@link PrReviewComment} domain objects and the generic {@link Finding} contract:
 *
 * <ol>
 *   <li>Looks the review + its comments up.
 *   <li>Resolves the project's linked repo for the review's repo, so the patch run's
 *       multi-repo clone path picks up the right workspace.
 *   <li>Calls {@link GitHubRepoService#getPullRequestSnapshot} to discover the PR's
 *       head branch — the commit will be pushed onto this branch via
 *       {@code branchOverride}, so addressing review comments lands on the same PR
 *       instead of opening a new one.
 *   <li>Synthesises a focused user prompt that lists every comment with its file +
 *       line + body so the agent can iterate per finding.
 *   <li>Hands off to {@link FindingPatchSubmitter} which dedupes against any active run
 *       for the same review id and dispatches when needed.
 * </ol>
 *
 * <p>Tier gating: the original PR Review submission already enforced PRO+ tier; this
 * service piggybacks on the existing review row's existence (FREE tier never sees one).
 * For belt-and-braces, the controller checks the user owns the review's project too.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrReviewAutofixService {

  private final PrReviewRepository reviewRepo;
  private final PrReviewCommentRepository commentRepo;
  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepoRepository repoRepo;
  private final GitHubRepoService github;
  private final FindingPatchSubmitter submitter;

  public FindingPatchSubmitter.SubmitResult dispatch(UUID reviewId, AppUser user) {
    PrReview review =
        reviewRepo
            .findById(reviewId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PR review not found"));

    if (!"COMPLETE".equals(review.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Cannot autofix a PR review in status " + review.getStatus() + " — wait for COMPLETE.");
    }
    if ("APPROVE".equals(review.getVerdict())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Verdict is APPROVE — nothing to autofix.");
    }

    List<PrReviewComment> comments =
        commentRepo.findByPrReviewIdOrderByFilePathAscLineNumberAsc(reviewId);
    if (comments.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "PR review has no comments to address.");
    }

    UUID linkedRepoId = resolveLinkedRepoId(review.getProjectId(), review.getRepoFullName());

    GitHubRepoService.PullRequestSnapshot snap =
        github.getPullRequestSnapshot(
            review.getRepoFullName(), review.getPrNumber(), user.getGithubPat(), 0);
    if (snap == null || snap.headBranch() == null || snap.headBranch().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY,
          "GitHub did not return a head branch for "
              + review.getRepoFullName()
              + "#"
              + review.getPrNumber()
              + " — cannot dispatch autofix.");
    }
    if ("closed".equalsIgnoreCase(snap.state()) && !snap.merged()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "PR is closed without merge — autofix would have nowhere to push.");
    }

    String prompt = buildPrompt(review, comments, snap);

    Finding finding =
        new Finding(
            FindingType.PR_REVIEW,
            reviewId.toString(),
            review.getProjectId(),
            List.of(linkedRepoId),
            prompt,
            snap.headBranch(),
            user.getId(),
            null);

    FindingPatchSubmitter.SubmitResult result = submitter.submit(finding);
    log.info(
        "PrReviewAutofix dispatch review={} pr={}#{} branch={} -> run={} ({})",
        reviewId,
        review.getRepoFullName(),
        review.getPrNumber(),
        snap.headBranch(),
        result.runId(),
        result.outcome());
    return result;
  }

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

  /**
   * Produces a focused user prompt that lists every comment with file + line + body. The
   * agent reads it as ground truth: each comment is a finding it must address, the
   * suggested fix (when present) is a hint, and the failing-test capture isn't relevant
   * for review-driven runs (no test infra needed beyond the project's own).
   */
  private String buildPrompt(
      PrReview review,
      List<PrReviewComment> comments,
      GitHubRepoService.PullRequestSnapshot snap) {
    StringBuilder sb = new StringBuilder();
    sb.append("Address the following PR review comments on ")
        .append(review.getRepoFullName())
        .append("#")
        .append(review.getPrNumber())
        .append(".\n\n");
    sb.append("# Context\n\n");
    sb.append("- Repo: ").append(review.getRepoFullName()).append("\n");
    sb.append("- PR: #").append(review.getPrNumber()).append("\n");
    sb.append("- Head branch: ").append(snap.headBranch()).append("\n");
    sb.append("- Base branch: ").append(snap.baseBranch()).append("\n");
    sb.append("- Reviewer verdict: ").append(review.getVerdict()).append("\n");
    if (review.getSummary() != null && !review.getSummary().isBlank()) {
      sb.append("\n# Reviewer summary\n\n").append(review.getSummary()).append("\n");
    }
    sb.append("\n# Comments to address\n\n");
    int n = 1;
    for (PrReviewComment c : comments) {
      sb.append(n++).append(". `").append(c.getFilePath());
      if (c.getLineNumber() != null) sb.append(":").append(c.getLineNumber());
      sb.append("`");
      if (c.getSeverity() != null && !c.getSeverity().isBlank()) {
        sb.append(" — ").append(c.getSeverity());
      }
      if (c.getCategory() != null && !c.getCategory().isBlank()) {
        sb.append(" / ").append(c.getCategory());
      }
      sb.append("\n   ").append(c.getBody().replace("\n", "\n   ")).append("\n\n");
    }
    sb.append("# How to work\n\n");
    sb.append(
        "1. Read each cited file at the cited line. Understand the comment's intent\n");
    sb.append(
        "   — the reviewer is right unless the suggestion is technically incorrect.\n");
    sb.append("2. Apply the minimum edit that addresses the comment.\n");
    sb.append(
        "3. Run the project's tests via `runTests` to confirm no regression. If the\n");
    sb.append(
        "   project doesn't use pytest, fall back to `shell` with the project's test\n");
    sb.append("   runner.\n");
    sb.append(
        "4. Commit will be pushed automatically to the existing PR branch on completion;\n");
    sb.append("   do not branch / push yourself.\n");
    sb.append("5. Call completeTask once every comment has been addressed and verified.\n\n");
    sb.append("# Constraints\n\n");
    sb.append(
        "- Do NOT reply to the comments inline — your job is to produce the fix, not the\n");
    sb.append("  conversation.\n");
    sb.append(
        "- Do NOT touch unrelated files. Reviewers don't want to see drive-by changes.\n");
    sb.append(
        "- Do NOT modify tests unless a comment explicitly says to. Tests may be the\n");
    sb.append("  reviewer's signal of intent.\n");
    return sb.toString();
  }
}
