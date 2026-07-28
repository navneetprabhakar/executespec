package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.codeintel.repository.RepoArchitectureDocRepository;
import com.navneet.executespec.project.workspace.ProjectWorkspaceService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectArchitectureJobPublisherTest {
  private ProjectArchitectureJobStore store;
  private ProjectRepository projects;
  private ProjectArchitectureJobService jobs;
  private ProjectCodeMapRepository codeMaps;
  private RepoArchitectureDocRepository repoArchitectures;
  private ProjectArchitectureDocRepository architectures;
  private ProjectArchitectureJobPublisher publisher;
  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    store = mock(ProjectArchitectureJobStore.class);
    projects = mock(ProjectRepository.class);
    jobs = mock(ProjectArchitectureJobService.class);
    codeMaps = mock(ProjectCodeMapRepository.class);
    repoArchitectures = mock(RepoArchitectureDocRepository.class);
    architectures = mock(ProjectArchitectureDocRepository.class);
    mapper = new ObjectMapper();
    publisher =
        new ProjectArchitectureJobPublisher(
            store,
            projects,
            jobs,
            codeMaps,
            repoArchitectures,
            architectures,
            mapper,
            mock(ProjectWorkspaceService.class),
            mock(VersionRetentionService.class));
    when(projects.findByIdForQuotaUpdate("project-1"))
        .thenReturn(Optional.of(Project.builder().id("project-1").orgId("org-1").build()));
  }

  @Test
  void staleLeaseCannotPublishAnyCanonicalRow() {
    var lease = lease(stagedJob("{}"));
    when(store.lockLiveForPromotion(lease)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> publisher.promote(lease, "a".repeat(64)))
        .isInstanceOf(ProjectArchitectureJobService.LeaseLostException.class);
    verifyNoInteractions(codeMaps, repoArchitectures, architectures);
    verify(store, never()).completePromoted(any(), any(), anyInt(), anyString());
  }

  @Test
  void validStagePublishesAllRowsThenCompletesFence() throws Exception {
    UUID repoId = UUID.randomUUID();
    var snapshot =
        new ProjectArchitectureJobService.SourceSnapshot(
            1,
            "project-1",
            List.of(
                new ProjectArchitectureJobService.SourceRepo(
                    repoId, "GITHUB", "", "acme/api", "main", "1".repeat(40), "actor-1")));
    String sourceJson = mapper.writeValueAsString(snapshot);
    String sourceDigest = ProjectArchitectureJobService.sha256(sourceJson);
    var staged =
        new ProjectArchitectureService.StagedArtifacts(
            1,
            "project-1",
            List.of(
                new ProjectArchitectureService.StagedCodeMap(
                    repoId, "acme/api", "main", "1".repeat(40), "# codemap")),
            List.of(
                new ProjectArchitectureService.StagedRepoArchitecture(
                    repoId,
                    "acme/api",
                    "main",
                    "1".repeat(40),
                    "# repo architecture",
                    "gemini-repo",
                    10,
                    20,
                    0.01,
                    false)),
            new ProjectArchitectureService.StagedProjectArchitecture(
                "# combined",
                "{\"acme/api\":\"" + "1".repeat(40) + "\"}",
                "{\"acme/api\":\"main\"}",
                0.02,
                "gemini-project",
                30,
                40,
                false),
            "summary",
            "QUALIFIED",
            List.of());
    String json = mapper.writeValueAsString(staged);
    var job = stagedJob(json, sourceJson, sourceDigest);
    var lease = lease(job);
    when(store.lockLiveForPromotion(lease)).thenReturn(Optional.of(job));
    when(jobs.canonicalJson(staged)).thenReturn(json);
    when(jobs.canonicalJson(snapshot)).thenReturn(sourceJson);
    when(jobs.parseSnapshot(sourceJson)).thenReturn(snapshot);
    when(codeMaps.findFirstByProjectIdAndRepoIdOrderByVersionDesc(anyString(), any()))
        .thenReturn(Optional.empty());
    when(repoArchitectures.findFirstByProjectIdAndRepoIdOrderByVersionDesc(anyString(), any()))
        .thenReturn(Optional.empty());
    when(architectures.findFirstByProjectIdOrderByVersionDesc("project-1"))
        .thenReturn(Optional.empty());
    when(codeMaps.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    when(repoArchitectures.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    when(architectures.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    when(store.completePromoted(any(), any(), eq(1), eq(sourceDigest))).thenReturn(true);

    var published = publisher.promote(lease, sourceDigest);

    assertThat(published.architecture().getContent()).isEqualTo("# combined");
    assertThat(published.codeMaps()).hasSize(1);
    assertThat(published.repoArchitectures()).hasSize(1);
    verify(store)
        .completePromoted(eq(lease), eq(published.architecture().getId()), eq(1), eq(sourceDigest));
  }

  @Test
  void corruptPersistedSourceSnapshotDigestCannotPublish() throws Exception {
    var snapshot = new ProjectArchitectureJobService.SourceSnapshot(1, "project-1", List.of());
    String sourceJson = mapper.writeValueAsString(snapshot);
    String corruptDigest = "a".repeat(64);
    var job = stagedJob("{}", sourceJson, corruptDigest);
    var lease = lease(job);
    when(store.lockLiveForPromotion(lease)).thenReturn(Optional.of(job));
    when(jobs.parseSnapshot(sourceJson)).thenReturn(snapshot);
    when(jobs.canonicalJson(snapshot)).thenReturn(sourceJson);

    assertThatThrownBy(() -> publisher.promote(lease, corruptDigest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Architecture source changed before promotion");

    verifyNoInteractions(codeMaps, repoArchitectures, architectures);
    verify(store, never()).completePromoted(any(), any(), anyInt(), anyString());
  }

  private static ProjectArchitectureJobStore.Lease lease(ProjectArchitectureJobStore.Job job) {
    return new ProjectArchitectureJobStore.Lease(
        job, "worker-1", 1, Instant.now().plusSeconds(900));
  }

  private static ProjectArchitectureJobStore.Job stagedJob(String json) {
    String sourceJson = "{\"schemaVersion\":1,\"projectId\":\"project-1\",\"repositories\":[]}";
    return stagedJob(json, sourceJson, ProjectArchitectureJobService.sha256(sourceJson));
  }

  private static ProjectArchitectureJobStore.Job stagedJob(
      String json, String sourceJson, String sourceDigest) {
    Instant now = Instant.now();
    return new ProjectArchitectureJobStore.Job(
        UUID.randomUUID(),
        "project-1",
        "org-1",
        "actor-1",
        null,
        sourceJson,
        sourceDigest,
        "GEMINI",
        "gemini-repo",
        "gemini-project",
        "ctx-repo",
        "ctx-project",
        ProjectArchitectureJobStore.Status.RUNNING,
        "PUBLISHING",
        1,
        now,
        now.plusSeconds(7200),
        "worker-1",
        1,
        now.plusSeconds(900),
        null,
        json,
        ProjectArchitectureJobService.sha256(json),
        "QUALIFIED",
        "{\"schemaVersion\":1,\"status\":\"QUALIFIED\",\"warnings\":[]}",
        null,
        null,
        null,
        null,
        null,
        now,
        now,
        null,
        null);
  }
}
