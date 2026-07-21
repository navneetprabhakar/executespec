package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.entity.RunCodeMap;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.codeintel.repository.RunCodeMapRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * RR-1586 — reusing the project codemap as a run's repository picture is only honest when the
 * project codemap actually describes the revision this run checked out. Matching on repo name alone
 * let a weeks-old codemap into the planner stamped with a provenance SHA that was never compared.
 */
class RunCodeMapServiceReuseShaTest {

  private static final String REPO_URL = "https://github.com/acme/api";
  private static final String REPO_FULL = "acme/api";
  private static final UUID REPO_ID = UUID.randomUUID();

  private CodeMapGenerator codeMapGenerator;
  private RunCodeMapRepository runCodeMapRepo;
  private ProjectCodeMapRepository projectCodeMapRepo;
  private ProjectRepoRepository projectRepoRepo;
  private RunRepoSnapshotRepository snapshotRepo;
  private GitHubRepoService gitHub;
  private RunCodeMapService service;

  @BeforeEach
  void setUp() {
    codeMapGenerator = mock(CodeMapGenerator.class);
    runCodeMapRepo = mock(RunCodeMapRepository.class);
    projectCodeMapRepo = mock(ProjectCodeMapRepository.class);
    projectRepoRepo = mock(ProjectRepoRepository.class);
    snapshotRepo = mock(RunRepoSnapshotRepository.class);
    gitHub = mock(GitHubRepoService.class);
    service =
        new RunCodeMapService(
            codeMapGenerator,
            runCodeMapRepo,
            projectCodeMapRepo,
            projectRepoRepo,
            snapshotRepo,
            gitHub,
            mock(LlmRouter.class),
            mock(PipelineRunRepository.class),
            mock(ModelResolver.class));

    when(runCodeMapRepo.findByRunId(anyString())).thenReturn(Optional.empty());
    when(projectRepoRepo.findById(REPO_ID))
        .thenReturn(
            Optional.of(
                ProjectRepo.builder()
                    .id(REPO_ID)
                    .scope(ProjectRepo.Scope.USER)
                    .ownerUserId("u1")
                    .githubRepoFullName(REPO_FULL)
                    .githubOwner("acme")
                    .build()));
  }

  private void projectCodeMapAtSha(String sha) {
    ProjectCodeMap pcm =
        ProjectCodeMap.builder()
            .projectId("p1")
            .repoId(REPO_ID)
            .version(7)
            .content("# codemap\n\n**Files**: 12\n")
            .generatedFromSha(sha)
            .build();
    when(projectCodeMapRepo.findByProjectId("p1")).thenReturn(List.of(pcm));
    when(projectCodeMapRepo.findFirstByProjectIdAndRepoIdOrderByVersionDesc("p1", REPO_ID))
        .thenReturn(Optional.of(pcm));
  }

  private void runSnapshotAtSha(String sha) {
    when(snapshotRepo.findByRunId("r1"))
        .thenReturn(
            List.of(
                RunRepoSnapshot.builder()
                    .runId("r1")
                    .repoFullName(REPO_FULL)
                    .branch("main")
                    .commitSha(sha)
                    .build()));
  }

  /** Nothing was reused: the caller must fall through to the regenerate path. */
  private void assertRegenerated(String result) {
    assertThat(result).isNull();
    verify(runCodeMapRepo, never()).save(any());
  }

  @Test
  void equalShaReusesAtZeroLlmCost() {
    projectCodeMapAtSha("aaa111");
    runSnapshotAtSha("aaa111");

    String out = service.generateForRun("r1", REPO_URL, "main", "pat", "p1");

    assertThat(out).contains("# codemap");
    ArgumentCaptor<RunCodeMap> saved = ArgumentCaptor.forClass(RunCodeMap.class);
    verify(runCodeMapRepo).save(saved.capture());
    assertThat(saved.getValue().getGeneratedFromSha()).isEqualTo("aaa111");
    assertThat(saved.getValue().getInputTokens()).isZero();
    verify(codeMapGenerator, never()).generateWithSha(anyString(), anyString(), any());
  }

  @Test
  void divergedShaRegeneratesInsteadOfInjectingStaleCodemap() {
    projectCodeMapAtSha("old000");
    runSnapshotAtSha("new999");
    when(gitHub.diffConfinedTo(eq(REPO_FULL), eq("old000"), eq("new999"), anyString(), any()))
        .thenReturn(false);
    when(codeMapGenerator.generateWithSha(anyString(), anyString(), any())).thenReturn(null);

    assertRegenerated(service.generateForRun("r1", REPO_URL, "main", "pat", "p1"));
  }

  @Test
  void executespecOnlyDriftStillReusesButRecordsTheRunSha() {
    projectCodeMapAtSha("old000");
    runSnapshotAtSha("new999");
    when(gitHub.diffConfinedTo(
            eq(REPO_FULL), eq("old000"), eq("new999"), eq(".executespec/"), any()))
        .thenReturn(true);

    String out = service.generateForRun("r1", REPO_URL, "main", "pat", "p1");

    assertThat(out).contains("# codemap");
    ArgumentCaptor<RunCodeMap> saved = ArgumentCaptor.forClass(RunCodeMap.class);
    verify(runCodeMapRepo).save(saved.capture());
    // The persisted provenance is the RUN's revision, not the project codemap's.
    assertThat(saved.getValue().getGeneratedFromSha()).isEqualTo("new999");
  }

  @Test
  void missingRunSnapshotRegeneratesRatherThanReusingUnverified() {
    projectCodeMapAtSha("aaa111");
    when(snapshotRepo.findByRunId("r1")).thenReturn(List.of());
    when(codeMapGenerator.generateWithSha(anyString(), anyString(), any())).thenReturn(null);

    assertRegenerated(service.generateForRun("r1", REPO_URL, "main", "pat", "p1"));
  }

  @Test
  void projectCodeMapWithNoRecordedShaRegenerates() {
    projectCodeMapAtSha(null);
    runSnapshotAtSha("aaa111");
    when(codeMapGenerator.generateWithSha(anyString(), anyString(), any())).thenReturn(null);

    assertRegenerated(service.generateForRun("r1", REPO_URL, "main", "pat", "p1"));
  }
}
