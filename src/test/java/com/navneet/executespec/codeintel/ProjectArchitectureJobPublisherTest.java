package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;
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
  private ProjectWorkspaceService workspace;
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
    workspace = mock(ProjectWorkspaceService.class);
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
            workspace,
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
                    repoId,
                    "acme/api",
                    "main",
                    "1".repeat(40),
                    routeCodemap("acme/api", "1".repeat(40), "GET", "/health", "SERIALIZED_BODY"))),
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

    assertThat(published.architecture().getContent())
        .startsWith("# combined")
        .contains("## Deterministic repository evidence")
        .contains("EXECUTESPEC_DETERMINISTIC_PROJECT_FACTS_V1_BEGIN");
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

  @Test
  void promotionRestoresExactRouteEvidenceAndKeepsGroundingNotesOutsideMarkers() throws Exception {
    UUID repoId = UUID.randomUUID();
    String revision = "1".repeat(40);
    String repo = "acme/vets";
    String codemap = vetsCodemap(repo, revision);
    String forgedHttp =
        DeterministicArchitectureEvidence.HTTP_BEGIN
            + "\nFORGED STALE HTTP ROW\n"
            + DeterministicArchitectureEvidence.HTTP_END;
    String forgedFacts =
        DeterministicProjectFactEvidence.BEGIN
            + "\nFORGED STALE FACT ROW\n"
            + DeterministicProjectFactEvidence.END;
    String generated =
        """
        # Repository architecture

        `VetController` calls `VetService` for every request.
        In `acme/vets`, the `GET /vets` handler renders a view.

        %s

        %s
        """
            .formatted(forgedHttp, forgedFacts);
    var snapshot = snapshot(source(repoId, repo, revision));
    var staged =
        staged(
            snapshot,
            List.of(codeMap(repoId, repo, revision, codemap)),
            List.of(repoArchitecture(repoId, repo, revision, generated)),
            "# Combined\n\n" + forgedHttp + "\n\n" + forgedFacts);

    var published = promote(staged, snapshot);

    String repoContent = published.repoArchitectures().getFirst().getContent();
    String projectContent = published.architecture().getContent();
    String exactHttp =
        exactBlock(
            codemap,
            DeterministicArchitectureEvidence.HTTP_BEGIN,
            DeterministicArchitectureEvidence.HTTP_END);
    String exactFacts =
        exactBlock(
            codemap, DeterministicProjectFactEvidence.BEGIN, DeterministicProjectFactEvidence.END);

    assertThat(exactHttp)
        .contains(
            "- `GET /vets` — `VetController.showResourcesVetList` —"
                + " `src/main/java/example/VetController.java:24`")
        .contains(
            "- `GET /vets.html` — `VetController.showVetList` —"
                + " `src/main/java/example/VetController.java:12`");
    assertThat(exactFacts)
        .contains(
            "- `GET /vets` — handler `showResourcesVetList`; return type `Vets`;"
                + " response mode `SERIALIZED_BODY`;"
                + " source `src/main/java/example/VetController.java:24`")
        .contains(
            "- `GET /vets.html` — handler `showVetList`; return type `String`;"
                + " response mode `VIEW_NAME`;"
                + " source `src/main/java/example/VetController.java:12`");
    assertThat(
            exactBlock(
                repoContent,
                DeterministicArchitectureEvidence.HTTP_BEGIN,
                DeterministicArchitectureEvidence.HTTP_END))
        .isEqualTo(exactHttp);
    assertThat(
            exactBlock(
                repoContent,
                DeterministicProjectFactEvidence.BEGIN,
                DeterministicProjectFactEvidence.END))
        .isEqualTo(exactFacts);
    assertThat(repoContent)
        .contains("Declared codemap symbols: `VetController`, `VetService`;")
        .contains("Not established:")
        .doesNotContain("FORGED STALE", "handler renders a view");
    assertThat(exactHttp).doesNotContain("Declared codemap symbols:");
    assertThat(exactFacts).doesNotContain("Declared codemap symbols:");
    assertThat(projectContent).contains(exactHttp, exactFacts).doesNotContain("FORGED STALE");
    assertThat(occurrences(repoContent, DeterministicArchitectureEvidence.HTTP_BEGIN)).isEqualTo(1);
    assertThat(occurrences(repoContent, DeterministicProjectFactEvidence.BEGIN)).isEqualTo(1);
  }

  @Test
  void promotionDegradesUnsupportedExhaustiveSourceContractsOnEveryPublishedSurface()
      throws Exception {
    UUID repoId = UUID.randomUUID();
    String revision = "4".repeat(40);
    String repo = "acme/inventory";
    String codemap =
        routeCodemap(repo, revision, "GET", "/health", "SERIALIZED_BODY")
            + "\n## Functions\n\n- `sortInventory`\n- `normalizeItem`\n";
    String falseClaim =
        "`sortInventory` accepts valid fields (`sku`, `name`, `category`, `priceCents`,"
            + " `quantity`).";
    var snapshot = snapshot(source(repoId, repo, revision));
    var base =
        staged(
            snapshot,
            List.of(codeMap(repoId, repo, revision, codemap)),
            List.of(repoArchitecture(repoId, repo, revision, "# Repo\n\n" + falseClaim)),
            "# Combined\n\n" + falseClaim);
    var staged =
        new ProjectArchitectureService.StagedArtifacts(
            base.schemaVersion(),
            base.projectId(),
            base.codeMaps(),
            base.repoArchitectures(),
            base.projectArchitecture(),
            falseClaim,
            base.qualityStatus(),
            base.qualityEvidence());

    var published = promote(staged, snapshot);

    assertThat(published.repoArchitectures().getFirst().getContent())
        .contains(
            "Source contract for `sortInventory`: not established by complete exact-source"
                + " evidence.")
        .doesNotContain("`category`", "accepts valid fields");
    assertThat(published.architecture().getContent())
        .contains("Source contract for `sortInventory`: not established")
        .doesNotContain("`category`", "accepts valid fields");
    assertThat(published.summary())
        .contains("Source contract for `sortInventory`: not established")
        .doesNotContain("`category`", "accepts valid fields");
    assertThat(
            exactBlock(
                published.repoArchitectures().getFirst().getContent(),
                DeterministicProjectFactEvidence.BEGIN,
                DeterministicProjectFactEvidence.END))
        .isEqualTo(
            exactBlock(
                codemap,
                DeterministicProjectFactEvidence.BEGIN,
                DeterministicProjectFactEvidence.END));
  }

  @Test
  void promotionUsesRepoBoundAuthorityButNeverCrossGroundsCombinedOrSummaryContent()
      throws Exception {
    UUID repoId = UUID.randomUUID();
    String revision = "5".repeat(40);
    String repo = "acme/inventory";
    String source =
        """
        export function sortInventory(items, field) {
          const allowedSortFields = new Set(['sku', 'name', 'priceCents', 'quantity']);
          if (!allowedSortFields.has(field)) throw new Error('invalid field');
          return [...items];
        }
        """;
    String exactSource =
        ExportedFunctionEvidence.render(repo, revision, Map.of("src/inventory.js", source));
    String codemap =
        routeCodemap(repo, revision, "GET", "/health", "SERIALIZED_BODY")
            + "\n## Functions\n\n- `sortInventory`\n"
            + exactSource;
    String validClaim =
        "`sortInventory` accepts valid fields (`sku`, `name`, `priceCents`, `quantity`).";
    var snapshot = snapshot(source(repoId, repo, revision));
    var base =
        staged(
            snapshot,
            List.of(codeMap(repoId, repo, revision, codemap)),
            List.of(repoArchitecture(repoId, repo, revision, "# Repo\n\n" + validClaim)),
            "# Combined\n\n" + validClaim);
    var staged =
        new ProjectArchitectureService.StagedArtifacts(
            base.schemaVersion(),
            base.projectId(),
            base.codeMaps(),
            base.repoArchitectures(),
            base.projectArchitecture(),
            validClaim,
            base.qualityStatus(),
            base.qualityEvidence());

    var published = promote(staged, snapshot);

    assertThat(published.repoArchitectures().getFirst().getContent()).contains(validClaim);
    assertThat(published.architecture().getContent())
        .contains(
            "Source contract for `sortInventory`: not established by complete exact-source"
                + " evidence.")
        .doesNotContain(validClaim);
    assertThat(published.summary())
        .isEqualTo(
            "Source contract for `sortInventory`: not established by complete exact-source"
                + " evidence.");
  }

  @Test
  void promotionPairsEvidenceByRepoIdWhenArtifactOrderDiffersAndRoutesShareAPath()
      throws Exception {
    UUID apiId = UUID.randomUUID();
    UUID webId = UUID.randomUUID();
    String apiRevision = "a".repeat(40);
    String webRevision = "b".repeat(40);
    String apiRepo = "acme/api";
    String webRepo = "acme/web";
    String apiCodemap = routeCodemap(apiRepo, apiRevision, "GET", "/shared", "SERIALIZED_BODY");
    String webCodemap = routeCodemap(webRepo, webRevision, "POST", "/shared", "VIEW_NAME");
    var snapshot =
        snapshot(source(apiId, apiRepo, apiRevision), source(webId, webRepo, webRevision));
    var staged =
        staged(
            snapshot,
            List.of(
                codeMap(apiId, apiRepo, apiRevision, apiCodemap),
                codeMap(webId, webRepo, webRevision, webCodemap)),
            // Deliberately reverse repository-architecture order relative to codemap/source order.
            List.of(
                repoArchitecture(
                    webId,
                    webRepo,
                    webRevision,
                    "# Web\n\nIn `acme/web`, the `POST /shared` handler resolves a view."),
                repoArchitecture(
                    apiId,
                    apiRepo,
                    apiRevision,
                    "# API\n\nIn `acme/api`, the `GET /shared` handler returns JSON.")),
            "# Combined");

    var published = promote(staged, snapshot);

    assertThat(published.repoNames()).containsExactly(webRepo, apiRepo);
    Map<UUID, String> contentByRepo =
        published.repoArchitectures().stream()
            .collect(
                java.util.stream.Collectors.toMap(doc -> doc.getRepoId(), doc -> doc.getContent()));
    assertThat(contentByRepo.get(webId))
        .contains(
            "- `POST /shared` — handler `handle`; return type `String`;"
                + " response mode `VIEW_NAME`;")
        .doesNotContain("- `GET /shared`", "response mode `SERIALIZED_BODY`");
    assertThat(contentByRepo.get(apiId))
        .contains(
            "- `GET /shared` — handler `handle`; return type `Payload`;"
                + " response mode `SERIALIZED_BODY`;")
        .doesNotContain("- `POST /shared`", "response mode `VIEW_NAME`");
    assertThat(published.architecture().getContent())
        .contains("### `acme/web`", "### `acme/api`")
        .containsSubsequence(
            "### `acme/web`", "- `POST /shared`", "### `acme/api`", "- `GET /shared`");
  }

  @Test
  void workspacePublicationPairsMirrorNamesByRepoIdWhenArtifactOrdersDiffer() {
    UUID apiId = UUID.randomUUID();
    UUID webId = UUID.randomUUID();
    ProjectCodeMap apiMap =
        ProjectCodeMap.builder()
            .projectId("project-1")
            .repoId(apiId)
            .version(3)
            .content("api codemap")
            .build();
    ProjectCodeMap webMap =
        ProjectCodeMap.builder()
            .projectId("project-1")
            .repoId(webId)
            .version(7)
            .content("web codemap")
            .build();
    RepoArchitectureDoc webArchitecture =
        RepoArchitectureDoc.builder()
            .projectId("project-1")
            .repoId(webId)
            .version(8)
            .content("web architecture")
            .build();
    RepoArchitectureDoc apiArchitecture =
        RepoArchitectureDoc.builder()
            .projectId("project-1")
            .repoId(apiId)
            .version(4)
            .content("api architecture")
            .build();
    ProjectArchitectureDoc combined =
        ProjectArchitectureDoc.builder()
            .projectId("project-1")
            .version(9)
            .content("combined architecture")
            .build();
    var published =
        new ProjectArchitectureJobPublisher.Published(
            combined,
            // Codemaps follow source order, while repository architectures and names are reversed.
            List.of(apiMap, webMap),
            List.of(webArchitecture, apiArchitecture),
            List.of("acme/web", "acme/api"),
            "summary");

    publisher.publishWorkspace("project-1", published);

    verify(workspace)
        .writeAndCommit("project-1", "codemaps/api.md", "api codemap", "codemap: acme/api v3");
    verify(workspace)
        .writeAndCommit("project-1", "codemaps/web.md", "web codemap", "codemap: acme/web v7");
    verify(workspace)
        .writeAndCommit(
            "project-1", "repos/web/architecture.md", "web architecture", "repo-arch: acme/web v8");
    verify(workspace)
        .writeAndCommit(
            "project-1", "repos/api/architecture.md", "api architecture", "repo-arch: acme/api v4");
  }

  @Test
  void workspacePublicationQualifiesCollidingRepositoryBasenames() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    var published =
        new ProjectArchitectureJobPublisher.Published(
            ProjectArchitectureDoc.builder()
                .projectId("project-1")
                .version(1)
                .content("combined")
                .build(),
            List.of(
                ProjectCodeMap.builder()
                    .projectId("project-1")
                    .repoId(firstId)
                    .version(1)
                    .content("first map")
                    .build(),
                ProjectCodeMap.builder()
                    .projectId("project-1")
                    .repoId(secondId)
                    .version(1)
                    .content("second map")
                    .build()),
            List.of(
                RepoArchitectureDoc.builder()
                    .projectId("project-1")
                    .repoId(firstId)
                    .version(1)
                    .content("first architecture")
                    .build(),
                RepoArchitectureDoc.builder()
                    .projectId("project-1")
                    .repoId(secondId)
                    .version(1)
                    .content("second architecture")
                    .build()),
            List.of("owner-a/service", "owner-b/service"),
            null);

    publisher.publishWorkspace("project-1", published);

    verify(workspace)
        .writeAndCommit(
            "project-1", "codemaps/owner-a_service.md", "first map", "codemap: owner-a/service v1");
    verify(workspace)
        .writeAndCommit(
            "project-1",
            "codemaps/owner-b_service.md",
            "second map",
            "codemap: owner-b/service v1");
    verify(workspace)
        .writeAndCommit(
            "project-1",
            "repos/owner-a_service/architecture.md",
            "first architecture",
            "repo-arch: owner-a/service v1");
    verify(workspace)
        .writeAndCommit(
            "project-1",
            "repos/owner-b_service/architecture.md",
            "second architecture",
            "repo-arch: owner-b/service v1");
  }

  private ProjectArchitectureJobPublisher.Published promote(
      ProjectArchitectureService.StagedArtifacts staged,
      ProjectArchitectureJobService.SourceSnapshot snapshot)
      throws Exception {
    String sourceJson = mapper.writeValueAsString(snapshot);
    String sourceDigest = ProjectArchitectureJobService.sha256(sourceJson);
    String stagedJson = mapper.writeValueAsString(staged);
    var job = stagedJob(stagedJson, sourceJson, sourceDigest);
    var lease = lease(job);
    when(store.lockLiveForPromotion(lease)).thenReturn(Optional.of(job));
    when(jobs.canonicalJson(staged)).thenReturn(stagedJson);
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
    return publisher.promote(lease, sourceDigest);
  }

  private static ProjectArchitectureJobService.SourceSnapshot snapshot(
      ProjectArchitectureJobService.SourceRepo... sources) {
    return new ProjectArchitectureJobService.SourceSnapshot(1, "project-1", List.of(sources));
  }

  private static ProjectArchitectureJobService.SourceRepo source(
      UUID repoId, String repo, String revision) {
    return new ProjectArchitectureJobService.SourceRepo(
        repoId, "GITHUB", "", repo, "main", revision, "actor-1");
  }

  private static ProjectArchitectureService.StagedCodeMap codeMap(
      UUID repoId, String repo, String revision, String content) {
    return new ProjectArchitectureService.StagedCodeMap(repoId, repo, "main", revision, content);
  }

  private static ProjectArchitectureService.StagedRepoArchitecture repoArchitecture(
      UUID repoId, String repo, String revision, String content) {
    return new ProjectArchitectureService.StagedRepoArchitecture(
        repoId, repo, "main", revision, content, "gemini-repo", 10, 20, 0.01, false);
  }

  private static ProjectArchitectureService.StagedArtifacts staged(
      ProjectArchitectureJobService.SourceSnapshot snapshot,
      List<ProjectArchitectureService.StagedCodeMap> maps,
      List<ProjectArchitectureService.StagedRepoArchitecture> repoDocs,
      String projectContent) {
    Map<String, String> shas = new java.util.LinkedHashMap<>();
    Map<String, String> branches = new java.util.LinkedHashMap<>();
    for (ProjectArchitectureJobService.SourceRepo source : snapshot.repositories()) {
      shas.put(source.repoFullName(), source.revision());
      branches.put(source.repoFullName(), source.branch());
    }
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      return new ProjectArchitectureService.StagedArtifacts(
          1,
          "project-1",
          maps,
          repoDocs,
          new ProjectArchitectureService.StagedProjectArchitecture(
              projectContent,
              objectMapper.writeValueAsString(shas),
              objectMapper.writeValueAsString(branches),
              0.02,
              "gemini-project",
              30,
              40,
              false),
          "summary",
          "QUALIFIED",
          List.of());
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static String vetsCodemap(String repo, String revision) {
    return """
# Repository codemap: %s
- `VetController`
- `VetService`

%s
## Deterministic public HTTP surface

- Source revision: `%s`
- Status: `AVAILABLE`
- `GET /vets` — `VetController.showResourcesVetList` — `src/main/java/example/VetController.java:24`
- `GET /vets.html` — `VetController.showVetList` — `src/main/java/example/VetController.java:12`
%s

%s
## Deterministic project-document facts

- Evidence-Format: `EXECUTESPEC_PROJECT_FACTS_V2`
- Repository: `%s`
- Source revision: `%s`
- Status: `AVAILABLE`
- Authority: exact-revision repository evidence; model-generated architecture prose is not grounding evidence.

### Spring route handler response evidence

%s
- Route-V1: method=%s;path=%s;mode=SERIALIZED_BODY;source=%s;handler=%s;returnType=%s
- Route-V1: method=%s;path=%s;mode=VIEW_NAME;source=%s;handler=%s;returnType=%s
%s

- `GET /vets` — handler `showResourcesVetList`; return type `Vets`; response mode `SERIALIZED_BODY`; source `src/main/java/example/VetController.java:24`
- `GET /vets.html` — handler `showVetList`; return type `String`; response mode `VIEW_NAME`; source `src/main/java/example/VetController.java:12`
%s

%s
- Evidence-Format: EXECUTESPEC_SETUP_SOURCES_V2
- Repository-Base64: %s
- Source-Revision: %s
- Scm-Provider: GITHUB
- Source-Selection: COMPLETE
- No exact setup command source was selected.
%s
"""
        .formatted(
            repo,
            DeterministicArchitectureEvidence.HTTP_BEGIN,
            revision,
            DeterministicArchitectureEvidence.HTTP_END,
            DeterministicProjectFactEvidence.BEGIN,
            repo,
            revision,
            DeterministicProjectFactEvidence.ROUTES_BEGIN,
            b64("GET"),
            b64("/vets"),
            b64("src/main/java/example/VetController.java:24"),
            b64("showResourcesVetList"),
            b64("Vets"),
            b64("GET"),
            b64("/vets.html"),
            b64("src/main/java/example/VetController.java:12"),
            b64("showVetList"),
            b64("String"),
            DeterministicProjectFactEvidence.ROUTES_END,
            DeterministicProjectFactEvidence.END,
            DeterministicProjectFactEvidence.SETUP_BEGIN,
            b64(repo),
            revision,
            DeterministicProjectFactEvidence.SETUP_END);
  }

  private static String routeCodemap(
      String repo, String revision, String method, String path, String mode) {
    String returnType = "VIEW_NAME".equals(mode) ? "String" : "Payload";
    return """
# Repository codemap: %s

%s
## Deterministic public HTTP surface

- Source revision: `%s`
- Status: `AVAILABLE`
- `%s %s` — `Controller.handle` — `src/main/java/example/Controller.java:10`
%s

%s
## Deterministic project-document facts

- Evidence-Format: `EXECUTESPEC_PROJECT_FACTS_V2`
- Repository: `%s`
- Source revision: `%s`
- Status: `AVAILABLE`
- Authority: exact-revision repository evidence; model-generated architecture prose is not grounding evidence.

### Spring route handler response evidence

%s
- Route-V1: method=%s;path=%s;mode=%s;source=%s;handler=%s;returnType=%s
%s

- `%s %s` — handler `handle`; return type `%s`; response mode `%s`; source `src/main/java/example/Controller.java:10`
%s

%s
- Evidence-Format: EXECUTESPEC_SETUP_SOURCES_V2
- Repository-Base64: %s
- Source-Revision: %s
- Scm-Provider: GITHUB
- Source-Selection: COMPLETE
- No exact setup command source was selected.
%s
"""
        .formatted(
            repo,
            DeterministicArchitectureEvidence.HTTP_BEGIN,
            revision,
            method,
            path,
            DeterministicArchitectureEvidence.HTTP_END,
            DeterministicProjectFactEvidence.BEGIN,
            repo,
            revision,
            DeterministicProjectFactEvidence.ROUTES_BEGIN,
            b64(method),
            b64(path),
            mode,
            b64("src/main/java/example/Controller.java:10"),
            b64("handle"),
            b64(returnType),
            DeterministicProjectFactEvidence.ROUTES_END,
            method,
            path,
            returnType,
            mode,
            DeterministicProjectFactEvidence.END,
            DeterministicProjectFactEvidence.SETUP_BEGIN,
            b64(repo),
            revision,
            DeterministicProjectFactEvidence.SETUP_END);
  }

  private static String b64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String exactBlock(String markdown, String begin, String end) {
    int start = markdown.indexOf(begin);
    int finish = markdown.indexOf(end, start + begin.length());
    assertThat(start).isGreaterThanOrEqualTo(0);
    assertThat(finish).isGreaterThan(start);
    return markdown.substring(start, finish + end.length()).strip();
  }

  private static int occurrences(String value, String needle) {
    int count = 0;
    int cursor = 0;
    while ((cursor = value.indexOf(needle, cursor)) >= 0) {
      count++;
      cursor += needle.length();
    }
    return count;
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
