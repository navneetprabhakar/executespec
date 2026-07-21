package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.project.entity.ProjectRepo;

import org.junit.jupiter.api.Test;

/**
 * RR-457 — the GitHub-only boundary of the code-intelligence pipeline.
 *
 * <p>Bitbucket and LOCAL sources are registered without {@code githubRepoFullName}, so before this
 * boundary existed they were pushed through a pipeline that builds {@code
 * https://github.com/<name>} and read the literal repository "null". These tests pin that such a
 * source is now named and excluded rather than silently mis-read.
 */
class ProjectArchitectureSourceAdmissionTest {

  private static ProjectRepo repo(String provider, String sourceType, String fullName) {
    ProjectRepo r = new ProjectRepo();
    r.setScmProvider(provider);
    r.setSourceType(sourceType);
    r.setGithubRepoFullName(fullName);
    return r;
  }

  @Test
  void githubScmSourceIsSupported() {
    assertThat(
            ProjectArchitectureService.unsupportedSourceReason(
                repo("GITHUB", ProjectRepo.SourceType.SCM.name(), "acme/widget")))
        .isNull();
  }

  @Test
  void bitbucketSourceIsExplicitlyUnsupported() {
    String reason =
        ProjectArchitectureService.unsupportedSourceReason(
            repo("BITBUCKET", ProjectRepo.SourceType.SCM.name(), null));
    assertThat(reason).contains("BITBUCKET").contains("provider-neutral reader");
  }

  @Test
  void localSourceIsExplicitlyUnsupported() {
    String reason =
        ProjectArchitectureService.unsupportedSourceReason(
            repo("GITHUB", ProjectRepo.SourceType.LOCAL.name(), null));
    assertThat(reason).contains("LOCAL");
  }

  @Test
  void githubRowWithNoRepositoryNameIsUnsupported() {
    assertThat(
            ProjectArchitectureService.unsupportedSourceReason(
                repo("GITHUB", ProjectRepo.SourceType.SCM.name(), "  ")))
        .contains("no GitHub repository name");
  }

  @Test
  void exclusionsAreCarriedIntoTheGeneratedDocument() {
    var admission =
        new ProjectArchitectureService.SourceAdmission(
            List.of(), List.of("acme/legacy (BITBUCKET source — no reader)"));
    String rendered = admission.appendExclusionNotice("# Architecture\n\nBody.");
    assertThat(rendered)
        .contains("# Architecture")
        .contains("Sources excluded from this architecture")
        .contains("acme/legacy");
  }

  @Test
  void fullyCoveredDocumentGainsNoCaveat() {
    var admission = new ProjectArchitectureService.SourceAdmission(List.of(), List.of());
    assertThat(admission.appendExclusionNotice("# Architecture")).isEqualTo("# Architecture");
  }
}
