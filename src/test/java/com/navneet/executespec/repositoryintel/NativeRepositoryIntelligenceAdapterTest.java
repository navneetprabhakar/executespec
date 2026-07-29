package com.navneet.executespec.repositoryintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.navneet.executespec.project.rag.CodeIndexStateStore;
import com.navneet.executespec.tool.RepositorySearchService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeRepositoryIntelligenceAdapterTest {

  @TempDir Path repository;

  private RepositorySearchService searchService;
  private CodeIndexStateStore indexStateStore;
  private NativeRepositoryIntelligenceAdapter adapter;

  @BeforeEach
  void setUp() {
    searchService = new RepositorySearchService();
    indexStateStore = mock(CodeIndexStateStore.class);
    adapter =
        new NativeRepositoryIntelligenceAdapter(
            searchService, indexStateStore, query -> handle(query, repository));
  }

  @Test
  void returnsExactAndHeuristicSymbolEvidenceWithHonestProvenance() throws Exception {
    Files.createDirectories(repository.resolve("src"));
    Files.writeString(
        repository.resolve("src/Widget.java"),
        "package sample;\npublic class Widget {\n  Widget build() { return new Widget(); }\n}\n");
    RepositoryQuery query =
        query(
            snapshot("Widget"),
            Set.of(RepositoryCapability.EXACT_TEXT, RepositoryCapability.SYMBOL),
            false);

    RepositoryEvidenceBatch result = adapter.query(query);

    assertThat(result.outcomes().get(RepositoryCapability.EXACT_TEXT).state())
        .isEqualTo(RepositoryCapabilityState.AVAILABLE_HIT);
    assertThat(result.outcomes().get(RepositoryCapability.SYMBOL).state())
        .isEqualTo(RepositoryCapabilityState.AVAILABLE_HIT);
    assertThat(result.evidence())
        .extracting(RepositoryEvidence::provenance)
        .contains(RepositoryEvidenceProvenance.EXACT)
        .contains(RepositoryEvidenceProvenance.HEURISTIC_SYMBOL)
        .doesNotContain(RepositoryEvidenceProvenance.AST_EXTRACTED);
    assertThat(result.evidence()).allMatch(hit -> hit.path().equals("src/Widget.java"));
    verifyNoInteractions(indexStateStore);
  }

  @Test
  void rejectsAllEvidenceAfterWorkingTreeMutation() throws Exception {
    Path source = repository.resolve("Widget.java");
    Files.writeString(source, "class Widget {}\n");
    RepositorySnapshotId before = snapshot("Widget");
    Files.writeString(source, "class Widget { int changed; }\n");
    RepositoryQuery query =
        query(before, Set.of(RepositoryCapability.EXACT_TEXT, RepositoryCapability.SYMBOL), false);

    RepositoryEvidenceBatch result = adapter.query(query);

    assertThat(result.evidence()).isEmpty();
    assertThat(result.outcomes().values())
        .allMatch(outcome -> outcome.state() == RepositoryCapabilityState.STALE_REJECTED)
        .allMatch(outcome -> "INVENTORY_FINGERPRINT_CHANGED".equals(outcome.reason()));
    verifyNoInteractions(indexStateStore);
  }

  @Test
  void rejectsAllEvidenceWhenRepositoryMutatesBetweenProbeAndCapability() {
    RepositorySearchService changingSearch = mock(RepositorySearchService.class);
    RepositorySearchService.SearchResult stableProbe =
        new RepositorySearchService.SearchResult(
            "abc123", "inventory-before", "query", java.util.List.of(), 0, false, 0, "native");
    RepositorySearchService.SearchResult capabilityRead =
        new RepositorySearchService.SearchResult(
            "abc123", "inventory-after", "query", java.util.List.of(), 0, false, 0, "native");
    when(changingSearch.search(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(stableProbe, capabilityRead);
    RepositorySnapshotId expected =
        new RepositorySnapshotId(
            "project-1",
            UUID.randomUUID().toString(),
            "abc123",
            null,
            "inventory-before",
            "native",
            1,
            Instant.EPOCH,
            RepositoryFreshness.CURRENT);
    NativeRepositoryIntelligenceAdapter isolated =
        new NativeRepositoryIntelligenceAdapter(
            changingSearch, indexStateStore, query -> handle(query, repository));

    RepositoryEvidenceBatch result =
        isolated.query(query(expected, Set.of(RepositoryCapability.SYMBOL), false));

    assertThat(result.evidence()).isEmpty();
    assertThat(result.outcomes().get(RepositoryCapability.SYMBOL))
        .extracting(CapabilityOutcome::state, CapabilityOutcome::reason)
        .containsExactly(RepositoryCapabilityState.STALE_REJECTED, "INVENTORY_FINGERPRINT_CHANGED");
  }

  @Test
  void unknownSnapshotIsUnavailableWithoutReadingWorkspaceOrIndex() {
    RepositoryRootResolver resolver = mock(RepositoryRootResolver.class);
    NativeRepositoryIntelligenceAdapter isolated =
        new NativeRepositoryIntelligenceAdapter(searchService, indexStateStore, resolver);
    RepositorySnapshotId unknown =
        new RepositorySnapshotId(
            "project-1",
            UUID.randomUUID().toString(),
            null,
            null,
            null,
            "native",
            1,
            Instant.EPOCH,
            RepositoryFreshness.UNKNOWN);
    RepositoryQuery query = query(unknown, Set.of(RepositoryCapability.EXACT_TEXT), false);

    RepositoryEvidenceBatch result = isolated.query(query);

    assertThat(result.evidence()).isEmpty();
    assertThat(result.outcomes().get(RepositoryCapability.EXACT_TEXT))
        .extracting(CapabilityOutcome::state, CapabilityOutcome::reason)
        .containsExactly(RepositoryCapabilityState.UNAVAILABLE, "SNAPSHOT_UNKNOWN");
    verifyNoInteractions(resolver, indexStateStore);
  }

  @Test
  void explicitlyStaleSnapshotRemainsDistinctFromUnverifiableIdentity() {
    RepositoryRootResolver resolver = mock(RepositoryRootResolver.class);
    NativeRepositoryIntelligenceAdapter isolated =
        new NativeRepositoryIntelligenceAdapter(searchService, indexStateStore, resolver);
    RepositorySnapshotId stale =
        new RepositorySnapshotId(
            "project-1",
            UUID.randomUUID().toString(),
            "abc123",
            null,
            "inventory",
            "native",
            1,
            Instant.EPOCH,
            RepositoryFreshness.STALE);

    RepositoryEvidenceBatch result =
        isolated.query(query(stale, Set.of(RepositoryCapability.EXACT_TEXT), false));

    assertThat(result.outcomes().get(RepositoryCapability.EXACT_TEXT))
        .extracting(CapabilityOutcome::state, CapabilityOutcome::reason)
        .containsExactly(RepositoryCapabilityState.STALE_REJECTED, "SNAPSHOT_STALE");
    verifyNoInteractions(resolver, indexStateStore);
  }

  @Test
  void rejectsCommitOnlySnapshotBeforeReadingMutableWorkspace() {
    RepositoryRootResolver resolver = mock(RepositoryRootResolver.class);
    NativeRepositoryIntelligenceAdapter isolated =
        new NativeRepositoryIntelligenceAdapter(searchService, indexStateStore, resolver);
    RepositorySnapshotId commitOnly =
        new RepositorySnapshotId(
            "project-1",
            UUID.randomUUID().toString(),
            "abc123",
            null,
            null,
            "native",
            1,
            Instant.EPOCH,
            RepositoryFreshness.CURRENT);

    RepositoryEvidenceBatch result =
        isolated.query(query(commitOnly, Set.of(RepositoryCapability.EXACT_TEXT), false));

    assertThat(result.evidence()).isEmpty();
    assertThat(result.outcomes().get(RepositoryCapability.EXACT_TEXT))
        .extracting(CapabilityOutcome::state, CapabilityOutcome::reason)
        .containsExactly(RepositoryCapabilityState.UNAVAILABLE, "INVENTORY_FINGERPRINT_REQUIRED");
    verifyNoInteractions(resolver, indexStateStore);
  }

  @Test
  void rejectsUnverifiableDirtyFingerprintEvenWhenInventoryIsPresent() {
    RepositoryRootResolver resolver = mock(RepositoryRootResolver.class);
    NativeRepositoryIntelligenceAdapter isolated =
        new NativeRepositoryIntelligenceAdapter(searchService, indexStateStore, resolver);
    RepositorySnapshotId inconsistent =
        new RepositorySnapshotId(
            "project-1",
            UUID.randomUUID().toString(),
            "abc123",
            "different-dirty-claim",
            "inventory",
            "native",
            1,
            Instant.EPOCH,
            RepositoryFreshness.CURRENT);

    RepositoryEvidenceBatch result =
        isolated.query(query(inconsistent, Set.of(RepositoryCapability.EXACT_TEXT), false));

    assertThat(result.outcomes().get(RepositoryCapability.EXACT_TEXT))
        .extracting(CapabilityOutcome::state, CapabilityOutcome::reason)
        .containsExactly(RepositoryCapabilityState.UNAVAILABLE, "DIRTY_WORKTREE_NOT_CERTIFIABLE");
    verifyNoInteractions(resolver, indexStateStore);
  }

  @Test
  void rejectsForeignEngineAndUnsupportedSchemaBeforeReadingWorkspace() {
    RepositoryRootResolver resolver = mock(RepositoryRootResolver.class);
    NativeRepositoryIntelligenceAdapter isolated =
        new NativeRepositoryIntelligenceAdapter(searchService, indexStateStore, resolver);
    RepositorySnapshotId foreignEngine =
        new RepositorySnapshotId(
            "project-1",
            UUID.randomUUID().toString(),
            "abc123",
            null,
            "inventory",
            "graphify",
            1,
            Instant.EPOCH,
            RepositoryFreshness.CURRENT);
    RepositorySnapshotId futureSchema =
        new RepositorySnapshotId(
            "project-1",
            UUID.randomUUID().toString(),
            "abc123",
            null,
            "inventory",
            "native",
            2,
            Instant.EPOCH,
            RepositoryFreshness.CURRENT);

    RepositoryEvidenceBatch engineResult =
        isolated.query(query(foreignEngine, Set.of(RepositoryCapability.EXACT_TEXT), false));
    RepositoryEvidenceBatch schemaResult =
        isolated.query(query(futureSchema, Set.of(RepositoryCapability.EXACT_TEXT), false));

    assertThat(engineResult.outcomes().get(RepositoryCapability.EXACT_TEXT))
        .extracting(CapabilityOutcome::state, CapabilityOutcome::reason)
        .containsExactly(RepositoryCapabilityState.UNAVAILABLE, "SNAPSHOT_ENGINE_UNSUPPORTED");
    assertThat(schemaResult.outcomes().get(RepositoryCapability.EXACT_TEXT))
        .extracting(CapabilityOutcome::state, CapabilityOutcome::reason)
        .containsExactly(RepositoryCapabilityState.UNAVAILABLE, "SNAPSHOT_SCHEMA_UNSUPPORTED");
    verifyNoInteractions(resolver, indexStateStore);
  }

  @Test
  void rejectsResolverHandleWhoseActorTenantProjectRepositoryOrSnapshotDoesNotMatch() {
    RepositorySnapshotId snapshot =
        new RepositorySnapshotId(
            "project-1",
            UUID.randomUUID().toString(),
            "abc123",
            null,
            "inventory",
            "native",
            1,
            Instant.EPOCH,
            RepositoryFreshness.CURRENT);
    RepositoryQuery query = query(snapshot, Set.of(RepositoryCapability.EXACT_TEXT), false);
    RepositoryRootResolver forgedResolver =
        ignored ->
            new AuthorizedRepositoryHandle(
                "another-actor",
                query.accessContext().tenantOrgId(),
                query.projectId(),
                query.repositoryId(),
                query.expectedSnapshot().stableId(),
                query.expectedSnapshot().commitSha(),
                repository.toAbsolutePath());
    NativeRepositoryIntelligenceAdapter isolated =
        new NativeRepositoryIntelligenceAdapter(searchService, indexStateStore, forgedResolver);

    RepositoryEvidenceBatch result = isolated.query(query);

    assertThat(result.evidence()).isEmpty();
    assertThat(result.outcomes().get(RepositoryCapability.EXACT_TEXT))
        .extracting(CapabilityOutcome::state, CapabilityOutcome::reason)
        .containsExactly(RepositoryCapabilityState.FAILED, "REPOSITORY_ROOT_UNAVAILABLE");
    verifyNoInteractions(indexStateStore);
  }

  @Test
  void semanticCapabilityNeverIndexesOrEmbedsAndReportsWhyItCannotSearch() throws Exception {
    Files.writeString(repository.resolve("Widget.java"), "class Widget {}\n");
    RepositorySnapshotId snapshot = snapshot("Widget");
    RepositoryQuery disallowed = query(snapshot, Set.of(RepositoryCapability.CODE_SEMANTIC), false);

    RepositoryEvidenceBatch denied = adapter.query(disallowed);

    assertThat(denied.outcomes().get(RepositoryCapability.CODE_SEMANTIC).reason())
        .isEqualTo("PROVIDER_RETRIEVAL_NOT_ALLOWED");
    verifyNoInteractions(indexStateStore);

    UUID repoId = UUID.fromString(snapshot.repositoryId());
    CodeIndexStateStore.State ready = readyState(repoId, "WORKTREE_ONLY");
    org.mockito.Mockito.when(indexStateStore.get("project-1", repoId))
        .thenReturn(Optional.of(ready));
    RepositoryQuery allowed = query(snapshot, Set.of(RepositoryCapability.CODE_SEMANTIC), true);

    RepositoryEvidenceBatch noRetriever = adapter.query(allowed);

    assertThat(noRetriever.evidence()).isEmpty();
    assertThat(noRetriever.outcomes().get(RepositoryCapability.CODE_SEMANTIC).reason())
        .isEqualTo("SEMANTIC_RETRIEVER_NOT_BOUND_TO_NATIVE_ADAPTER");
    verify(indexStateStore).get("project-1", repoId);
    verify(indexStateStore, never())
        .reconcile(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void unsupportedCapabilitiesAreExplicitRatherThanEmptySuccess() throws Exception {
    Files.writeString(repository.resolve("Widget.java"), "class Widget {}\n");
    RepositoryQuery query =
        query(
            snapshot("Widget"),
            Set.of(RepositoryCapability.AST_DECLARATION, RepositoryCapability.ARCHITECTURE),
            false);

    RepositoryEvidenceBatch result = adapter.query(query);

    assertThat(result.evidence()).isEmpty();
    assertThat(result.outcomes())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                RepositoryCapability.AST_DECLARATION,
                new CapabilityOutcome(
                    RepositoryCapability.AST_DECLARATION,
                    RepositoryCapabilityState.UNAVAILABLE,
                    "CAPABILITY_NOT_SUPPORTED_BY_NATIVE_ADAPTER",
                    0,
                    false),
                RepositoryCapability.ARCHITECTURE,
                new CapabilityOutcome(
                    RepositoryCapability.ARCHITECTURE,
                    RepositoryCapabilityState.UNAVAILABLE,
                    "CAPABILITY_NOT_SUPPORTED_BY_NATIVE_ADAPTER",
                    0,
                    false)));
  }

  @Test
  void interruptsBlockingRootResolutionAtTheSharedMonotonicDeadline() throws Exception {
    Files.writeString(repository.resolve("Widget.java"), "class Widget {}\n");
    RepositorySnapshotId snapshot = snapshot("Widget");
    CountDownLatch interrupted = new CountDownLatch(1);
    RepositoryRootResolver blockingResolver =
        ignored -> {
          try {
            Thread.sleep(10_000);
          } catch (InterruptedException expected) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
          }
          return handle(ignored, repository);
        };
    NativeRepositoryIntelligenceAdapter isolated =
        new NativeRepositoryIntelligenceAdapter(searchService, indexStateStore, blockingResolver);
    QueryBudget bounded = new QueryBudget(2, 10, 10, 1_000, 250, 100);
    RepositoryQuery query =
        new RepositoryQuery(
            "project-1",
            snapshot.repositoryId(),
            "Widget",
            Set.of(RepositoryCapability.EXACT_TEXT),
            null,
            "*.java",
            snapshot,
            bounded,
            false,
            access());

    long started = System.nanoTime();
    RepositoryEvidenceBatch result = isolated.query(query);
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(elapsedMillis).isLessThan(2_000);
    assertThat(result.evidence()).isEmpty();
    assertThat(result.outcomes().get(RepositoryCapability.EXACT_TEXT))
        .extracting(CapabilityOutcome::state, CapabilityOutcome::reason)
        .containsExactly(RepositoryCapabilityState.FAILED, "QUERY_TIMEOUT");
    assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void interruptsBlockingCapabilitySearchAndReturnsExplicitPartialTimeout() throws Exception {
    RepositorySearchService blockingSearch = mock(RepositorySearchService.class);
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch interrupted = new CountDownLatch(1);
    RepositorySearchService.SearchResult probe =
        new RepositorySearchService.SearchResult(
            "abc123", "inventory", "query", java.util.List.of(), 0, false, 0, "native");
    when(blockingSearch.search(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            ignored -> {
              if (calls.incrementAndGet() == 1) return probe;
              try {
                Thread.sleep(10_000);
              } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
              }
              return probe;
            });
    RepositorySnapshotId snapshot =
        new RepositorySnapshotId(
            "project-1",
            UUID.randomUUID().toString(),
            "abc123",
            null,
            "inventory",
            "native",
            1,
            Instant.EPOCH,
            RepositoryFreshness.CURRENT);
    RepositoryQuery query =
        new RepositoryQuery(
            "project-1",
            snapshot.repositoryId(),
            "Widget",
            Set.of(RepositoryCapability.SYMBOL),
            null,
            "*.java",
            snapshot,
            new QueryBudget(2, 10, 10, 1_000, 250, 100),
            false,
            access());
    NativeRepositoryIntelligenceAdapter isolated =
        new NativeRepositoryIntelligenceAdapter(
            blockingSearch, indexStateStore, request -> handle(request, repository));

    RepositoryEvidenceBatch result = isolated.query(query);

    assertThat(result.truncated()).isTrue();
    assertThat(result.outcomes().get(RepositoryCapability.SYMBOL))
        .extracting(
            CapabilityOutcome::state, CapabilityOutcome::reason, CapabilityOutcome::truncated)
        .containsExactly(RepositoryCapabilityState.PARTIAL, "QUERY_TIMEOUT", true);
    assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void callerInterruptionPropagatesAndCancelsInFlightResolution() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch childInterrupted = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean callerInterruptRestored = new AtomicBoolean();
    RepositoryRootResolver blockingResolver =
        ignored -> {
          entered.countDown();
          try {
            Thread.sleep(10_000);
          } catch (InterruptedException expected) {
            childInterrupted.countDown();
            Thread.currentThread().interrupt();
          }
          return handle(ignored, repository);
        };
    NativeRepositoryIntelligenceAdapter isolated =
        new NativeRepositoryIntelligenceAdapter(searchService, indexStateStore, blockingResolver);
    RepositorySnapshotId snapshot =
        new RepositorySnapshotId(
            "project-1",
            UUID.randomUUID().toString(),
            "abc123",
            null,
            "inventory",
            "native",
            1,
            Instant.EPOCH,
            RepositoryFreshness.CURRENT);
    Thread caller =
        Thread.ofPlatform()
            .unstarted(
                () -> {
                  try {
                    isolated.query(query(snapshot, Set.of(RepositoryCapability.EXACT_TEXT), false));
                  } catch (Throwable thrown) {
                    failure.set(thrown);
                    callerInterruptRestored.set(Thread.currentThread().isInterrupted());
                  }
                });

    caller.start();
    assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
    caller.interrupt();
    caller.join(2_000);

    assertThat(caller.isAlive()).isFalse();
    assertThat(failure.get())
        .isInstanceOf(
            NativeRepositoryIntelligenceAdapter.RepositoryQueryInterruptedException.class);
    assertThat(callerInterruptRestored).isTrue();
    assertThat(childInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
  }

  private RepositorySnapshotId snapshot(String text) {
    RepositorySearchService.SearchResult result =
        searchService.search(
            repository,
            new RepositorySearchService.SearchRequest(
                text, RepositorySearchService.SearchMode.EXACT, true, null, null, 1, 0));
    return new RepositorySnapshotId(
        "project-1",
        UUID.randomUUID().toString(),
        result.repositorySha(),
        null,
        result.inventoryFingerprint(),
        "native",
        1,
        Instant.EPOCH,
        RepositoryFreshness.CURRENT);
  }

  private static RepositoryQuery query(
      RepositorySnapshotId snapshot,
      Set<RepositoryCapability> capabilities,
      boolean providerAllowed) {
    return new RepositoryQuery(
        "project-1",
        snapshot.repositoryId(),
        "Widget",
        capabilities,
        null,
        "*.java",
        snapshot,
        QueryBudget.DEFAULT,
        providerAllowed,
        access());
  }

  private static RepositoryAccessContext access() {
    return new RepositoryAccessContext("actor-1", "org-1");
  }

  private static AuthorizedRepositoryHandle handle(RepositoryQuery query, Path root) {
    try {
      return new AuthorizedRepositoryHandle(
          query.accessContext().actorUserId(),
          query.accessContext().tenantOrgId(),
          query.projectId(),
          query.repositoryId(),
          query.expectedSnapshot().stableId(),
          query.expectedSnapshot().commitSha() == null
              ? "UNPINNED"
              : query.expectedSnapshot().commitSha(),
          root.toRealPath());
    } catch (java.io.IOException unavailable) {
      throw new IllegalStateException("test repository is unavailable", unavailable);
    }
  }

  private static CodeIndexStateStore.State readyState(UUID repoId, String sourceSha) {
    return new CodeIndexStateStore.State(
        "project-1",
        repoId,
        sourceSha,
        CodeIndexStateStore.Status.READY,
        null,
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
        1,
        null,
        Instant.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH);
  }
}
