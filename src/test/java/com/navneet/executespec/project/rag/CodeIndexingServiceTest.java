package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.rag.CodeIndexStateStore.Completion;
import com.navneet.executespec.project.rag.CodeIndexStateStore.Lease;
import com.navneet.executespec.project.rag.CodeIndexStateStore.State;
import com.navneet.executespec.project.rag.CodeIndexStateStore.Status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** Adversarial coverage for deterministic partial/resume, deletion, and lease loss. */
class CodeIndexingServiceTest {

  private final WorkspaceInitService ws = mock(WorkspaceInitService.class);
  private final EmbeddingService embedder = mock(EmbeddingService.class);
  private final CodeChunkStore chunks = mock(CodeChunkStore.class);
  private final CodeIndexStateStore states = mock(CodeIndexStateStore.class);
  private final ChunkingService chunker = new ChunkingService();
  private final ProjectRepo repo = repo();
  private final Lease lease =
      new Lease(
          "proj-1",
          repo.getId(),
          repo.getLastSyncedSha(),
          "node:a",
          7L,
          Instant.now().plusSeconds(60));

  @BeforeEach
  void defaults() {
    when(states.get("proj-1", repo.getId())).thenReturn(Optional.empty());
    when(states.tryAcquire(
            eq("proj-1"), eq(repo.getId()), eq(repo.getLastSyncedSha()), anyString(), any()))
        .thenReturn(Optional.of(lease));
    when(states.renew(eq(lease), any())).thenReturn(true);
    when(states.complete(eq(lease), any())).thenReturn(true);
    when(chunks.indexedFileShas("proj-1", repo.getId().toString())).thenReturn(Map.of());
    when(embedder.availability("proj-1"))
        .thenReturn(
            new EmbeddingPolicyResolver.Availability(
                EmbeddingPolicyResolver.Mode.SEMANTIC,
                true,
                true,
                "openai",
                EmbeddingService.DEFAULT_MODEL,
                "AVAILABLE"));
    when(embedder.embedForProject(
            anyString(), anyList(), anyString(), anyString(), anyString(), nullable(String.class)))
        .thenAnswer(
            inv -> {
              List<String> input = inv.getArgument(1);
              List<float[]> result = new ArrayList<>();
              for (int i = 0; i < input.size(); i++) result.add(new float[] {0.1f, 0.2f});
              return result;
            });
  }

  private CodeIndexingService service(int cap) {
    return new CodeIndexingService(ws, chunker, embedder, chunks, states, cap);
  }

  @Test
  void indexes_source_and_reports_binary_and_oversized_as_skipped(@TempDir Path root)
      throws Exception {
    Files.createDirectories(root.resolve("src"));
    Files.writeString(root.resolve("src/Foo.java"), "public class Foo { void hello() {} }\n");
    Files.write(root.resolve("logo.png"), new byte[] {1, 2, 0, 3});
    Files.writeString(root.resolve("big.txt"), "a".repeat(300 * 1024));
    when(ws.runWorkspacePath(anyString())).thenReturn(root);

    var result = service(4000).indexRepo("proj-1", repo, null, null, "member-1");

    assertThat(result.outcome()).isEqualTo(CodeIndexingService.Outcome.READY);
    verify(chunks)
        .replaceFileChunks(
            eq(lease), eq("acme/widget"), eq("src/Foo.java"), anyString(), anyList(), anyList());
    ArgumentCaptor<Completion> completion = ArgumentCaptor.forClass(Completion.class);
    verify(states).complete(eq(lease), completion.capture());
    assertThat(completion.getValue().eligibleFiles()).isEqualTo(1);
    assertThat(completion.getValue().indexedFiles()).isEqualTo(1);
    assertThat(completion.getValue().skippedFiles()).isEqualTo(2);
    assertThat(completion.getValue().coveragePercent()).isEqualTo(100.0);
    assertThat(completion.getValue().reason()).isEqualTo("FULL_ELIGIBLE_SOURCE_COVERAGE");
    verify(embedder)
        .embedForProject(
            eq("proj-1"),
            anyList(),
            eq("ProjectCodeEmbedding"),
            eq("PROJECT_REPOSITORY"),
            eq(repo.getId().toString()),
            eq("member-1"));
  }

  @Test
  void sensitivePathsAndCredentialContentNeverReachEmbeddingOrChunkStorage(@TempDir Path root)
      throws Exception {
    Files.createDirectories(root.resolve("src"));
    Files.writeString(root.resolve(".env"), "OPENAI_API_KEY=sk-" + "a".repeat(40));
    Files.writeString(
        root.resolve("src/fixture.txt"),
        "-----BEGIN PRIVATE KEY-----\n" + "not-real-but-sensitive\n-----END PRIVATE KEY-----\n");
    Files.writeString(root.resolve("src/Safe.java"), "public class Safe {}\n");
    when(ws.runWorkspacePath(anyString())).thenReturn(root);

    var result = service(4000).indexRepo("proj-1", repo, null, null, "member-1");

    assertThat(result.outcome()).isEqualTo(CodeIndexingService.Outcome.READY);
    verify(chunks)
        .replaceFileChunks(
            eq(lease), eq("acme/widget"), eq("src/Safe.java"), anyString(), anyList(), anyList());
    verify(chunks, never())
        .replaceFileChunks(
            eq(lease), eq("acme/widget"), eq(".env"), anyString(), anyList(), anyList());
    verify(chunks, never())
        .replaceFileChunks(
            eq(lease), eq("acme/widget"), eq("src/fixture.txt"), anyString(), anyList(), anyList());
    ArgumentCaptor<Completion> completion = ArgumentCaptor.forClass(Completion.class);
    verify(states).complete(eq(lease), completion.capture());
    assertThat(completion.getValue().eligibleFiles()).isEqualTo(1);
    assertThat(completion.getValue().skippedFiles()).isEqualTo(2);
  }

  @Test
  void unchanged_file_is_covered_without_reembedding(@TempDir Path root) throws Exception {
    Files.createDirectories(root.resolve("src"));
    Path foo = root.resolve("src/Foo.java");
    Files.writeString(foo, "public class Foo {}\n");
    when(ws.runWorkspacePath(anyString())).thenReturn(root);
    when(chunks.indexedFileShas("proj-1", repo.getId().toString()))
        .thenReturn(Map.of("src/Foo.java", sha256(Files.readAllBytes(foo))));

    assertThat(service(4000).indexRepo("proj-1", repo, null).outcome())
        .isEqualTo(CodeIndexingService.Outcome.READY);

    verify(embedder, never())
        .embedForProject(
            anyString(), anyList(), anyString(), anyString(), anyString(), nullable(String.class));
    verify(chunks, never())
        .replaceFileChunks(any(), anyString(), anyString(), anyString(), anyList(), anyList());
    ArgumentCaptor<Completion> completion = ArgumentCaptor.forClass(Completion.class);
    verify(states).complete(eq(lease), completion.capture());
    assertThat(completion.getValue().unchangedFiles()).isEqualTo(1);
    assertThat(completion.getValue().indexedFiles()).isEqualTo(1);
  }

  @Test
  void deleted_file_chunks_are_removed_under_the_current_fence(@TempDir Path root) {
    when(ws.runWorkspacePath(anyString())).thenReturn(root);
    when(chunks.indexedFileShas("proj-1", repo.getId().toString()))
        .thenReturn(Map.of("src/Removed.java", "old-sha"));
    when(chunks.deleteFile(lease, "src/Removed.java")).thenReturn(true);

    service(4000).indexRepo("proj-1", repo, null);

    verify(chunks).deleteFile(lease, "src/Removed.java");
    ArgumentCaptor<Completion> completion = ArgumentCaptor.forClass(Completion.class);
    verify(states).complete(eq(lease), completion.capture());
    assertThat(completion.getValue().deletedFiles()).isEqualTo(1);
    assertThat(completion.getValue().status()).isEqualTo(Status.PARTIAL);
    assertThat(completion.getValue().reason()).isEqualTo("NO_ELIGIBLE_SOURCE_FILES");
  }

  @Test
  void refresh_cap_is_visible_partial_and_next_pass_can_resume(@TempDir Path root)
      throws Exception {
    Files.createDirectories(root.resolve("src"));
    Files.writeString(root.resolve("src/A.java"), "class A {}\n");
    Files.writeString(root.resolve("src/B.java"), "class B {}\n");
    when(ws.runWorkspacePath(anyString())).thenReturn(root);

    var result = service(1).indexRepo("proj-1", repo, null);

    assertThat(result.outcome()).isEqualTo(CodeIndexingService.Outcome.PARTIAL);
    ArgumentCaptor<Completion> completion = ArgumentCaptor.forClass(Completion.class);
    verify(states).complete(eq(lease), completion.capture());
    assertThat(completion.getValue().status()).isEqualTo(Status.PARTIAL);
    assertThat(completion.getValue().reason()).isEqualTo("REFRESH_LIMIT_REACHED_RESUMABLE");
    assertThat(completion.getValue().eligibleFiles()).isEqualTo(2);
    assertThat(completion.getValue().indexedFiles()).isEqualTo(1);
    assertThat(completion.getValue().coveragePercent()).isEqualTo(50.0);
  }

  @Test
  void stale_or_expired_worker_stops_before_embedding_or_publication(@TempDir Path root)
      throws Exception {
    Files.writeString(root.resolve("A.java"), "class A {}\n");
    when(ws.runWorkspacePath(anyString())).thenReturn(root);
    when(states.renew(eq(lease), any())).thenReturn(false);

    var result = service(4000).indexRepo("proj-1", repo, null);

    assertThat(result.outcome()).isEqualTo(CodeIndexingService.Outcome.LEASE_LOST);
    verify(embedder, never())
        .embedForProject(
            anyString(), anyList(), anyString(), anyString(), anyString(), nullable(String.class));
    verify(chunks, never())
        .replaceFileChunks(any(), anyString(), anyString(), anyString(), anyList(), anyList());
    verify(states, never()).complete(eq(lease), any());
    verify(states, never()).fail(eq(lease), anyString());
  }

  @Test
  void exact_ready_state_must_match_project_repo_and_source_sha() {
    State ready = readyState("proj-1", repo.getId(), repo.getLastSyncedSha());
    when(states.get("proj-1", repo.getId())).thenReturn(Optional.of(ready));

    assertThat(service(4000).isIndexed("proj-1", repo)).isTrue();
    verify(states, atLeastOnce()).reconcile("proj-1", repo.getId(), repo.getLastSyncedSha());

    ProjectRepo moved = repo();
    moved.setId(repo.getId());
    moved.setLastSyncedSha("cafebabecafe9999");
    assertThat(service(4000).isIndexed("proj-1", moved)).isFalse();
    // A different tenant context has no state even for the same registry repository.
    assertThat(service(4000).isIndexed("proj-2", repo)).isFalse();
  }

  @Test
  void unsupportedSelectedProviderFailsClosedBeforeCloneOrGlobalEmbedding() throws Exception {
    when(embedder.availability("proj-1"))
        .thenReturn(
            new EmbeddingPolicyResolver.Availability(
                EmbeddingPolicyResolver.Mode.LEXICAL,
                false,
                true,
                "deepseek",
                null,
                "SELECTED_PROVIDER_HAS_NO_COMPATIBLE_EMBEDDING_MODEL"));

    var result = service(4000).indexRepo("proj-1", repo, null);

    assertThat(result.outcome()).isEqualTo(CodeIndexingService.Outcome.UNSUPPORTED_SOURCE);
    assertThat(result.readiness().semanticSearchAvailable()).isFalse();
    assertThat(result.readiness().lexicalFallbackAvailable()).isTrue();
    assertThat(result.readiness().reason())
        .isEqualTo("EMBEDDING_SELECTED_PROVIDER_HAS_NO_COMPATIBLE_EMBEDDING_MODEL");
    verify(states).fail(lease, "EMBEDDING_SELECTED_PROVIDER_HAS_NO_COMPATIBLE_EMBEDDING_MODEL");
    verify(ws, never()).cloneFromRepo(anyString(), anyString(), any(), any());
    verify(embedder, never())
        .embedForProject(
            anyString(), anyList(), anyString(), anyString(), anyString(), nullable(String.class));
  }

  @Test
  void bitbucketCloneUsesApiTokenGitIdentityRatherThanRestEmail(@TempDir Path root)
      throws Exception {
    repo.setScmProvider("BITBUCKET");
    repo.setScmCloneUrl("https://bitbucket.org/acme/widget.git");
    when(ws.runWorkspacePath(anyString())).thenReturn(root);

    service(4000).indexRepo("proj-1", repo, "owner@acme.example", "scoped-api-token", "member-1");

    verify(ws)
        .cloneFromRepo(
            anyString(),
            eq("https://bitbucket.org/acme/widget.git"),
            eq("x-bitbucket-api-token-auth"),
            eq("scoped-api-token"));
  }

  private static ProjectRepo repo() {
    return ProjectRepo.builder()
        .id(UUID.randomUUID())
        .githubRepoFullName("acme/widget")
        .lastSyncedSha("deadbeefcafe1234")
        .build();
  }

  private static State readyState(String projectId, UUID repoId, String sha) {
    Instant now = Instant.now();
    return new State(
        projectId,
        repoId,
        sha,
        Status.READY,
        "FULL_SOURCE_COVERAGE",
        1,
        1,
        1,
        1,
        0,
        0,
        0,
        0,
        1,
        100.0,
        false,
        null,
        3,
        null,
        now,
        now,
        now);
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
