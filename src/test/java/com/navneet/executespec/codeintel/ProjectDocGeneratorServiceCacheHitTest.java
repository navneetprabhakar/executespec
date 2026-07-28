package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.codeintel.repository.ProjectGeneratedDocRepository;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
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
            executionPolicyFactory);
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
        .content("# " + type.name() + " v" + version)
        .sourceArchVersion(archVersion)
        .sourceShasFingerprint(fingerprint)
        .build();
  }

  @Test
  void cacheHit_returnsCachedDoc_andSkipsLlmCall() {
    String shasJson = "{\"owner/repo-a\":\"aaa\"}";
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(project("p-1")));
    when(architectureService.latest("p-1")).thenReturn(Optional.of(archDoc(7, shasJson)));

    ProjectGeneratedDoc cached = generatedDoc(DocType.EXPLANATION, 3, 7, shasJson);
    when(docRepo
            .findFirstByProjectIdAndDocTypeAndSourceArchVersionAndSourceShasFingerprintOrderByVersionDesc(
                "p-1", DocType.EXPLANATION, 7, shasJson))
        .thenReturn(Optional.of(cached));

    ProjectGeneratedDoc result =
        svc.generate("p-1", DocType.EXPLANATION, "actor-1", LlmProvider.ANTHROPIC);

    assertThat(result).isSameAs(cached);
    // The LLM router must NOT have been called — that's the whole point of the short-circuit.
    verify(llmRouter, never()).callText(any(), any(), any(), any(), any(), any());
    // ModelResolver should also not be called — we never even resolved a model.
    verify(modelResolver, never()).resolve(any(), any());
    // No new doc saved.
    verify(docRepo, never()).save(any(ProjectGeneratedDoc.class));
  }

  @Test
  void cacheHit_emptyContent_fallsThrough() {
    // Defensive: if the cached row happens to have null/blank content (a bug or partial write
    // in some prior code path), we must NOT short-circuit on it — fall through to regenerate.
    String shasJson = "{\"owner/repo-a\":\"aaa\"}";
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(project("p-1")));
    when(architectureService.latest("p-1")).thenReturn(Optional.of(archDoc(7, shasJson)));

    ProjectGeneratedDoc emptyCached = generatedDoc(DocType.EXPLANATION, 3, 7, shasJson);
    emptyCached.setContent(""); // simulate empty-content row
    when(docRepo
            .findFirstByProjectIdAndDocTypeAndSourceArchVersionAndSourceShasFingerprintOrderByVersionDesc(
                "p-1", DocType.EXPLANATION, 7, shasJson))
        .thenReturn(Optional.of(emptyCached));

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

    assertThat(capped).hasSizeLessThanOrEqualTo(60_000 + 120);
    assertThat(capped).contains("additional repository evidence omitted");
    String evidence = capped.substring(0, capped.indexOf("\n\n[truncated"));
    assertThat(evidence).doesNotEndWith("recor");
    assertThat(evidence).endsWith("\n");
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
    when(linkRepo.findByProjectId("p-1")).thenReturn(java.util.List.of());
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
    when(llmRouter.callText(
            anyString(),
            anyString(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            any(LlmExecutionReference.class)))
        .thenReturn(
            new ChatResponse(
                java.util.List.of(
                    new Generation(AssistantMessage.builder().content("# Generated").build()))));
    when(docRepo.findFirstByProjectIdAndDocTypeOrderByVersionDesc("p-1", DocType.EXPLANATION))
        .thenReturn(Optional.empty());
    when(docRepo.save(any(ProjectGeneratedDoc.class)))
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
    assertThat(generated.getContent()).isEqualTo("# Generated");
    assertThat(generated.getSourceArchVersion()).isEqualTo(7);
    assertThat(generated.getSourceShasFingerprint()).isEqualTo(shasJson);
  }
}
