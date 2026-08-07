package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.describeReviewerFixes;
import static com.navneet.executespec.integration.RunPipelineSupport.truncate;

import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.report.model.CompileResult;
import com.navneet.executespec.reviewer.ReviewerResult;
import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.reviewer.model.IssueCategory;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.reviewer.model.ReviewIssue;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;

/** Pure validation helper for deterministic and advisory review-issue projection. */
final class ValidationReviewIssueSynthesizer {

  private static final int FINDING_CAP = 5;

  private ValidationReviewIssueSynthesizer() {}

  static ReviewIssue workspaceTestFailure(WorkspaceTestRunner.DetailedResult result) {
    var summary = result.summary();
    String tail = result.outputTail() == null ? "" : result.outputTail().strip();
    if (tail.length() > 1_200) tail = tail.substring(tail.length() - 1_200);
    String description =
        "Workspace test suite executed with "
            + summary.getFailed()
            + " failed and "
            + summary.getErrors()
            + " errored of "
            + summary.getTotalTests()
            + " tests (command: "
            + result.command()
            + ")";
    if (!tail.isBlank()) description += ". Failure output: " + tail;
    return issue(
        "SYNTH-TEST-DIAGNOSTIC",
        IssueSeverity.CRITICAL,
        IssueCategory.LOGIC_ERROR,
        description,
        false);
  }

  /**
   * Honest evidence note for the {@code unprovableForStack} exact-route verification state: the
   * spec declared exact HTTP routes but no inspected repository root contains a Spring MVC mapping
   * source, so deterministic route proof is unavailable for this stack. MINOR by design — this is a
   * ledger note that exact-route proof is unavailable, never a failing verdict.
   */
  /**
   * Disclosure prefix; report-side gating recognizes it by literal (see
   * ValidationReportService.isInformationalDisclosureDescription — cross-pinned by test).
   */
  static final String HTTP_STACK_UNPROVABLE_PREFIX =
      "Exact HTTP route verification is not applicable to this stack";

  static ReviewIssue httpRequirementUnprovableForStack(
      DeterministicHttpRequirementVerifier.Verification verification) {
    StringBuilder description =
        new StringBuilder(
            HTTP_STACK_UNPROVABLE_PREFIX
                + ": no Spring MVC mapping"
                + " sources were found in the inspected repositories, so deterministic exact-route"
                + " proof is unavailable (the routes were neither proven present nor proven"
                + " absent).");
    String required = displayRoutes(verification.required());
    if (!required.isEmpty()) {
      description.append(" Declared required routes (unproven): ").append(required).append(".");
    }
    String forbidden = displayRoutes(verification.forbidden());
    if (!forbidden.isEmpty()) {
      description
          .append(" Declared method-not-allowed constraints (unproven): ")
          .append(forbidden)
          .append(".");
    }
    return issue(
        "SYNTH-HTTP-STACK-UNPROVABLE",
        IssueSeverity.MINOR,
        IssueCategory.COMPLETENESS,
        truncate(description.toString(), 1_200),
        false);
  }

  private static String displayRoutes(
      java.util.Collection<
              com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor.RouteKey>
          routes) {
    if (routes == null) return "";
    return routes.stream()
        .sorted()
        .map(route -> route.method() + " " + route.path())
        .reduce((left, right) -> left + ", " + right)
        .orElse("");
  }

  static List<ReviewIssue> synthesise(
      ReviewerResult reviewResult, CompileResult compile, AccuracyReport accuracy) {
    List<ReviewIssue> out = new ArrayList<>();
    boolean buildFailed =
        (compile != null && !compile.passed() && !compile.skipped())
            || (reviewResult != null && Boolean.FALSE.equals(reviewResult.finalBuildPassed()));
    if (buildFailed) {
      out.add(
          issue(
              "SYNTH-BUILD-1",
              IssueSeverity.CRITICAL,
              IssueCategory.LOGIC_ERROR,
              "Final build failed — code does not compile / package as produced",
              false));
      out.add(
          issue(
              "SYNTH-BUILD-2",
              IssueSeverity.CRITICAL,
              IssueCategory.DEPLOYABILITY,
              "Build artifacts absent — the run cannot be deployed as-is",
              false));
    }
    if (reviewResult != null) addReviewerIssue(out, reviewResult);
    addAccuracyIssues(out, accuracy);
    return out;
  }

  private static void addReviewerIssue(List<ReviewIssue> out, ReviewerResult review) {
    switch (review.overall()) {
      case UNRESOLVED_ISSUES ->
          out.add(
              issue(
                  "SYNTH-REV-1",
                  IssueSeverity.CRITICAL,
                  IssueCategory.LOGIC_ERROR,
                  "Reviewer flagged unresolved issues in wiring/general pass",
                  false));
      case REVIEW_FAILED ->
          out.add(
              issue(
                  "SYNTH-REV-2",
                  IssueSeverity.MINOR,
                  IssueCategory.ERROR_HANDLING,
                  "Reviewer agent errored — review did not complete",
                  false));
      case FIXED_ISSUES ->
          out.add(
              issue(
                  "SYNTH-FIX-1",
                  IssueSeverity.MINOR,
                  IssueCategory.LOGIC_ERROR,
                  describeReviewerFixes(review),
                  true));
      default -> {
        // CLEAN has no synthetic issue.
      }
    }
  }

  private static void addAccuracyIssues(List<ReviewIssue> out, AccuracyReport accuracy) {
    if (accuracy == null) return;
    if (accuracy.hasCitedSpecEvidence()) {
      addBounded(
          out,
          accuracy.getMissingRequirements(),
          "SYNTH-ACC-MISS-",
          IssueCategory.MISSING_FEATURE,
          "Spec requirement not implemented: ");
      addBounded(
          out,
          accuracy.getPartialRequirements(),
          "SYNTH-ACC-PART-",
          IssueCategory.SPEC_MISMATCH,
          "Spec requirement only partially implemented: ");
    }
    if (accuracy.hasCitedEndpointEvidence() && accuracy.getEndpointCoverage() != null) {
      addBounded(
          out,
          accuracy.getEndpointCoverage().getMissing(),
          "SYNTH-ACC-EP-",
          IssueCategory.MISSING_ENDPOINT,
          "Endpoint from spec not implemented: ");
    }
  }

  private static void addBounded(
      List<ReviewIssue> out,
      List<String> findings,
      String idPrefix,
      IssueCategory category,
      String descriptionPrefix) {
    if (findings == null) return;
    int count = 0;
    for (String finding : findings) {
      if (count >= FINDING_CAP) break;
      count++;
      out.add(
          issue(
              idPrefix + count,
              IssueSeverity.MINOR,
              category,
              descriptionPrefix + truncate(finding, 180),
              false));
    }
  }

  private static ReviewIssue issue(
      String id,
      IssueSeverity severity,
      IssueCategory category,
      String description,
      boolean fixed) {
    return ReviewIssue.builder()
        .id(id)
        .severity(severity)
        .category(category)
        .description(description)
        .autoFixed(fixed)
        .fixSucceeded(fixed)
        .build();
  }
}
