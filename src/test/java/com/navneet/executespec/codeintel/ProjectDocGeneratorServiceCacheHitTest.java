package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.codeintel.repository.ProjectGeneratedDocRepository;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.workspace.ProjectWorkspaceService;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ProjectProviderResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * PR-Q (2026-05-13) — pins the short-circuit contract on {@link ProjectDocGeneratorService}.
 *
 * <p>The cache key is (projectId, docType, sourceArchVersion, sourceShasFingerprint). When all four
 * match a non-empty doc already in the table, the service must return that doc unchanged AND skip
 * the LLM call entirely. Otherwise it falls through to the regenerate path (not exercised here —
 * that's covered by the existing integration tests).
 *
 * <p>This test is deliberately narrow: it pins only the cache-hit path. The cache-miss path is the
 * pre-PR-Q behaviour that everything else already tests.
 */
class ProjectDocGeneratorServiceCacheHitTest {

  private static final UUID SOURCE_REPO_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000099");

  private ProjectArchitectureService architectureService;
  private ProjectWorkspaceService workspace;
  private ProjectGeneratedDocRepository docRepo;
  private VersionRetentionService versionRetention;
  private ProjectRepository projectRepo;
  private ProjectRepoLinkRepository linkRepo;
  private ProjectRepoRepository repoRepo;
  private LlmRouter llmRouter;
  private ModelResolver modelResolver;
  private RunCostTracker costTracker;
  private ProjectProviderResolver projectProviderResolver;
  private LlmExecutionPolicyFactory executionPolicyFactory;
  private PromptAssembler promptAssembler;
  private ProjectDocGeneratorService svc;

  @BeforeEach
  void setUp() {
    architectureService = mock(ProjectArchitectureService.class);
    workspace = mock(ProjectWorkspaceService.class);
    docRepo = mock(ProjectGeneratedDocRepository.class);
    versionRetention = mock(VersionRetentionService.class);
    projectRepo = mock(ProjectRepository.class);
    linkRepo = mock(ProjectRepoLinkRepository.class);
    repoRepo = mock(ProjectRepoRepository.class);
    llmRouter = mock(LlmRouter.class);
    modelResolver = mock(ModelResolver.class);
    costTracker = mock(RunCostTracker.class);
    projectProviderResolver = mock(ProjectProviderResolver.class);
    executionPolicyFactory = mock(LlmExecutionPolicyFactory.class);
    promptAssembler = mock(PromptAssembler.class);
    when(promptAssembler.assemble("project-doc-explanation.md")).thenReturn("explanation prompt");
    when(promptAssembler.assemble("project-doc-setup.md")).thenReturn("setup prompt");
    when(projectRepo.findByIdForQuotaUpdate("p-1")).thenReturn(Optional.of(project("p-1")));

    svc =
        new ProjectDocGeneratorService(
            architectureService,
            workspace,
            docRepo,
            versionRetention,
            projectRepo,
            linkRepo,
            repoRepo,
            llmRouter,
            modelResolver,
            costTracker,
            projectProviderResolver,
            executionPolicyFactory,
            promptAssembler);
  }

  private Project project(String id) {
    return Project.builder().id(id).orgId("org-1").name("Test").createdBy("u-1").build();
  }

  private ProjectArchitectureDoc archDoc(int version, String shasJson) {
    return ProjectArchitectureDoc.builder()
        .projectId("p-1")
        .version(version)
        .content("# arch v" + version)
        .generatedFromShas(shasJson)
        .build();
  }

  private ProjectGeneratedDoc generatedDoc(
      DocType type, int version, int archVersion, String fingerprint) {
    return ProjectGeneratedDoc.builder()
        .projectId("p-1")
        .docType(type)
        .version(version)
        .content(validDoc(type, type.name() + " v" + version))
        .sourceArchVersion(archVersion)
        .sourceShasFingerprint(fingerprint)
        .build();
  }

  @Test
  void cacheHit_returnsCachedDoc_andSkipsLlmCall() {
    String shasJson = "{\"owner/repo-a\":\"aaa\"}";
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(project("p-1")));
    when(architectureService.latest("p-1")).thenReturn(Optional.of(archDoc(7, shasJson)));
    wireExactSourceRepository("aaa");

    ProjectGeneratedDoc cached = generatedDoc(DocType.EXPLANATION, 3, 7, shasJson);
    when(docRepo
            .findFirstByProjectIdAndDocTypeAndSourceArchVersionAndSourceShasFingerprintOrderByVersionDesc(
                "p-1", DocType.EXPLANATION, 7, shasJson))
        .thenReturn(Optional.of(cached));

    ProjectGeneratedDoc result =
        svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC);

    assertThat(result).isSameAs(cached);
    // The LLM router must NOT have been called — that's the whole point of the short-circuit.
    verifyNoInteractions(llmRouter);
    // ModelResolver should also not be called — we never even resolved a model.
    verify(modelResolver, never()).resolve(any(), any());
    // No new doc saved.
    verify(docRepo, never()).saveAndFlush(any(ProjectGeneratedDoc.class));
  }

  @Test
  void cacheHit_emptyContent_fallsThrough() {
    // Defensive: if the cached row happens to have null/blank content (a bug or partial write
    // in some prior code path), we must NOT short-circuit on it — fall through to regenerate.
    String shasJson = "{\"owner/repo-a\":\"aaa\"}";
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(project("p-1")));
    when(architectureService.latest("p-1")).thenReturn(Optional.of(archDoc(7, shasJson)));
    wireExactSourceRepository("aaa");

    ProjectGeneratedDoc emptyCached = generatedDoc(DocType.EXPLANATION, 3, 7, shasJson);
    emptyCached.setContent(""); // simulate empty-content row
    when(docRepo
            .findFirstByProjectIdAndDocTypeAndSourceArchVersionAndSourceShasFingerprintOrderByVersionDesc(
                "p-1", DocType.EXPLANATION, 7, shasJson))
        .thenReturn(Optional.of(emptyCached));
    wireExactSourceRepository("aaa");

    // We don't bother mocking the full regenerate path — instead, observe that the cache-hit
    // didn't fire by checking the test gets past the cache lookup and would have tried to
    // call modelResolver. The actual call will throw NPE on the mock chain, which is fine —
    // we only need to assert the cache filter rejected the blank doc.
    try {
      svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC);
    } catch (Exception e) {
      // expected — regenerate path doesn't have all mocks wired
    }
    // The fact that we got past the cache check + tried to resolve a model proves the empty
    // doc was correctly rejected as a cache hit.
    verify(modelResolver).resolve("ProjectDocGeneratorService", LlmProvider.ANTHROPIC);
  }

  @Test
  void cacheHitWithUnsupportedBehaviorIsRegeneratedInsteadOfReturned() {
    wireCacheMiss();
    wireExactSourceRepository(
        "aaa",
        """
        ## Functions
        ### `src/AIPlayer.java`
        - `class AIPlayer`
        - `evaluate`
        ### `src/TicTacToe.java`
        - `class TicTacToe`
        - `checkWin`
        """);
    String shasJson = "{\"owner/repo-a\":\"aaa\"}";
    ProjectGeneratedDoc stale = generatedDoc(DocType.EXPLANATION, 3, 7, shasJson);
    stale.setContent(
        validDoc(DocType.EXPLANATION, "Legacy")
            .replace(
                "## Key data flows",
                "`AIPlayer.evaluate` invokes `TicTacToe.checkWin`.\n## Key data flows"));
    String evidence =
        GeneratedSignatureGrounding.codemapEvidence(
            svc.buildSourceBundle(project("p-1"), archDoc(7, shasJson)));
    assertThat(GeneratedSignatureGrounding.unsupportedBehaviorClaim(stale.getContent(), evidence))
        .isPresent();
    when(docRepo
            .findFirstByProjectIdAndDocTypeAndSourceArchVersionAndSourceShasFingerprintOrderByVersionDesc(
                "p-1", DocType.EXPLANATION, 7, shasJson))
        .thenReturn(Optional.of(stale));
    when(llmRouter.callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class)))
        .thenReturn(textResponse(validDoc(DocType.EXPLANATION, "Regenerated"), "stop"));
    when(docRepo.saveAndFlush(any(ProjectGeneratedDoc.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ProjectGeneratedDoc result =
        svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC);

    assertThat(result).isNotSameAs(stale);
    assertThat(result.getContent())
        .isEqualTo(validDoc(DocType.EXPLANATION, "Regenerated"))
        .doesNotContain("invokes");
    verify(llmRouter)
        .callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class));
  }

  @Test
  void anthropicMultiGenerationResponsePublishesAllTextWithoutCorrectiveRetry() {
    wireCacheMiss();
    String expected = validDoc(DocType.EXPLANATION, "Generated across content blocks");
    when(llmRouter.callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class)))
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(""),
                        ChatGenerationMetadata.builder().finishReason("STOP").build()),
                    new Generation(
                        new AssistantMessage(expected),
                        ChatGenerationMetadata.builder().finishReason("STOP").build()))));
    when(docRepo.saveAndFlush(any(ProjectGeneratedDoc.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ProjectGeneratedDoc result =
        svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC);

    assertThat(result.getContent()).isEqualTo(expected);
    verify(llmRouter, org.mockito.Mockito.times(1))
        .callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class));
  }

  @Test
  void noLatestArch_throws_andNeverConsultsCache() {
    // If there's no architecture doc at all, the service throws before even hitting the cache
    // lookup — preserving the existing contract that callers must run /analyse first.
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(project("p-1")));
    when(architectureService.latest("p-1")).thenReturn(Optional.empty());

    try {
      svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC);
    } catch (IllegalStateException expected) {
      // pass
    }
    verify(docRepo, never())
        .findFirstByProjectIdAndDocTypeAndSourceArchVersionAndSourceShasFingerprintOrderByVersionDesc(
            any(), any(), any(), any());
  }

  @Test
  void sourceBundleCapStopsAtCompleteLineAndLabelsOmittedEvidence() {
    String source = "header\n" + "record\n".repeat(20_000);

    String capped = ProjectDocGeneratorService.capSourceBundle(source);

    assertThat(capped)
        .hasSizeLessThanOrEqualTo(ProjectDocGeneratorService.SOURCE_BUNDLE_CHAR_LIMIT);
    assertThat(capped).contains("section evidence truncated at a complete line");
    assertThat(capped).doesNotContain("recor\n[section");
  }

  @Test
  void fairBudgetIncludesEveryRepositoryInsteadOfPrefixStarvingLaterSections() {
    String hugeA = "repo-first-record\n".repeat(8_000);
    String hugeB = "repo-middle-record\n".repeat(8_000);
    String hugeC = "repo-last-record\n".repeat(8_000);

    String bundled =
        ProjectDocGeneratorService.fairBudgetSourceSections(
            java.util.List.of(
                new ProjectDocGeneratorService.SourceSection("# Repo first", hugeA),
                new ProjectDocGeneratorService.SourceSection("# Repo middle", hugeB),
                new ProjectDocGeneratorService.SourceSection("# Repo last", hugeC)));

    assertThat(bundled)
        .hasSizeLessThanOrEqualTo(ProjectDocGeneratorService.SOURCE_BUNDLE_CHAR_LIMIT)
        .contains("# Repo first", "# Repo middle", "# Repo last")
        .contains("repo-first-record", "repo-middle-record", "repo-last-record")
        .doesNotContain("repo-first-recor\n[section", "repo-middle-recor\n[section");
    String marker = "[section evidence truncated at a complete line";
    int markerIndex = bundled.indexOf(marker);
    int markerCount = 0;
    while (markerIndex >= 0) {
      assertThat(bundled.charAt(markerIndex - 1)).isEqualTo('\n');
      markerCount++;
      markerIndex = bundled.indexOf(marker, markerIndex + marker.length());
    }
    assertThat(markerCount).isEqualTo(3);
  }

  @Test
  void fairBudgetNeverUsesRawTailSubstringWhenStructuralOverheadIsLarge() {
    java.util.List<ProjectDocGeneratorService.SourceSection> sections =
        java.util.stream.IntStream.range(0, 80)
            .mapToObj(
                index ->
                    new ProjectDocGeneratorService.SourceSection(
                        "# " + index + "-" + "h".repeat(500),
                        ("repo-" + index + "-complete-line\n").repeat(100)))
            .toList();

    String bundled = ProjectDocGeneratorService.fairBudgetSourceSections(sections);

    assertThat(bundled)
        .hasSizeLessThanOrEqualTo(ProjectDocGeneratorService.SOURCE_BUNDLE_CHAR_LIMIT)
        .endsWith("\n\n")
        .contains("# 0-", "# 40-", "# 79-");
  }

  @Test
  void sourceBundleSortsRepositoriesByCanonicalNameThenId() {
    UUID zId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID aLaterId = UUID.fromString("00000000-0000-0000-0000-000000000003");
    UUID aFirstId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    when(linkRepo.findByProjectId("p-1"))
        .thenReturn(
            List.of(
                ProjectRepoLink.builder().projectId("p-1").repoId(zId).build(),
                ProjectRepoLink.builder().projectId("p-1").repoId(aLaterId).build(),
                ProjectRepoLink.builder().projectId("p-1").repoId(aFirstId).build()));
    when(repoRepo.findById(zId))
        .thenReturn(
            Optional.of(ProjectRepo.builder().id(zId).githubRepoFullName("zeta/repo").build()));
    when(repoRepo.findById(aLaterId))
        .thenReturn(
            Optional.of(
                ProjectRepo.builder().id(aLaterId).githubRepoFullName("alpha/repo-b").build()));
    when(repoRepo.findById(aFirstId))
        .thenReturn(
            Optional.of(
                ProjectRepo.builder().id(aFirstId).githubRepoFullName("alpha/repo-a").build()));
    when(architectureService.codeMapForSourceSha("p-1", zId, "sha-z"))
        .thenReturn(Optional.of(ProjectCodeMap.builder().content("zeta-map").build()));
    when(architectureService.codeMapForSourceSha("p-1", aLaterId, "sha-b"))
        .thenReturn(Optional.of(ProjectCodeMap.builder().content("alpha-later-map").build()));
    when(architectureService.codeMapForSourceSha("p-1", aFirstId, "sha-a"))
        .thenReturn(Optional.of(ProjectCodeMap.builder().content("alpha-first-map").build()));

    String bundle =
        svc.buildSourceBundle(
            project("p-1"),
            archDoc(
                1,
                "{\"alpha/repo-a\":\"sha-a\",\"alpha/repo-b\":\"sha-b\","
                    + "\"zeta/repo\":\"sha-z\"}"));

    assertThat(bundle.indexOf("alpha-first-map")).isLessThan(bundle.indexOf("alpha-later-map"));
    assertThat(bundle.indexOf("alpha-later-map")).isLessThan(bundle.indexOf("zeta-map"));
  }

  @Test
  void sourceBundleRejectsLatestCodemapWhenExactArchitectureRevisionIsUnavailable() {
    UUID repoId = UUID.fromString("00000000-0000-0000-0000-000000000010");
    when(linkRepo.findByProjectId("p-1"))
        .thenReturn(List.of(ProjectRepoLink.builder().projectId("p-1").repoId(repoId).build()));
    when(repoRepo.findById(repoId))
        .thenReturn(
            Optional.of(ProjectRepo.builder().id(repoId).githubRepoFullName("owner/repo").build()));
    when(architectureService.codeMapForSourceSha("p-1", repoId, "source-sha"))
        .thenReturn(Optional.empty());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                svc.buildSourceBundle(
                    project("p-1"), archDoc(7, "{\"owner/repo\":\"source-sha\"}")))
        .isInstanceOf(ProjectDocGeneratorService.StaleSourceException.class);
  }

  @Test
  void publicGenerateMethodsDoNotOpenTransactionAroundProviderTransport() throws Exception {
    assertThat(
            ProjectDocGeneratorService.class
                .getMethod("generate", String.class, DocType.class, String.class, LlmProvider.class)
                .isAnnotationPresent(
                    org.springframework.transaction.annotation.Transactional.class))
        .isFalse();
    assertThat(
            ProjectDocGeneratorService.class
                .getMethod(
                    "generate",
                    String.class,
                    DocType.class,
                    String.class,
                    LlmProvider.class,
                    LlmExecutionReference.class)
                .isAnnotationPresent(
                    org.springframework.transaction.annotation.Transactional.class))
        .isFalse();
  }

  @Test
  void documentExecutionIdentityFitsDurableCorrelationColumn() {
    String identity =
        ProjectDocGeneratorService.projectDocContextId(
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

    assertThat(identity).isEqualTo("project-doc:123e4567-e89b-12d3-a456-426614174000");
    assertThat(identity).hasSizeLessThanOrEqualTo(96);
  }

  @Test
  void cacheMissPassesCompactIdentityIntoExecutionPolicyAndPersistsGeneratedDoc() {
    String shasJson = "{\"owner/repo-a\":\"aaa\"}";
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(project("p-1")));
    when(architectureService.latest("p-1")).thenReturn(Optional.of(archDoc(7, shasJson)));
    when(docRepo
            .findFirstByProjectIdAndDocTypeAndSourceArchVersionAndSourceShasFingerprintOrderByVersionDesc(
                "p-1", DocType.EXPLANATION, 7, shasJson))
        .thenReturn(Optional.empty());
    wireExactSourceRepository("aaa");
    when(modelResolver.resolve("ProjectDocGeneratorService", LlmProvider.ANTHROPIC))
        .thenReturn(
            ResolvedModel.builder()
                .requestedProvider(LlmProvider.ANTHROPIC)
                .actualProvider(LlmProvider.ANTHROPIC)
                .model("claude-test")
                .agentTier(AgentTier.WORKHORSE)
                .build());
    LlmExecutionReference reference =
        new LlmExecutionReference("project-doc-test", LlmExecutionScope.customer("org-1"));
    when(executionPolicyFactory.forProject(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(String.class),
            anyString()))
        .thenReturn(reference);
    when(llmRouter.callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class)))
        .thenReturn(
            new ChatResponse(
                java.util.List.of(
                    new Generation(
                        AssistantMessage.builder()
                            .content(validDoc(DocType.EXPLANATION, "Generated"))
                            .build()))));
    when(docRepo.findFirstByProjectIdAndDocTypeOrderByVersionDesc("p-1", DocType.EXPLANATION))
        .thenReturn(Optional.empty());
    when(docRepo.saveAndFlush(any(ProjectGeneratedDoc.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ProjectGeneratedDoc generated =
        svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC);

    ArgumentCaptor<String> sourceId = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> correlationId = ArgumentCaptor.forClass(String.class);
    verify(executionPolicyFactory)
        .forProject(
            org.mockito.ArgumentMatchers.eq("p-1"),
            org.mockito.ArgumentMatchers.eq("project-doc"),
            org.mockito.ArgumentMatchers.eq("PROJECT_DOC"),
            sourceId.capture(),
            correlationId.capture(),
            org.mockito.ArgumentMatchers.eq("ANTHROPIC"),
            org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.eq("actor-1"));
    assertThat(sourceId.getValue()).startsWith("project-doc:").hasSizeLessThanOrEqualTo(96);
    assertThat(correlationId.getValue()).isEqualTo(sourceId.getValue());
    assertThat(generated.getContent()).isEqualTo(validDoc(DocType.EXPLANATION, "Generated"));
    assertThat(generated.getSourceArchVersion()).isEqualTo(7);
    assertThat(generated.getSourceShasFingerprint()).isEqualTo(shasJson);
    verify(promptAssembler).assemble("project-doc-explanation.md");
  }

  @Test
  void truncatedProviderOutputIsRejectedAndNeverPersistedOrMirrored() {
    wireCacheMiss();
    when(llmRouter.callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class)))
        .thenReturn(textResponse("# Cut document", "MAX_TOKENS"));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC))
        .isInstanceOf(ProjectDocGeneratorService.IncompleteGenerationException.class)
        .hasMessageContaining("no document was saved");

    verify(docRepo, never()).saveAndFlush(any(ProjectGeneratedDoc.class));
    verify(workspace, never()).writeRawFile(anyString(), anyString(), anyString());
  }

  @Test
  void missingFinishMetadataIsUnknownRatherThanAClassificationFailure() {
    ChatResponse response =
        new ChatResponse(List.of(new Generation(new AssistantMessage("# Complete"), null)));

    assertThat(ProjectDocGeneratorService.responseWasTruncated(response)).isFalse();
  }

  @Test
  void concurrentIdenticalGenerationSharesOneProviderCallAndOnePersistedVersion() throws Exception {
    wireCacheMiss();
    CountDownLatch providerEntered = new CountDownLatch(1);
    CountDownLatch releaseProvider = new CountDownLatch(1);
    when(llmRouter.callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class)))
        .thenAnswer(
            ignored -> {
              providerEntered.countDown();
              if (!releaseProvider.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test provider release timed out");
              }
              return textResponse(validDoc(DocType.EXPLANATION, "Generated once"), "stop");
            });
    when(docRepo.saveAndFlush(any(ProjectGeneratedDoc.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC));
      assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
      var second =
          executor.submit(
              () -> svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC));
      // Let the second request reach the in-flight join while the first provider call remains
      // deterministically blocked. The production assertion is the one-call verification below.
      Thread.sleep(100);
      releaseProvider.countDown();

      assertThat(first.get(5, TimeUnit.SECONDS).getContent())
          .isEqualTo(validDoc(DocType.EXPLANATION, "Generated once"));
      assertThat(second.get(5, TimeUnit.SECONDS).getContent())
          .isEqualTo(validDoc(DocType.EXPLANATION, "Generated once"));
    }
    verify(llmRouter, org.mockito.Mockito.times(1))
        .callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class));
    verify(docRepo, org.mockito.Mockito.times(1)).saveAndFlush(any(ProjectGeneratedDoc.class));
    verify(projectRepo, org.mockito.Mockito.times(1)).findByIdForQuotaUpdate("p-1");
  }

  @Test
  void distinctAuthorityContextsNeverShareAnInFlightProviderCall() throws Exception {
    wireCacheMiss();
    CountDownLatch bothProvidersEntered = new CountDownLatch(2);
    CountDownLatch releaseProviders = new CountDownLatch(1);
    when(llmRouter.callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class)))
        .thenAnswer(
            invocation -> {
              bothProvidersEntered.countDown();
              if (!releaseProviders.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test provider release timed out");
              }
              return textResponse(validDoc(DocType.EXPLANATION, "Independently generated"), "stop");
            });
    when(docRepo.saveAndFlush(any(ProjectGeneratedDoc.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    LlmExecutionReference authorityA =
        new LlmExecutionReference("confirmed-action-a", LlmExecutionScope.customer("org-1"));
    LlmExecutionReference authorityB =
        new LlmExecutionReference("confirmed-action-b", LlmExecutionScope.customer("org-1"));

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () ->
                  svc.generate(
                      "p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC, authorityA));
      var second =
          executor.submit(
              () ->
                  svc.generate(
                      "p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC, authorityB));

      assertThat(bothProvidersEntered.await(5, TimeUnit.SECONDS)).isTrue();
      releaseProviders.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).getContent())
          .isEqualTo(validDoc(DocType.EXPLANATION, "Independently generated"));
      assertThat(second.get(5, TimeUnit.SECONDS).getContent())
          .isEqualTo(validDoc(DocType.EXPLANATION, "Independently generated"));
    }
    verify(llmRouter, org.mockito.Mockito.times(2))
        .callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class));
  }

  @Test
  void failedGenerationIsRemovedFromSingleFlightSoRetryCanSucceed() {
    wireCacheMiss();
    when(llmRouter.callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class)))
        .thenReturn(
            textResponse("# Cut", "length"),
            textResponse(validDoc(DocType.SETUP, "Complete"), "stop"));
    when(docRepo.saveAndFlush(any(ProjectGeneratedDoc.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> svc.generate("p-1", DocType.SETUP, "actor-1", LlmProvider.ANTHROPIC))
        .isInstanceOf(ProjectDocGeneratorService.IncompleteGenerationException.class);
    ProjectGeneratedDoc retry =
        svc.generate("p-1", DocType.SETUP, "actor-1", LlmProvider.ANTHROPIC);

    assertThat(retry.getContent()).isEqualTo(validDoc(DocType.SETUP, "Complete"));
    verify(llmRouter, org.mockito.Mockito.times(2))
        .callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class));
  }

  @Test
  void sourceChangeDuringProviderCallRejectsObsoleteDocumentWithoutPersistenceOrMirror() {
    wireCacheMiss();
    String oldFingerprint = "{\"owner/repo-a\":\"aaa\"}";
    String newFingerprint = "{\"owner/repo-a\":\"bbb\"}";
    when(architectureService.latest("p-1"))
        .thenReturn(
            Optional.of(archDoc(7, oldFingerprint)), Optional.of(archDoc(8, newFingerprint)));
    when(llmRouter.callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class)))
        .thenReturn(textResponse(validDoc(DocType.EXPLANATION, "Obsolete"), "stop"));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC))
        .isInstanceOf(ProjectDocGeneratorService.StaleSourceException.class)
        .hasMessageContaining("no document was saved");

    verify(docRepo, never()).saveAndFlush(any(ProjectGeneratedDoc.class));
    verify(workspace, never()).writeRawFile(anyString(), anyString(), anyString());
  }

  @Test
  void credentialLikeProviderOutputIsRejectedBeforePersistenceOrMirror() {
    wireCacheMiss();
    String unsafe = validDoc(DocType.SETUP, "Unsafe") + "\nActual key: sk-ant-" + "A".repeat(40);
    when(llmRouter.callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class)))
        .thenReturn(textResponse(unsafe, "stop"));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> svc.generate("p-1", DocType.SETUP, "actor-1", LlmProvider.ANTHROPIC))
        .isInstanceOf(ProjectDocGeneratorService.UnsafeGeneratedDocumentException.class);

    verify(docRepo, never()).saveAndFlush(any(ProjectGeneratedDoc.class));
    verify(workspace, never()).writeRawFile(anyString(), anyString(), anyString());
  }

  @Test
  void missingRequiredHeadingGetsOneSameRouteCorrectiveRetryBeforePublication() {
    wireCacheMiss();
    String missingArchitecture =
        validDoc(DocType.EXPLANATION, "First")
            .replace("## High-level architecture", "## Architecture");
    when(llmRouter.callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class)))
        .thenReturn(
            textResponse(missingArchitecture, "stop"),
            textResponse(validDoc(DocType.EXPLANATION, "Corrected"), "stop"));
    when(docRepo.saveAndFlush(any(ProjectGeneratedDoc.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ProjectGeneratedDoc generated =
        svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.chat.messages.Message>> messages =
        ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<String> providers = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> models = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> steps = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<LlmExecutionReference> references =
        ArgumentCaptor.forClass(LlmExecutionReference.class);
    verify(llmRouter, org.mockito.Mockito.times(2))
        .callTextStrict(
            providers.capture(),
            models.capture(),
            messages.capture(),
            org.mockito.ArgumentMatchers.eq("project-doc-explanation-p-1"),
            org.mockito.ArgumentMatchers.eq("project-doc-explanation"),
            steps.capture(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            references.capture());

    assertThat(generated.getContent()).isEqualTo(validDoc(DocType.EXPLANATION, "Corrected"));
    assertThat(providers.getAllValues()).containsExactly("anthropic", "anthropic");
    assertThat(models.getAllValues()).containsExactly("claude-test", "claude-test");
    assertThat(steps.getAllValues()).containsExactly("generate", "generate-corrective-retry");
    assertThat(references.getAllValues()).allMatch(reference -> reference == references.getValue());
    assertThat(messages.getAllValues().get(1).get(2).getText())
        .contains("PROJECT_DOC_VALIDATION_ERROR")
        .contains("missing required heading ## high-level architecture")
        .contains(
            "The corrected EXPLANATION must retain each of these exact H2 heading lines exactly"
                + " once")
        .contains(
            "## Repositories",
            "## High-level architecture",
            "## Key data flows",
            "## Where to contribute")
        .contains("Do not rename, delete, merge, reorder, or demote any of these headings");
    verify(docRepo, org.mockito.Mockito.times(1)).saveAndFlush(any(ProjectGeneratedDoc.class));
    verify(workspace, org.mockito.Mockito.times(1))
        .writeRawFile(
            org.mockito.ArgumentMatchers.eq("p-1"),
            org.mockito.ArgumentMatchers.eq("docs/explanation.md"),
            org.mockito.ArgumentMatchers.eq(validDoc(DocType.EXPLANATION, "Corrected")));
  }

  @Test
  void unsupportedBehaviorClaimIsDeterministicallyDegradedWithoutProviderRetry() {
    wireCacheMiss();
    wireExactSourceRepository(
        "aaa",
        """
        ## Functions
        ### `src/AIPlayer.java`
        - `class AIPlayer`
        - `evaluate`
        ### `src/TicTacToe.java`
        - `class TicTacToe`
        - `checkWin`
        - `generateCacheString`
        """);
    String unsupported =
        validDoc(DocType.EXPLANATION, "First")
            .replace(
                "## Key data flows",
                "`AIPlayer.evaluate` invokes `TicTacToe.checkWin`, while"
                    + " `generateCacheString` is an inactive stub.\n## Key data flows");
    when(llmRouter.callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class)))
        .thenReturn(textResponse(unsupported, "stop"));
    when(docRepo.saveAndFlush(any(ProjectGeneratedDoc.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ProjectGeneratedDoc generated =
        svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC);

    assertThat(generated.getContent())
        .contains(
            "Declared codemap symbols:",
            "codemap evidence does not establish runtime relationships or liveness")
        .contains(
            "## Repositories",
            "## High-level architecture",
            "## Key data flows",
            "## Where to contribute")
        .doesNotContain("invokes", "inactive stub");
    verify(llmRouter, org.mockito.Mockito.times(1))
        .callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class));
  }

  @Test
  void malformedShortProviderOutputRetriesOnceThenFailsTheDocumentQualityGate() {
    wireCacheMiss();
    when(llmRouter.callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class)))
        .thenReturn(textResponse("# Generated", "stop"));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC))
        .isInstanceOf(ProjectDocGeneratorService.InvalidGeneratedDocumentException.class)
        .hasMessageContaining("too short");

    verify(llmRouter, org.mockito.Mockito.times(2))
        .callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class));
    verify(docRepo, never()).saveAndFlush(any(ProjectGeneratedDoc.class));
    verify(workspace, never()).writeRawFile(anyString(), anyString(), anyString());
  }

  @Test
  void explicitSafetyTerminationIsRejectedEvenWhenProviderReturnsNonblankText() {
    wireCacheMiss();
    when(llmRouter.callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class)))
        .thenReturn(textResponse(validDoc(DocType.EXPLANATION, "Unsafe prefix"), "SAFETY"));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC))
        .isInstanceOf(ProjectDocGeneratorService.ProviderNonCompleteGenerationException.class);

    verify(docRepo, never()).saveAndFlush(any(ProjectGeneratedDoc.class));
    verify(workspace, never()).writeRawFile(anyString(), anyString(), anyString());
  }

  @Test
  void abnormalFinishInAnyGenerationRejectsTheWholeDocument() {
    wireCacheMiss();
    when(llmRouter.callTextStrict(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),
            any(LlmExecutionReference.class)))
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(validDoc(DocType.EXPLANATION, "Primary")),
                        ChatGenerationMetadata.builder().finishReason("STOP").build()),
                    new Generation(
                        new AssistantMessage("provider safety diagnostic"),
                        ChatGenerationMetadata.builder().finishReason("SAFETY").build()))));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC))
        .isInstanceOf(ProjectDocGeneratorService.ProviderNonCompleteGenerationException.class);

    verify(docRepo, never()).saveAndFlush(any(ProjectGeneratedDoc.class));
    verify(workspace, never()).writeRawFile(anyString(), anyString(), anyString());
  }

  @Test
  void slackCredentialAndTextualToolProtocolAreBothRejectedBeforePublication() {
    String slack = "xoxb-1234567890-abcdefghijklmnop";
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> svc.assertSafeForCustomer("token=" + slack))
        .isInstanceOf(ProjectDocGeneratorService.UnsafeGeneratedDocumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> svc.assertSafeForCustomer("<tool_call>{\"name\":\"readFile\"}</tool_call>"))
        .isInstanceOf(ProjectDocGeneratorService.UnsafeGeneratedDocumentException.class);
  }

  @Test
  void linkedRepositoryOutsideArchitectureSnapshotFailsBeforeProviderDispatch() {
    UUID repoId = UUID.fromString("00000000-0000-0000-0000-000000000010");
    when(linkRepo.findByProjectId("p-1"))
        .thenReturn(List.of(ProjectRepoLink.builder().projectId("p-1").repoId(repoId).build()));
    when(repoRepo.findById(repoId))
        .thenReturn(
            Optional.of(
                ProjectRepo.builder().id(repoId).githubRepoFullName("owner/newly-linked").build()));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                svc.buildSourceBundle(
                    project("p-1"), archDoc(7, "{\"owner/old-repo\":\"source-sha\"}")))
        .isInstanceOf(ProjectDocGeneratorService.StaleSourceException.class);
    verifyNoInteractions(llmRouter);
  }

  @Test
  void deterministicQualityGateRejectsHeadingMentionsAndMissingRepositoryEvidence() {
    String proseWithHeadingNames =
        """
        # Test
        This paragraph mentions ## Repositories, ## High-level architecture, ## Key data flows,
        and ## Where to contribute without creating any real sections.
        %s
        """
            .formatted("filler ".repeat(100));
    assertThat(
            ProjectGeneratedDocValidator.validate(
                DocType.EXPLANATION, proseWithHeadingNames, java.util.Set.of("owner/repo-a")))
        .hasValueSatisfying(reason -> assertThat(reason).contains("missing required heading"));

    String structurallyValid = validDoc(DocType.EXPLANATION, "Structure");
    assertThat(
            ProjectGeneratedDocValidator.validate(
                DocType.EXPLANATION, structurallyValid, java.util.Set.of("owner/not-present")))
        .hasValueSatisfying(reason -> assertThat(reason).contains("missing linked repository"));
  }

  @Test
  void generatedDocumentFingerprintColumnAndForwardMigrationAreUnbounded() throws Exception {
    var column =
        ProjectGeneratedDoc.class
            .getDeclaredField("sourceShasFingerprint")
            .getAnnotation(jakarta.persistence.Column.class);
    assertThat(column.columnDefinition()).isEqualTo("TEXT");
    String migration =
        java.nio.file.Files.readString(
            java.nio.file.Path.of(
                "src/main/resources/db/migration/V353__durable_project_architecture_jobs.sql"));
    assertThat(migration)
        .contains("ALTER COLUMN source_shas_fingerprint TYPE TEXT")
        .doesNotContain("source_shas_fingerprint TYPE VARCHAR(255)");
  }

  private void wireCacheMiss() {
    String shasJson = "{\"owner/repo-a\":\"aaa\"}";
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(project("p-1")));
    when(architectureService.latest("p-1")).thenReturn(Optional.of(archDoc(7, shasJson)));
    when(docRepo
            .findFirstByProjectIdAndDocTypeAndSourceArchVersionAndSourceShasFingerprintOrderByVersionDesc(
                org.mockito.ArgumentMatchers.eq("p-1"),
                any(DocType.class),
                org.mockito.ArgumentMatchers.eq(7),
                org.mockito.ArgumentMatchers.eq(shasJson)))
        .thenReturn(Optional.empty());
    wireExactSourceRepository("aaa");
    when(modelResolver.resolve("ProjectDocGeneratorService", LlmProvider.ANTHROPIC))
        .thenReturn(
            ResolvedModel.builder()
                .requestedProvider(LlmProvider.ANTHROPIC)
                .actualProvider(LlmProvider.ANTHROPIC)
                .model("claude-test")
                .agentTier(AgentTier.WORKHORSE)
                .build());
    when(executionPolicyFactory.forProject(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.nullable(String.class),
            anyString()))
        .thenReturn(
            new LlmExecutionReference("project-doc-test", LlmExecutionScope.customer("org-1")));
    when(docRepo.findFirstByProjectIdAndDocTypeOrderByVersionDesc(
            org.mockito.ArgumentMatchers.eq("p-1"), any(DocType.class)))
        .thenReturn(Optional.empty());
  }

  private void wireExactSourceRepository(String sha) {
    wireExactSourceRepository(sha, "source map for " + sha);
  }

  private void wireExactSourceRepository(String sha, String content) {
    when(linkRepo.findByProjectId("p-1"))
        .thenReturn(
            List.of(ProjectRepoLink.builder().projectId("p-1").repoId(SOURCE_REPO_ID).build()));
    when(repoRepo.findById(SOURCE_REPO_ID))
        .thenReturn(
            Optional.of(
                ProjectRepo.builder()
                    .id(SOURCE_REPO_ID)
                    .githubRepoFullName("owner/repo-a")
                    .build()));
    when(architectureService.codeMapForSourceSha("p-1", SOURCE_REPO_ID, sha))
        .thenReturn(
            Optional.of(
                ProjectCodeMap.builder()
                    .projectId("p-1")
                    .repoId(SOURCE_REPO_ID)
                    .generatedFromSha(sha)
                    .content(content)
                    .build()));
  }

  private static ChatResponse textResponse(String text, String finishReason) {
    return new ChatResponse(
        java.util.List.of(
            new Generation(
                new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())));
  }

  private static String validDoc(DocType type, String label) {
    String detail =
        (label
                + " explains concrete repository behavior, commands, evidence, and safe next steps."
                + " ")
            .repeat(8);
    String document =
        switch (type) {
          case EXPLANATION ->
              """
              # %s
              %s
              ## Repositories
              - owner/repo-a: application source.
              ## High-level architecture
              %s
              ## Key data flows
              1. Request enters the application and reaches the service.
              ## Where to contribute
              Start with the linked repository and its existing tests.
              """
                  .formatted(label, detail, detail);
          case SETUP ->
              """
              # Setting up %s
              %s
              ## Prerequisites
              - Git and the repository runtime.
              ## Clone
              ```bash
              git clone https://example.invalid/owner/repo-a.git
              ```
              ## Environment variables
              No required variables were proven by the supplied evidence.
              ## First-run per repo
              Follow the repository README and detected package scripts.
              ## Running the full stack locally
              Start the repository service and use its documented health check.
              ## Troubleshooting
              Re-check runtime versions and dependency installation.
              """
                  .formatted(label, detail);
        };
    return document.strip();
  }
}
