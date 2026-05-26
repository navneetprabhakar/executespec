package com.navneet.executespec.contracts.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.contracts.detection.ContractDetectionService;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link ContractCoverageController} (E2.3) — verifies the GET cached read + POST
 * rescan paths, project membership gating, and known-gap rendering.
 */
@ExtendWith(MockitoExtension.class)
class ContractCoverageControllerTest {

  @Mock ProjectRepository projectRepository;
  @Mock ProjectRepoRepository repoRepo;
  @Mock ProjectRepoLinkRepository linkRepo;
  @Mock ContractRegistryRepository contractRegistryRepo;
  @Mock ContractDetectionService detectionService;
  @Mock GitHubRepoService gitHub;
  @Mock AppUserRepository userRepo;
  @Mock com.navneet.executespec.project.security.ProjectAccessGuard projectAccessGuard;

  @InjectMocks ContractCoverageController controller;

  String projectId;
  UUID repoId;
  ProjectRepo repo;

  @BeforeEach
  void setUp() {
    projectId = UUID.randomUUID().toString();
    repoId = UUID.randomUUID();
    repo = new ProjectRepo();
    repo.setId(repoId);
    repo.setGithubRepoFullName("acme/widget");
    lenient().when(repoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    lenient()
        .when(linkRepo.findByProjectIdAndRepoId(projectId, repoId))
        .thenReturn(
            Optional.of(
                ProjectRepoLink.builder()
                    .id(UUID.randomUUID())
                    .projectId(projectId)
                    .repoId(repoId)
                    .build()));
  }

  @Test
  void coverage_get_returnsKnownGapsAndPerTypeContractGroups() {
    ContractRegistry openapi =
        ContractRegistry.builder()
            .id(UUID.randomUUID())
            .repoId(repoId)
            .name("widget-api")
            .type(ContractRegistry.ContractType.OPENAPI.name())
            .currentRegistryVersion(1)
            .currentContentHash("abc")
            .detectionMethod(ContractRegistry.DetectionMethod.AUTO_FILE.name())
            .build();
    when(contractRegistryRepo.findByRepoId(repoId)).thenReturn(List.of(openapi));

    ResponseEntity<ContractCoverageController.CoverageResponse> res =
        controller.coverage(projectId, repoId, fakeAuth());

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody().githubRepoFullName()).isEqualTo("acme/widget");
    assertThat(res.getBody().scans())
        .anySatisfy(
            s -> {
              assertThat(s.detector()).isEqualTo("OpenApiFileDetector");
              assertThat(s.contractIds()).hasSize(1);
            });
    assertThat(res.getBody().scans())
        .filteredOn(s -> s.type() == ContractRegistry.ContractType.PROTO)
        .singleElement()
        .satisfies(s -> assertThat(s.contractIds()).isEmpty());
    assertThat(res.getBody().knownGaps())
        .extracting(g -> g.category())
        .contains("grpc-services-in-code", "monorepo-package-scoping");
  }

  @Test
  void coverage_get_rejectsUnauthenticatedCallers() {
    assertThatThrownBy(() -> controller.coverage(projectId, repoId, null))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void coverage_get_rejectsRepoNotLinkedToProject() {
    when(linkRepo.findByProjectIdAndRepoId(projectId, repoId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> controller.coverage(projectId, repoId, fakeAuth()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void coverage_get_rejectsMissingRepoId() {
    when(repoRepo.findById(repoId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> controller.coverage(projectId, repoId, fakeAuth()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void rescan_callsDetectionService_andReturnsPerDetectorSummary() {
    when(detectionService.runForRepo(eq(repoId), any(), any(), any()))
        .thenReturn(
            List.of(
                new ContractDetectionService.DetectionResult(
                    "OpenApiFileDetector",
                    ContractRegistry.ContractType.OPENAPI,
                    List.of("**/openapi.yaml"),
                    ContractDetectionService.DetectionResult.Status.SCANNED,
                    List.of(UUID.randomUUID()),
                    null),
                new ContractDetectionService.DetectionResult(
                    "ProtoFileDetector",
                    ContractRegistry.ContractType.PROTO,
                    List.of("**/*.proto"),
                    ContractDetectionService.DetectionResult.Status.FAILED,
                    List.of(),
                    "buf returned non-zero")));

    ResponseEntity<ContractCoverageController.CoverageResponse> res =
        controller.rescan(projectId, repoId, fakeAuth());

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody().scans()).hasSize(2);
    assertThat(res.getBody().scans().get(1).status())
        .isEqualTo(ContractCoverageController.DetectorSummary.Status.FAILED);
    assertThat(res.getBody().scans().get(1).failureReason()).contains("buf");
  }

  private UsernamePasswordAuthenticationToken fakeAuth() {
    // RL-2026-05-040 follow-up: controller resolves principal(auth) as AppUserDetails (RL-036
    // ProjectAccessGuard wiring); a bare String principal → 401 before validation. Mocked
    // projectAccessGuard no-ops assertCanRead/Write so tests reach the 400/404 path.
    com.navneet.executespec.auth.entity.AppUser u =
        new com.navneet.executespec.auth.entity.AppUser();
    u.setId(UUID.randomUUID().toString());
    u.setOrgId(UUID.randomUUID().toString());
    u.setEmail("ci-test@executespec.local"); // getName() = getUsername() = email;
    // ensureLinkedAndAccessible needs it non-null
    return new UsernamePasswordAuthenticationToken(
        new com.navneet.executespec.auth.security.AppUserDetails(u, null), "n/a");
  }
}
