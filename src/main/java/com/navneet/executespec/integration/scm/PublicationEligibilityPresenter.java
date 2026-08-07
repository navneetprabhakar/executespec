package com.navneet.executespec.integration.scm;

import java.util.LinkedHashSet;
import java.util.List;

import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-side presenter for {@code GET /api/runs/{id}/publication-eligibility}.
 *
 * <p>The deterministic gate verdict comes unchanged from {@link
 * PublicationAdmissionService#evaluate(PipelineRun)} — this class does NOT alter eligibility,
 * status, codes, or any existing field semantics. It only adds presentation-side acknowledgment of
 * durable created-PR evidence so a BLOCKED re-publication verdict (for example the stale-generation
 * gate) does not read as a contradiction next to a visibly merged PR: the client can say "already
 * published" while the gate honestly says a NEW publication is blocked.
 *
 * <p>Published evidence is durable only: {@code pull_request_record} rows for this run, falling
 * back to the run row's persisted PR URL ({@code scmPrUrl}, then legacy {@code githubPrUrl}) for
 * pre-V169 runs that never wrote a PR record. Evidence lookup is fail-soft — an acknowledgment read
 * failure must never hide the gate verdict.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PublicationEligibilityPresenter {

  private final PublicationAdmissionService publicationAdmissionService;
  private final PullRequestRecordRepository pullRequestRecordRepository;

  public Response present(PipelineRun run) {
    PublicationAdmissionService.Eligibility eligibility = publicationAdmissionService.evaluate(run);
    List<String> publishedPrUrls = publishedPrUrls(run);
    return Response.from(eligibility, publishedPrUrls);
  }

  private List<String> publishedPrUrls(PipelineRun run) {
    if (run == null || run.getRunId() == null || run.getRunId().isBlank()) {
      return List.of();
    }
    LinkedHashSet<String> urls = new LinkedHashSet<>();
    try {
      for (PullRequestRecord record :
          pullRequestRecordRepository.findByRunIdOrderByCreatedAtAsc(run.getRunId())) {
        String url = record.getPrUrl();
        if (url != null && !url.isBlank()) {
          urls.add(url);
        }
      }
    } catch (RuntimeException lookupFailure) {
      // Presentation-side acknowledgment only: fall through to the run row's durable URL.
      log.warn(
          "PR-record evidence lookup failed for run {}: {}",
          run.getRunId(),
          lookupFailure.getMessage());
    }
    if (urls.isEmpty()) {
      String fallback = firstNonBlank(run.getScmPrUrl(), run.getGithubPrUrl());
      if (fallback != null) {
        urls.add(fallback);
      }
    }
    return List.copyOf(urls);
  }

  @SuppressWarnings("deprecation") // legacy githubPrUrl remains durable evidence for old runs
  private static String firstNonBlank(String preferred, String legacy) {
    if (preferred != null && !preferred.isBlank()) return preferred;
    if (legacy != null && !legacy.isBlank()) return legacy;
    return null;
  }

  /**
   * Additive response contract: the first nine components are byte-for-byte the existing {@link
   * PublicationAdmissionService.Eligibility} fields (same names, same values); {@code
   * alreadyPublished} and {@code publishedPrUrls} are the new acknowledgment fields.
   */
  public record Response(
      boolean eligible,
      String eligibility,
      String code,
      String blockingGate,
      String reason,
      boolean retryable,
      String remediation,
      String evidenceWorkspaceGeneration,
      String currentWorkspaceGeneration,
      boolean alreadyPublished,
      List<String> publishedPrUrls) {

    static Response from(
        PublicationAdmissionService.Eligibility eligibility, List<String> publishedPrUrls) {
      List<String> urls = publishedPrUrls == null ? List.of() : List.copyOf(publishedPrUrls);
      return new Response(
          eligibility.eligible(),
          eligibility.eligibility(),
          eligibility.code(),
          eligibility.blockingGate(),
          eligibility.reason(),
          eligibility.retryable(),
          eligibility.remediation(),
          eligibility.evidenceWorkspaceGeneration(),
          eligibility.currentWorkspaceGeneration(),
          !urls.isEmpty(),
          urls);
    }
  }
}
