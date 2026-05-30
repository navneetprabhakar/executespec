package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.project.entity.ProjectRepo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Unit-tests the code-RAG indexing walk in isolation — no network, no DB, no embeddings provider.
 * {@code WorkspaceInitService} (clone), {@code EmbeddingService}, and {@code CodeChunkStore} are
 * mocked; {@code ChunkingService} is real (pure). A {@link TempDir} stands in for the cloned repo so
 * the real file walk (skip-rules + per-file SHA incremental) is exercised against actual files.
 */
class CodeIndexingServiceTest {

  private final WorkspaceInitService ws = mock(WorkspaceInitService.class);
  private final EmbeddingService embedder = mock(EmbeddingService.class);
  private final CodeChunkStore store = mock(CodeChunkStore.class);
  private final ChunkingService chunker = new ChunkingService(); // real — pure chunking logic

  private CodeIndexingService service() {
    return new CodeIndexingService(ws, chunker, embedder, store);
  }

  private static ProjectRepo repo() {
    return ProjectRepo.builder()
        .id(UUID.randomUUID())
        .githubRepoFullName("acme/widget")
        .lastSyncedSha("deadbeefcafe1234")
        .build();
  }

  @Test
  void indexes_source_and_skips_binary_and_oversized(@TempDir Path root) throws Exception {
    Files.createDirectories(root.resolve("src"));
    Files.writeString(
        root.resolve("src/Foo.java"),
        "public class Foo {\n  void hello() { System.out.println(\"hi\"); }\n}\n");
    Files.write(root.resolve("logo.png"), new byte[] {1, 2, 0, 3, 4}); // binary ext + null byte
    Files.writeString(root.resolve("big.txt"), "a".repeat(300 * 1024)); // > MAX_FILE_BYTES → skip

    when(ws.runWorkspacePath(anyString())).thenReturn(root);
    when(store.indexedFileShas(anyString())).thenReturn(Map.of());
    when(embedder.embed(anyList()))
        .thenAnswer(
            inv -> {
              List<String> in = inv.getArgument(0);
              List<float[]> out = new ArrayList<>();
              for (int i = 0; i < in.size(); i++) out.add(new float[] {0.1f, 0.2f});
              return out;
            });

    ProjectRepo repo = repo();
    service().indexRepo("proj-1", repo, "pat-xyz");

    // Only the source file is embedded + stored — png (binary) and big.txt (oversized) are skipped.
    ArgumentCaptor<String> filePath = ArgumentCaptor.forClass(String.class);
    verify(store, times(1))
        .insertFileChunks(
            eq("proj-1"),
            eq(repo.getId().toString()),
            eq("acme/widget"),
            filePath.capture(),
            anyString(),
            anyList(),
            anyList());
    assertThat(filePath.getValue()).contains("Foo.java");
  }

  @Test
  void incremental_skips_unchanged_file(@TempDir Path root) throws Exception {
    Files.createDirectories(root.resolve("src"));
    Path foo = root.resolve("src/Foo.java");
    Files.writeString(foo, "public class Foo {}\n");
    String relPath = root.relativize(foo).toString();
    String sha = sha256(Files.readAllBytes(foo));

    when(ws.runWorkspacePath(anyString())).thenReturn(root);
    // Already indexed at this exact content SHA → the walk must skip it (no re-embed).
    when(store.indexedFileShas(anyString())).thenReturn(Map.of(relPath, sha));

    service().indexRepo("proj-1", repo(), null);

    verify(store, never())
        .insertFileChunks(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyList(), anyList());
  }

  private static String sha256(byte[] b) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
  }
}
