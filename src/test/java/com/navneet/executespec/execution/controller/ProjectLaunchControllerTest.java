package com.navneet.executespec.execution.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * RR-693 — terminal launch states (CANCELLED, and the RR-345 PARTIAL) must be classified terminal
 * on the overview, not counted as active. RR-694 — a GitHub owner's mere existence is not
 * permission to create; the pre-flight fails closed and validates owner syntax.
 */
@ExtendWith(MockitoExtension.class)
class ProjectLaunchControllerTest {

  @Mock FeatureFlagService featureFlags;
  @Mock ExecutionRunRepository execRunRepo;
  @Mock ExecutionSolutionRepository solutionRepo;
  @Mock ProjectRepository projectRepo;
  @Mock ProjectMemberRepository projectMemberRepo;
  @Mock OrgRepository orgRepo;
  @Mock AppUserRepository appUserRepo;
  @Mock TierConfigRepository tierConfigRepo;
  @Mock RestTemplate integrationRestTemplate;
  @Mock com.navneet.executespec.state.repository.PipelineRunRepository pipelineRunRepo;
  @Mock com.navneet.executespec.project.pr.PullRequestRecordRepository prRepo;
  @Mock AppUserDetails principal;

  @InjectMocks ProjectLaunchController controller;

  private final AppUser user = AppUser.builder().id("user-1").tier("TEAM").githubPat("pat").build();

  @BeforeEach
  void enable() {
    TierConfig tier = new TierConfig();
    tier.setTier("TEAM");
    tier.setExecutionViewEnabled(true);
    when(principal.getUser()).thenReturn(user);
    when(featureFlags.isEnabled("execution.view.enabled")).thenReturn(true);
    when(tierConfigRepo.findById("TEAM")).thenReturn(Optional.of(tier));
    // orgId null on both user + project → org match, so no ProjectMember lookup is needed.
    when(projectRepo.findById("project-1"))
        .thenReturn(Optional.of(Project.builder().id("project-1").build()));
  }

  private static ExecutionRun run(String status) {
    return ExecutionRun.builder().id("e-" + status).projectId("project-1").status(status).build();
  }

  @Test
  void overview_classifiesCancelledAndPartialAsTerminal() {
    Page<ExecutionRun> page =
        new PageImpl<>(
            List.of(
                run("COMPLETE"),
                run("PARTIAL"),
                run("FAILED"),
                run("ABORTED"),
                run("CANCELLED"),
                run("EXECUTING"),
                run("PAUSED")));
    when(execRunRepo.findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(eq("project-1"), any()))
        .thenReturn(page);
    when(solutionRepo.findByExecutionRun_IdOrderBySequenceNumberAsc(any())).thenReturn(List.of());

    var overview = controller.overview("project-1", principal).getBody();

    // RR-345/RR-693 — COMPLETE + PARTIAL are completed; FAILED + ABORTED + CANCELLED are failed
    // (CANCELLED no longer inflates active); only EXECUTING + PAUSED remain active.
    assertThat(overview.completedLaunches()).isEqualTo(2);
    assertThat(overview.failedLaunches()).isEqualTo(3);
    assertThat(overview.activeLaunches()).isEqualTo(2);
  }

  @Test
  void validateOwner_rejectsMalformedOwnerSyntax() {
    var result = controller.validateOwner("project-1", ownerReq("bad!!name"), principal).getBody();
    assertThat(result.canCreate()).isFalse();
    assertThat(result.reason()).isEqualTo("Invalid GitHub owner name");
  }

  @Test
  void validateOwner_ownAccountIsVerified() {
    when(appUserRepo.findById("user-1")).thenReturn(Optional.of(user));
    when(integrationRestTemplate.exchange(
            eq("https://api.github.com/user"), eq(HttpMethod.GET), any(), eq(Map.class)))
        .thenReturn(ResponseEntity.<Map>ok(Map.of("login", "myself")));

    var result = controller.validateOwner("project-1", ownerReq("myself"), principal).getBody();

    assertThat(result.canCreate()).isTrue();
  }

  @Test
  void validateOwner_existingForeignAccountIsNotCreateAuthority() {
    // RR-694 — the caller is authenticated as "myself" but asked about "someone-else". Account
    // existence must NOT grant canCreate=true; with no org-admin membership it fails closed.
    when(appUserRepo.findById("user-1")).thenReturn(Optional.of(user));
    when(integrationRestTemplate.exchange(
            eq("https://api.github.com/user"), eq(HttpMethod.GET), any(), eq(Map.class)))
        .thenReturn(ResponseEntity.<Map>ok(Map.of("login", "myself")));
    when(integrationRestTemplate.exchange(
            eq("https://api.github.com/user/memberships/orgs/someone-else"),
            eq(HttpMethod.GET),
            any(),
            eq(Map.class)))
        .thenThrow(
            org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Not Found",
                org.springframework.http.HttpHeaders.EMPTY,
                new byte[0],
                null));

    var result =
        controller.validateOwner("project-1", ownerReq("someone-else"), principal).getBody();

    assertThat(result.canCreate()).isFalse();
    assertThat(result.reason()).contains("Cannot confirm repository-create permission");
  }

  private static ProjectLaunchController.ValidateOwnerRequest ownerReq(String owner) {
    return new ProjectLaunchController.ValidateOwnerRequest(owner);
  }
}
