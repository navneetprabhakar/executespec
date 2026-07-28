package com.navneet.executespec.repositoryintel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.navneet.executespec.project.rag.CodeIndexStateStore;
import com.navneet.executespec.tool.RepositorySearchService;

/**
 * Conservative adapter over the existing deterministic search and semantic-index readiness stores.
 * It never clones, indexes, embeds, generates documents, or invokes a model.
 */
public final class NativeRepositoryIntelligenceAdapter implements RepositoryIntelligencePort {

  private static final Set<RepositoryCapability> SEARCH_CAPABILITIES =
      Set.of(
          RepositoryCapability.EXACT_TEXT,
          RepositoryCapability.SYMBOL,
          RepositoryCapability.HEURISTIC_RELATIONSHIP);

  private final RepositorySearchService searchService;
  private final CodeIndexStateStore codeIndexStateStore;
  private final RepositoryRootResolver rootResolver;

  public NativeRepositoryIntelligenceAdapter(
      RepositorySearchService searchService,
      CodeIndexStateStore codeIndexStateStore,
      RepositoryRootResolver rootResolver) {
    if (searchService == null) throw new IllegalArgumentException("searchService is required");
    if (codeIndexStateStore == null) {
      throw new IllegalArgumentException("codeIndexStateStore is required");
    }
    if (rootResolver == null) throw new IllegalArgumentException("rootResolver is required");
    this.searchService = searchService;
    this.codeIndexStateStore = codeIndexStateStore;
    this.rootResolver = rootResolver;
  }

  @Override
  public RepositoryEvidenceBatch query(RepositoryQuery query) {
    if (query == null) throw new IllegalArgumentException("query is required");
    QueryDeadline deadline = QueryDeadline.start(query.budget().timeoutMillis());
    Map<RepositoryCapability, CapabilityOutcome> outcomes =
        new EnumMap<>(RepositoryCapability.class);
    if (query.expectedSnapshot().freshness() != RepositoryFreshness.CURRENT) {
      rejectUnusableSnapshot(query, outcomes);
      return batch(query, List.of(), outcomes, false);
    }
    if (!"native".equals(query.expectedSnapshot().engine())) {
      rejectUnavailableSnapshot(query, outcomes, "SNAPSHOT_ENGINE_UNSUPPORTED");
      return batch(query, List.of(), outcomes, false);
    }
    if (query.expectedSnapshot().schemaVersion() != 1) {
      rejectUnavailableSnapshot(query, outcomes, "SNAPSHOT_SCHEMA_UNSUPPORTED");
      return batch(query, List.of(), outcomes, false);
    }
    if (query.expectedSnapshot().inventoryFingerprint() == null) {
      rejectUnavailableSnapshot(query, outcomes, "INVENTORY_FINGERPRINT_REQUIRED");
      return batch(query, List.of(), outcomes, false);
    }
    if (query.expectedSnapshot().dirtyFingerprint() != null
        && !query
            .expectedSnapshot()
            .dirtyFingerprint()
            .equals(query.expectedSnapshot().inventoryFingerprint())) {
      rejectUnavailableSnapshot(query, outcomes, "DIRTY_FINGERPRINT_NOT_VALIDATABLE");
      return batch(query, List.of(), outcomes, false);
    }

    Path root;
    try {
      root = deadline.call(() -> rootResolver.resolve(query));
      if (root == null) throw new IllegalStateException("repository root is unavailable");
    } catch (RepositoryQueryTimeoutException timeout) {
      failAll(query, outcomes, "QUERY_TIMEOUT");
      return batch(query, List.of(), outcomes, false);
    } catch (RepositoryQueryInterruptedException interrupted) {
      throw interrupted;
    } catch (RuntimeException resolutionFailure) {
      failAll(query, outcomes, "REPOSITORY_ROOT_UNAVAILABLE");
      return batch(query, List.of(), outcomes, false);
    }

    RepositorySearchService.SearchResult snapshotProbe;
    try {
      snapshotProbe = deadline.call(() -> snapshotProbe(root, query));
    } catch (RepositoryQueryTimeoutException timeout) {
      failAll(query, outcomes, "QUERY_TIMEOUT");
      return batch(query, List.of(), outcomes, false);
    } catch (RepositoryQueryInterruptedException interrupted) {
      throw interrupted;
    } catch (RuntimeException searchFailure) {
      failAll(query, outcomes, "REPOSITORY_SEARCH_FAILED");
      return batch(query, List.of(), outcomes, false);
    }
    String mismatch = snapshotMismatch(query.expectedSnapshot(), snapshotProbe);
    if (mismatch != null) {
      query
          .capabilities()
          .forEach(
              capability ->
                  outcomes.put(
                      capability,
                      new CapabilityOutcome(
                          capability,
                          RepositoryCapabilityState.STALE_REJECTED,
                          mismatch,
                          0,
                          false)));
      return batch(query, List.of(), outcomes, false);
    }

    List<RepositoryEvidence> evidence = new ArrayList<>();
    BudgetCounter budget = new BudgetCounter(query);
    boolean truncated = false;
    int calls = 0;
    for (RepositoryCapability capability : RepositoryCapability.values()) {
      if (!query.capabilities().contains(capability)) continue;
      if (deadline.expired()) {
        outcomes.put(capability, partial(capability, "QUERY_TIMEOUT", 0));
        truncated = true;
        continue;
      }
      if (SEARCH_CAPABILITIES.contains(capability)) {
        if (calls >= query.budget().maxAdapterCalls()) {
          outcomes.put(capability, partial(capability, "ADAPTER_CALL_BUDGET", 0));
          truncated = true;
          continue;
        }
        calls++;
        try {
          SearchContribution contribution =
              deadline.call(() -> searchCapability(root, query, capability, snapshotProbe));
          int accepted = budget.add(evidence, contribution.evidence());
          boolean capabilityTruncated =
              contribution.hasMore() || accepted < contribution.evidence().size();
          outcomes.put(
              capability,
              outcome(
                  capability,
                  accepted,
                  capabilityTruncated,
                  capabilityTruncated ? "RESULT_BUDGET" : null));
          truncated |= capabilityTruncated;
        } catch (RepositoryQueryTimeoutException timeout) {
          outcomes.put(capability, partial(capability, "QUERY_TIMEOUT", 0));
          truncated = true;
        } catch (RepositoryQueryInterruptedException interrupted) {
          throw interrupted;
        } catch (RuntimeException capabilityFailure) {
          outcomes.put(
              capability,
              new CapabilityOutcome(
                  capability,
                  RepositoryCapabilityState.FAILED,
                  "CAPABILITY_QUERY_FAILED",
                  0,
                  false));
        }
      } else if (capability == RepositoryCapability.CODE_SEMANTIC) {
        try {
          outcomes.put(
              capability,
              deadline.call(() -> semanticOutcome(query, snapshotProbe.repositorySha())));
        } catch (RepositoryQueryTimeoutException timeout) {
          outcomes.put(capability, partial(capability, "QUERY_TIMEOUT", 0));
          truncated = true;
        } catch (RepositoryQueryInterruptedException interrupted) {
          throw interrupted;
        }
      } else {
        outcomes.put(
            capability,
            new CapabilityOutcome(
                capability,
                RepositoryCapabilityState.UNAVAILABLE,
                "CAPABILITY_NOT_SUPPORTED_BY_NATIVE_ADAPTER",
                0,
                false));
      }
    }
    return batch(query, evidence, outcomes, truncated);
  }

  private SearchContribution searchCapability(
      Path root,
      RepositoryQuery query,
      RepositoryCapability capability,
      RepositorySearchService.SearchResult snapshotProbe) {
    int limit = Math.min(200, query.budget().maxHitsPerCapability());
    if (capability == RepositoryCapability.HEURISTIC_RELATIONSHIP) {
      RepositorySearchService.RelationshipResult result =
          searchService.relationships(
              root,
              new RepositorySearchService.RelationshipRequest(
                  query.text(), true, query.relativeRoot(), query.filePattern(), limit, 0));
      List<RepositoryEvidence> evidence =
          result.edges().stream().map(edge -> relationshipEvidence(query, edge)).toList();
      return new SearchContribution(evidence, result.hasMore());
    }
    RepositorySearchService.SearchMode mode =
        capability == RepositoryCapability.SYMBOL
            ? RepositorySearchService.SearchMode.SYMBOL
            : RepositorySearchService.SearchMode.EXACT;
    RepositorySearchService.SearchResult result =
        capability == RepositoryCapability.EXACT_TEXT
                && query.relativeRoot() == null
                && query.filePattern() == null
                && snapshotProbe.hits().size() <= limit
                && !snapshotProbe.hasMore()
            ? snapshotProbe
            : search(root, query, mode, limit);
    List<RepositoryEvidence> evidence =
        result.hits().stream().map(hit -> searchEvidence(query, capability, hit)).toList();
    return new SearchContribution(evidence, result.hasMore());
  }

  private RepositorySearchService.SearchResult search(
      Path root, RepositoryQuery query, RepositorySearchService.SearchMode mode, int limit) {
    return searchService.search(
        root,
        new RepositorySearchService.SearchRequest(
            query.text(), mode, true, query.relativeRoot(), query.filePattern(), limit, 0));
  }

  /** Snapshot identity is repository-wide even when the evidence query narrows its search root. */
  private RepositorySearchService.SearchResult snapshotProbe(Path root, RepositoryQuery query) {
    return searchService.search(
        root,
        new RepositorySearchService.SearchRequest(
            query.text(), RepositorySearchService.SearchMode.EXACT, true, null, null, 1, 0));
  }

  private RepositoryEvidence searchEvidence(
      RepositoryQuery query,
      RepositoryCapability capability,
      RepositorySearchService.SearchHit hit) {
    return new RepositoryEvidence(
        null,
        RepositoryEvidenceScope.REPOSITORY,
        capability,
        capability == RepositoryCapability.EXACT_TEXT
            ? RepositoryEvidenceKind.TEXT_MATCH
            : RepositoryEvidenceKind.SYMBOL,
        hit.path(),
        hit.line(),
        hit.line(),
        capability == RepositoryCapability.SYMBOL ? query.text() : null,
        null,
        hit.preview(),
        null,
        capability == RepositoryCapability.EXACT_TEXT
            ? RepositoryEvidenceProvenance.EXACT
            : RepositoryEvidenceProvenance.HEURISTIC_SYMBOL,
        capability == RepositoryCapability.EXACT_TEXT ? 1.0 : 0.75,
        capability == RepositoryCapability.EXACT_TEXT
            ? RepositoryEvidenceAmbiguity.UNAMBIGUOUS
            : RepositoryEvidenceAmbiguity.UNKNOWN,
        query.expectedSnapshot(),
        query.fingerprint());
  }

  private RepositoryEvidence relationshipEvidence(
      RepositoryQuery query, RepositorySearchService.RelationshipEdge edge) {
    boolean resolved = edge.targetPath() != null && !edge.targetPath().isBlank();
    String relationship =
        edge.relationshipType().name()
            + (resolved ? " -> " + edge.targetPath() : " -> unresolved target");
    return new RepositoryEvidence(
        null,
        RepositoryEvidenceScope.REPOSITORY,
        RepositoryCapability.HEURISTIC_RELATIONSHIP,
        RepositoryEvidenceKind.RELATIONSHIP,
        edge.sourcePath(),
        edge.line(),
        edge.line(),
        edge.symbol(),
        relationship,
        edge.preview(),
        null,
        RepositoryEvidenceProvenance.HEURISTIC_RELATIONSHIP,
        resolved ? 0.75 : 0.4,
        resolved ? RepositoryEvidenceAmbiguity.UNAMBIGUOUS : RepositoryEvidenceAmbiguity.AMBIGUOUS,
        query.expectedSnapshot(),
        query.fingerprint());
  }

  private CapabilityOutcome semanticOutcome(RepositoryQuery query, String currentSha) {
    if (!query.providerBackedRetrievalAllowed()) {
      return unavailable(RepositoryCapability.CODE_SEMANTIC, "PROVIDER_RETRIEVAL_NOT_ALLOWED");
    }
    if (query.projectId() == null) {
      return unavailable(RepositoryCapability.CODE_SEMANTIC, "PROJECT_ID_REQUIRED");
    }
    UUID repoId;
    try {
      repoId = UUID.fromString(query.repositoryId());
    } catch (IllegalArgumentException invalidRepoId) {
      return unavailable(RepositoryCapability.CODE_SEMANTIC, "UUID_REPOSITORY_ID_REQUIRED");
    }
    Optional<CodeIndexStateStore.State> state;
    try {
      state = codeIndexStateStore.get(query.projectId(), repoId);
    } catch (RuntimeException stateReadFailure) {
      return new CapabilityOutcome(
          RepositoryCapability.CODE_SEMANTIC,
          RepositoryCapabilityState.FAILED,
          "INDEX_STATE_READ_FAILED",
          0,
          false);
    }
    if (state.isEmpty()) {
      return unavailable(RepositoryCapability.CODE_SEMANTIC, "INDEX_NOT_BUILT");
    }
    CodeIndexStateStore.State index = state.get();
    if (index.sourceSha() == null
        || !index.semanticallyReadyFor(currentSha)
        || index.coveragePercent() < 100.0
        || index.inventoryTruncated()) {
      return unavailable(
          RepositoryCapability.CODE_SEMANTIC,
          "INDEX_" + index.status().name() + ":" + normalizeReason(index.statusReason()));
    }
    return unavailable(
        RepositoryCapability.CODE_SEMANTIC, "SEMANTIC_RETRIEVER_NOT_BOUND_TO_NATIVE_ADAPTER");
  }

  private static String snapshotMismatch(
      RepositorySnapshotId expected, RepositorySearchService.SearchResult actual) {
    if (expected.dirtyFingerprint() != null && expected.inventoryFingerprint() == null) {
      return "DIRTY_FINGERPRINT_NOT_VALIDATABLE";
    }
    if (expected.commitSha() != null
        && !expected.commitSha().equalsIgnoreCase(actual.repositorySha())) {
      return "COMMIT_SHA_CHANGED";
    }
    if (expected.inventoryFingerprint() != null
        && !expected.inventoryFingerprint().equalsIgnoreCase(actual.inventoryFingerprint())) {
      return "INVENTORY_FINGERPRINT_CHANGED";
    }
    return null;
  }

  private static CapabilityOutcome outcome(
      RepositoryCapability capability, int hits, boolean truncated, String reason) {
    if (truncated) return partial(capability, reason, hits);
    return new CapabilityOutcome(
        capability,
        hits == 0
            ? RepositoryCapabilityState.AVAILABLE_NO_MATCH
            : RepositoryCapabilityState.AVAILABLE_HIT,
        null,
        hits,
        false);
  }

  private static CapabilityOutcome partial(
      RepositoryCapability capability, String reason, int hits) {
    return new CapabilityOutcome(capability, RepositoryCapabilityState.PARTIAL, reason, hits, true);
  }

  private static CapabilityOutcome unavailable(RepositoryCapability capability, String reason) {
    return new CapabilityOutcome(
        capability, RepositoryCapabilityState.UNAVAILABLE, reason, 0, false);
  }

  private static void rejectUnusableSnapshot(
      RepositoryQuery query, Map<RepositoryCapability, CapabilityOutcome> outcomes) {
    RepositoryCapabilityState state =
        query.expectedSnapshot().freshness() == RepositoryFreshness.STALE
            ? RepositoryCapabilityState.STALE_REJECTED
            : RepositoryCapabilityState.UNAVAILABLE;
    String reason = "SNAPSHOT_" + query.expectedSnapshot().freshness().name();
    query
        .capabilities()
        .forEach(
            capability ->
                outcomes.put(
                    capability, new CapabilityOutcome(capability, state, reason, 0, false)));
  }

  private static void rejectUnavailableSnapshot(
      RepositoryQuery query, Map<RepositoryCapability, CapabilityOutcome> outcomes, String reason) {
    query
        .capabilities()
        .forEach(
            capability ->
                outcomes.put(
                    capability,
                    new CapabilityOutcome(
                        capability, RepositoryCapabilityState.UNAVAILABLE, reason, 0, false)));
  }

  private static void failAll(
      RepositoryQuery query, Map<RepositoryCapability, CapabilityOutcome> outcomes, String reason) {
    query
        .capabilities()
        .forEach(
            capability ->
                outcomes.put(
                    capability,
                    new CapabilityOutcome(
                        capability, RepositoryCapabilityState.FAILED, reason, 0, false)));
  }

  private static RepositoryEvidenceBatch batch(
      RepositoryQuery query,
      List<RepositoryEvidence> evidence,
      Map<RepositoryCapability, CapabilityOutcome> outcomes,
      boolean truncated) {
    return RepositoryEvidenceBatch.of(query, evidence, outcomes, truncated);
  }

  private static String normalizeReason(String reason) {
    if (reason == null || reason.isBlank()) return "NO_REASON";
    String normalized = reason.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
    return normalized.substring(
        0, Math.min(normalized.length(), CapabilityOutcome.MAX_REASON_CHARACTERS));
  }

  private record SearchContribution(List<RepositoryEvidence> evidence, boolean hasMore) {}

  /**
   * One monotonic deadline shared by root resolution, snapshot validation, and all capabilities.
   */
  private static final class QueryDeadline {
    private final long startedNanos;
    private final long timeoutNanos;

    private QueryDeadline(long startedNanos, long timeoutNanos) {
      this.startedNanos = startedNanos;
      this.timeoutNanos = timeoutNanos;
    }

    private static QueryDeadline start(long timeoutMillis) {
      return new QueryDeadline(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
    }

    private boolean expired() {
      return remainingNanos() <= 0;
    }

    private <T> T call(Callable<T> operation) {
      long remaining = remainingNanos();
      if (remaining <= 0) throw new RepositoryQueryTimeoutException();
      FutureTask<T> task = new FutureTask<>(operation);
      Thread.startVirtualThread(task);
      try {
        return task.get(remaining, TimeUnit.NANOSECONDS);
      } catch (TimeoutException timeout) {
        task.cancel(true);
        throw new RepositoryQueryTimeoutException();
      } catch (InterruptedException interrupted) {
        task.cancel(true);
        Thread.currentThread().interrupt();
        throw new RepositoryQueryInterruptedException(interrupted);
      } catch (ExecutionException failed) {
        Throwable cause = failed.getCause();
        if (cause instanceof InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new RepositoryQueryInterruptedException(interrupted);
        }
        if (cause instanceof RuntimeException runtime) throw runtime;
        if (cause instanceof Error error) throw error;
        throw new IllegalStateException("repository intelligence operation failed", cause);
      }
    }

    private long remainingNanos() {
      return timeoutNanos - (System.nanoTime() - startedNanos);
    }
  }

  private static final class RepositoryQueryTimeoutException extends RuntimeException {}

  static final class RepositoryQueryInterruptedException extends RuntimeException {
    private RepositoryQueryInterruptedException(InterruptedException cause) {
      super("repository intelligence query interrupted", cause);
    }
  }

  private static final class BudgetCounter {
    private final QueryBudget budget;
    private int hits;
    private int characters;
    private int tokens;

    private BudgetCounter(RepositoryQuery query) {
      this.budget = query.budget();
      this.characters = RepositoryJsonBudget.reservedBatchCharacters(query);
      this.tokens = Math.max(1, (characters + 3) / 4);
    }

    private int add(List<RepositoryEvidence> target, List<RepositoryEvidence> candidates) {
      int accepted = 0;
      for (RepositoryEvidence candidate : candidates) {
        int addedCharacters = candidate.characterCount() + (hits == 0 ? 0 : 1);
        if (hits + 1 > budget.maxTotalHits()
            || characters + addedCharacters > budget.maxCharacters()
            || tokens + Math.max(1, (addedCharacters + 3) / 4) > budget.maxEstimatedTokens()) break;
        target.add(candidate);
        hits++;
        characters += addedCharacters;
        tokens += Math.max(1, (addedCharacters + 3) / 4);
        accepted++;
      }
      return accepted;
    }
  }
}
