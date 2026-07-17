package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ReviewerMutationScopeTest {

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
}
