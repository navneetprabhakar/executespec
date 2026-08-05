package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ReviewerMutationScopeTest {

  @Test
  void readOnlyAttestationAllowsExactlyOneBuildProjectCall() {
    ReviewerMutationScope.withSingleBuildProject(
        () -> {
          org.assertj.core.api.Assertions.assertThat(ReviewerMutationScope.validateBuildProject())
              .isNull();
          org.assertj.core.api.Assertions.assertThat(ReviewerMutationScope.validateBuildProject())
              .contains("already executed")
              .contains("completeTask");
          return null;
        });

    org.assertj.core.api.Assertions.assertThat(ReviewerMutationScope.validateBuildProject())
        .isNull();
  }

  private static final ReviewerMutationScope.Policy BROWNFIELD =
      new ReviewerMutationScope.Policy(
          true,
          List.of("repos/api"),
          List.of("src/AuthService.java", "repos/api/src/AuthServiceTest.java"),
          false);

  @Test
  void rejectsWritesOutsideAdmittedRepositoryRoot() {
    String error =
        ReviewerMutationScope.withPolicy(
            BROWNFIELD,
            () -> ReviewerMutationScope.validateWriteFile("package.json", false, false));

    assertThat(error).contains("outside admitted repository roots");
  }

  @Test
  void rejectsBuildManifestFabricationInsideRepositoryRoot() {
    String error =
        ReviewerMutationScope.withPolicy(
            BROWNFIELD,
            () -> ReviewerMutationScope.validateWriteFile("repos/api/package.json", false, false));

    assertThat(error).contains("cannot create or wholesale-replace build manifest");
  }

  @Test
  void allowsExistingFileEditAndPlanApprovedNewTest() {
    String edit =
        ReviewerMutationScope.withPolicy(
            BROWNFIELD,
            () -> ReviewerMutationScope.validateEditFile("repos/api/src/AuthService.java"));
    String plannedNewFile =
        ReviewerMutationScope.withPolicy(
            BROWNFIELD,
            () ->
                ReviewerMutationScope.validateWriteFile(
                    "repos/api/src/AuthServiceTest.java", false, false));

    assertThat(edit).isNull();
    assertThat(plannedNewFile).isNull();
  }

  @Test
  void rejectsUnplannedNewFileButLeavesGreenfieldUnrestricted() {
    String brownfield =
        ReviewerMutationScope.withPolicy(
            BROWNFIELD,
            () -> ReviewerMutationScope.validateWriteFile("repos/api/src/Fake.java", false, false));
    String greenfield =
        ReviewerMutationScope.withPolicy(
            new ReviewerMutationScope.Policy(false, List.of(), List.of(), false),
            () -> ReviewerMutationScope.validateWriteFile("package.json", false, false));

    assertThat(brownfield).contains("unplanned file");
    assertThat(greenfield).isNull();
  }

  @Test
  void disablesShellOnlyInsideBrownfieldReviewerScope() {
    String brownfield =
        ReviewerMutationScope.withPolicy(BROWNFIELD, ReviewerMutationScope::validateShell);
    String outside = ReviewerMutationScope.validateShell();

    assertThat(brownfield).contains("shell is disabled");
    assertThat(outside).isNull();
  }

  // ---- RR-1543: containment must be evaluated on the canonical path ------------------------

  @Test
  void dotSegmentEscapeToSiblingRepositoryIsRejected() {
    for (String escaping :
        List.of(
            "repos/api/../web/x.java",
            "repos/api/./../web/x.java",
            "./repos/api/../../etc/x",
            "repos/api/src/../../web/x.java")) {
      String write =
          ReviewerMutationScope.withPolicy(
              BROWNFIELD, () -> ReviewerMutationScope.validateWriteFile(escaping, true, true));
      String edit =
          ReviewerMutationScope.withPolicy(
              BROWNFIELD, () -> ReviewerMutationScope.validateEditFile(escaping));
      org.assertj.core.api.Assertions.assertThat(write).as(escaping).isNotNull();
      org.assertj.core.api.Assertions.assertThat(edit).as(escaping).isNotNull();
    }
  }

  @Test
  void absolutePathIsRejected() {
    String error =
        ReviewerMutationScope.withPolicy(
            BROWNFIELD, () -> ReviewerMutationScope.validateEditFile("/etc/passwd"));
    org.assertj.core.api.Assertions.assertThat(error).isNotNull();
  }

  @Test
  void redundantDotSegmentsInsideTheRootStillResolveInside() {
    String error =
        ReviewerMutationScope.withPolicy(
            BROWNFIELD,
            () -> ReviewerMutationScope.validateEditFile("repos/api/src/../src/Main.java"));
    org.assertj.core.api.Assertions.assertThat(error).isNull();
  }

  @Test
  void approvedBasenameDoesNotAuthorizeThatFilenameElsewhere() {
    // "repos/api/src/AuthServiceTest.java" is approved; the same BASENAME in another directory
    // inside the same root must NOT inherit that approval.
    String error =
        ReviewerMutationScope.withPolicy(
            BROWNFIELD,
            () ->
                ReviewerMutationScope.validateWriteFile(
                    "repos/api/other/AuthServiceTest.java", false, false));
    org.assertj.core.api.Assertions.assertThat(error).contains("cannot create an unplanned file");
  }

  // ---- RR-1542: destructive tools are inside the scope -------------------------------------

  @Test
  void deleteOutsideAdmittedRootIsRejectedAndInsideIsAllowed() {
    org.assertj.core.api.Assertions.assertThat(
            ReviewerMutationScope.withPolicy(
                BROWNFIELD, () -> ReviewerMutationScope.validateDelete("repos/web/x.java")))
        .contains("outside admitted repository roots");
    org.assertj.core.api.Assertions.assertThat(
            ReviewerMutationScope.withPolicy(
                BROWNFIELD, () -> ReviewerMutationScope.validateDelete("repos/api/x.java")))
        .isNull();
  }

  @Test
  void moveIsRejectedWhenEitherEndpointLeavesTheAdmittedRoot() {
    org.assertj.core.api.Assertions.assertThat(
            ReviewerMutationScope.withPolicy(
                BROWNFIELD,
                () -> ReviewerMutationScope.validateMove("repos/api/a.java", "repos/web/a.java")))
        .isNotNull();
    org.assertj.core.api.Assertions.assertThat(
            ReviewerMutationScope.withPolicy(
                BROWNFIELD,
                () -> ReviewerMutationScope.validateMove("repos/web/a.java", "repos/api/a.java")))
        .isNotNull();
    org.assertj.core.api.Assertions.assertThat(
            ReviewerMutationScope.withPolicy(
                BROWNFIELD,
                () -> ReviewerMutationScope.validateMove("repos/api/a.java", "repos/api/b.java")))
        .isNull();
  }

  @Test
  void backgroundJobIsRefusedWheneverShellIs() {
    org.assertj.core.api.Assertions.assertThat(
            ReviewerMutationScope.withPolicy(
                BROWNFIELD, ReviewerMutationScope::validateBackgroundJob))
        .isEqualTo(
            ReviewerMutationScope.withPolicy(BROWNFIELD, ReviewerMutationScope::validateShell));
    org.assertj.core.api.Assertions.assertThat(ReviewerMutationScope.validateBackgroundJob())
        .isNull();
  }

  @Test
  void nonBrownfieldPolicyLeavesDestructiveToolsUnrestricted() {
    ReviewerMutationScope.Policy greenfield =
        new ReviewerMutationScope.Policy(false, List.of(), List.of(), false);
    org.assertj.core.api.Assertions.assertThat(
            ReviewerMutationScope.withPolicy(
                greenfield, () -> ReviewerMutationScope.validateDelete("anything/at/all.java")))
        .isNull();
    org.assertj.core.api.Assertions.assertThat(
            ReviewerMutationScope.withPolicy(
                greenfield, () -> ReviewerMutationScope.validateMove("a.java", "b.java")))
        .isNull();
  }

  // ---- RR-272 / RR-1687: revocation refuses writes on BOTH brownfield and greenfield ----------

  private static final ReviewerMutationScope.Policy GREENFIELD =
      new ReviewerMutationScope.Policy(false, List.of(), List.of(), false);

  @Test
  void revokedLeaseRefusesAnOtherwiseAllowedGreenfieldWrite() {
    // Greenfield normally admits any write; once the supervising thread revokes the lease, the same
    // write is refused — this is the authority RR-1687 said greenfield passes did not have.
    ReviewerMutationScope.Lease lease = new ReviewerMutationScope.Lease();
    String allowedBefore =
        ReviewerMutationScope.withPolicy(
            GREENFIELD,
            lease,
            () -> ReviewerMutationScope.validateWriteFile("src/App.java", true, true));
    lease.revoke();
    String refusedAfter =
        ReviewerMutationScope.withPolicy(
            GREENFIELD,
            lease,
            () -> ReviewerMutationScope.validateWriteFile("src/App.java", true, true));

    org.assertj.core.api.Assertions.assertThat(allowedBefore).isNull();
    org.assertj.core.api.Assertions.assertThat(refusedAfter).contains("revoked");
  }

  @Test
  void revokedLeaseRefusesAnOtherwiseAllowedBrownfieldEditAndEveryMutation() {
    ReviewerMutationScope.Lease lease = new ReviewerMutationScope.Lease();
    String editBefore =
        ReviewerMutationScope.withPolicy(
            BROWNFIELD,
            lease,
            () -> ReviewerMutationScope.validateEditFile("repos/api/src/AuthService.java"));
    lease.revoke();

    org.assertj.core.api.Assertions.assertThat(editBefore).isNull();
    org.assertj.core.api.Assertions.assertThat(
            ReviewerMutationScope.withPolicy(
                BROWNFIELD,
                lease,
                () -> ReviewerMutationScope.validateEditFile("repos/api/src/AuthService.java")))
        .contains("revoked");
    org.assertj.core.api.Assertions.assertThat(
            ReviewerMutationScope.withPolicy(
                BROWNFIELD,
                lease,
                () ->
                    ReviewerMutationScope.validateWriteFile(
                        "repos/api/src/New.java", false, false)))
        .contains("revoked");
    org.assertj.core.api.Assertions.assertThat(
            ReviewerMutationScope.withPolicy(
                BROWNFIELD,
                lease,
                () -> ReviewerMutationScope.validateDelete("repos/api/src/AuthService.java")))
        .contains("revoked");
    org.assertj.core.api.Assertions.assertThat(
            ReviewerMutationScope.withPolicy(
                BROWNFIELD,
                lease,
                () -> ReviewerMutationScope.validateMove("repos/api/a.java", "repos/api/b.java")))
        .contains("revoked");
    org.assertj.core.api.Assertions.assertThat(
            ReviewerMutationScope.withPolicy(
                GREENFIELD, lease, ReviewerMutationScope::validateShell))
        .contains("revoked");
    org.assertj.core.api.Assertions.assertThat(
            ReviewerMutationScope.withPolicy(
                GREENFIELD, lease, ReviewerMutationScope::validateBackgroundJob))
        .contains("revoked");
  }
}
