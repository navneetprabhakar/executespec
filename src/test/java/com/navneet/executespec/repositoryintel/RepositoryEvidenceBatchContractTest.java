package com.navneet.executespec.repositoryintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class RepositoryEvidenceBatchContractTest {

  @Test
  void sortsDeduplicatesAndMeasuresEvidenceDeterministically() throws Exception {
    RepositoryQuery query = query(QueryBudget.DEFAULT);
    RepositoryEvidence later = evidence(query, RepositoryEvidenceKind.SYMBOL, "z/File.java", 20);
    RepositoryEvidence exact = evidence(query, RepositoryEvidenceKind.TEXT_MATCH, "a/File.java", 3);

    RepositoryEvidenceBatch batch =
        RepositoryEvidenceBatch.of(
            query,
            List.of(later, exact, exact),
            Map.of(
                RepositoryCapability.EXACT_TEXT,
                new CapabilityOutcome(
                    RepositoryCapability.EXACT_TEXT,
                    RepositoryCapabilityState.AVAILABLE_HIT,
                    null,
                    1,
                    false),
                RepositoryCapability.SYMBOL,
                new CapabilityOutcome(
                    RepositoryCapability.SYMBOL,
                    RepositoryCapabilityState.AVAILABLE_HIT,
                    null,
                    1,
                    false)),
            false);

    assertThat(batch.evidence()).containsExactly(exact, later);
    assertThat(batch.returnedCharacters())
        .isGreaterThan(exact.characterCount() + later.characterCount());
    assertThat(batch.estimatedTokens()).isEqualTo((batch.returnedCharacters() + 3) / 4);
    assertThat(batch.getQueryFingerprint()).isEqualTo(batch.queryFingerprint());
    assertThat(batch.getRequestedSnapshot()).isEqualTo(batch.requestedSnapshot());
    assertThat(batch.getEvidence()).isSameAs(batch.evidence());
    assertThat(batch.getOutcomes()).isSameAs(batch.outcomes());
    assertThat(batch.isTruncated()).isEqualTo(batch.truncated());
    assertThat(batch.getReturnedCharacters()).isEqualTo(batch.returnedCharacters());
    assertThat(batch.getEstimatedTokens()).isEqualTo(batch.estimatedTokens());
    String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(batch);
    assertThat(batch.returnedCharacters()).isGreaterThanOrEqualTo(serialized.length());
  }

  @Test
  void requiresAnExplicitOutcomeForEveryRequestedCapability() {
    RepositoryQuery query = query(QueryBudget.DEFAULT);

    assertThatThrownBy(
            () ->
                RepositoryEvidenceBatch.of(
                    query,
                    List.of(),
                    Map.of(
                        RepositoryCapability.EXACT_TEXT,
                        new CapabilityOutcome(
                            RepositoryCapability.EXACT_TEXT,
                            RepositoryCapabilityState.AVAILABLE_NO_MATCH,
                            null,
                            0,
                            false)),
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly the requested capabilities");
  }

  @Test
  void distinguishesNoMatchUnavailableStaleAndPartial() {
    assertThat(
            new CapabilityOutcome(
                    RepositoryCapability.EXACT_TEXT,
                    RepositoryCapabilityState.AVAILABLE_NO_MATCH,
                    null,
                    0,
                    false)
                .state())
        .isEqualTo(RepositoryCapabilityState.AVAILABLE_NO_MATCH);
    assertThat(
            new CapabilityOutcome(
                    RepositoryCapability.CODE_SEMANTIC,
                    RepositoryCapabilityState.UNAVAILABLE,
                    "INDEX_NOT_READY",
                    0,
                    false)
                .state())
        .isEqualTo(RepositoryCapabilityState.UNAVAILABLE);
    assertThat(
            new CapabilityOutcome(
                    RepositoryCapability.ARCHITECTURE,
                    RepositoryCapabilityState.STALE_REJECTED,
                    "SOURCE_SHA_CHANGED",
                    0,
                    false)
                .state())
        .isEqualTo(RepositoryCapabilityState.STALE_REJECTED);
    assertThat(
            new CapabilityOutcome(
                    RepositoryCapability.CODEMAP,
                    RepositoryCapabilityState.PARTIAL,
                    "INVENTORY_TRUNCATED",
                    2,
                    true)
                .state())
        .isEqualTo(RepositoryCapabilityState.PARTIAL);
  }

  @Test
  void enforcesBudgetAndRepresentsTerminalTruncationWithoutFakeCursor() {
    QueryBudget tiny = new QueryBudget(1, 1, 1, 2, 1, 100);
    RepositoryQuery query = query(tiny);
    RepositoryEvidence hit = evidence(query, RepositoryEvidenceKind.TEXT_MATCH, "A.java", 1);
    RepositoryEvidence second = evidence(query, RepositoryEvidenceKind.SYMBOL, "B.java", 2);
    Map<RepositoryCapability, CapabilityOutcome> outcomes =
        Map.of(
            RepositoryCapability.EXACT_TEXT,
            new CapabilityOutcome(
                RepositoryCapability.EXACT_TEXT,
                RepositoryCapabilityState.PARTIAL,
                "CHARACTER_BUDGET",
                1,
                true),
            RepositoryCapability.SYMBOL,
            new CapabilityOutcome(
                RepositoryCapability.SYMBOL,
                RepositoryCapabilityState.AVAILABLE_HIT,
                null,
                1,
                false));

    assertThatThrownBy(
            () -> RepositoryEvidenceBatch.of(query, List.of(hit, second), outcomes, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds the query budget");
    Map<RepositoryCapability, CapabilityOutcome> terminalOutcomes =
        Map.of(
            RepositoryCapability.EXACT_TEXT,
            new CapabilityOutcome(
                RepositoryCapability.EXACT_TEXT,
                RepositoryCapabilityState.PARTIAL,
                "CHARACTER_BUDGET",
                0,
                true),
            RepositoryCapability.SYMBOL,
            new CapabilityOutcome(
                RepositoryCapability.SYMBOL,
                RepositoryCapabilityState.AVAILABLE_NO_MATCH,
                null,
                0,
                false));
    assertThatThrownBy(() -> RepositoryEvidenceBatch.of(query, List.of(), terminalOutcomes, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("response envelope");

    RepositoryQuery adequate = query(QueryBudget.DEFAULT);
    assertThat(
            RepositoryEvidenceBatch.of(
                    adequate,
                    List.of(),
                    Map.of(
                        RepositoryCapability.EXACT_TEXT,
                        new CapabilityOutcome(
                            RepositoryCapability.EXACT_TEXT,
                            RepositoryCapabilityState.PARTIAL,
                            "CHARACTER_BUDGET",
                            0,
                            true),
                        RepositoryCapability.SYMBOL,
                        new CapabilityOutcome(
                            RepositoryCapability.SYMBOL,
                            RepositoryCapabilityState.AVAILABLE_NO_MATCH,
                            null,
                            0,
                            false)),
                    true)
                .truncated())
        .isTrue();
  }

  @Test
  void rejectsEvidenceFromAnotherQueryOrSnapshot() {
    RepositoryQuery query = query(QueryBudget.DEFAULT);
    RepositoryEvidence wrongQuery =
        new RepositoryEvidence(
            null,
            RepositoryEvidenceScope.REPOSITORY,
            RepositoryCapability.EXACT_TEXT,
            RepositoryEvidenceKind.TEXT_MATCH,
            "A.java",
            1,
            1,
            null,
            null,
            "x",
            null,
            RepositoryEvidenceProvenance.EXACT,
            1,
            RepositoryEvidenceAmbiguity.UNAMBIGUOUS,
            query.expectedSnapshot(),
            "different-query");

    assertThatThrownBy(
            () -> RepositoryEvidenceBatch.of(query, List.of(wrongQuery), validOutcomes(), false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("query fingerprint mismatch");
  }

  @Test
  void constructionCannotBypassTruncationOrHitAccounting() {
    RepositoryQuery query = query(QueryBudget.DEFAULT);
    Map<RepositoryCapability, CapabilityOutcome> partial =
        Map.of(
            RepositoryCapability.EXACT_TEXT,
            new CapabilityOutcome(
                RepositoryCapability.EXACT_TEXT,
                RepositoryCapabilityState.PARTIAL,
                "RESULT_BUDGET",
                0,
                true),
            RepositoryCapability.SYMBOL,
            new CapabilityOutcome(
                RepositoryCapability.SYMBOL,
                RepositoryCapabilityState.AVAILABLE_NO_MATCH,
                null,
                0,
                false));

    assertThatThrownBy(() -> RepositoryEvidenceBatch.of(query, List.of(), partial, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("top-level truncation");
    assertThatThrownBy(() -> RepositoryEvidenceBatch.of(query, List.of(), validOutcomes(), false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("hitCount");
    assertThat(RepositoryEvidenceBatch.class.getConstructors()).isEmpty();
  }

  @Test
  void outcomeIterationOrderIsDeterministicAcrossInputMapOrder() {
    RepositoryQuery query = query(QueryBudget.DEFAULT);
    CapabilityOutcome exact =
        new CapabilityOutcome(
            RepositoryCapability.EXACT_TEXT,
            RepositoryCapabilityState.AVAILABLE_NO_MATCH,
            null,
            0,
            false);
    CapabilityOutcome symbol =
        new CapabilityOutcome(
            RepositoryCapability.SYMBOL,
            RepositoryCapabilityState.AVAILABLE_NO_MATCH,
            null,
            0,
            false);
    Map<RepositoryCapability, CapabilityOutcome> reverse = new LinkedHashMap<>();
    reverse.put(RepositoryCapability.SYMBOL, symbol);
    reverse.put(RepositoryCapability.EXACT_TEXT, exact);
    Map<RepositoryCapability, CapabilityOutcome> forward = new LinkedHashMap<>();
    forward.put(RepositoryCapability.EXACT_TEXT, exact);
    forward.put(RepositoryCapability.SYMBOL, symbol);

    List<RepositoryCapability> first =
        new ArrayList<>(
            RepositoryEvidenceBatch.of(query, List.of(), reverse, false).outcomes().keySet());
    List<RepositoryCapability> second =
        new ArrayList<>(
            RepositoryEvidenceBatch.of(query, List.of(), forward, false).outcomes().keySet());

    assertThat(first)
        .containsExactly(RepositoryCapability.EXACT_TEXT, RepositoryCapability.SYMBOL)
        .isEqualTo(second);
  }

  @Test
  void budgetCountsRepeatedLongSymbolMetadataNotOnlyExcerpt() {
    RepositoryQuery query = query(new QueryBudget(2, 10, 10, 500, 500, 100));
    RepositoryEvidence longSymbol =
        new RepositoryEvidence(
            null,
            RepositoryEvidenceScope.REPOSITORY,
            RepositoryCapability.SYMBOL,
            RepositoryEvidenceKind.SYMBOL,
            "A.java",
            1,
            1,
            "S".repeat(2_000),
            null,
            "x",
            null,
            RepositoryEvidenceProvenance.HEURISTIC_SYMBOL,
            0.75,
            RepositoryEvidenceAmbiguity.UNKNOWN,
            query.expectedSnapshot(),
            query.fingerprint());
    Map<RepositoryCapability, CapabilityOutcome> outcomes =
        Map.of(
            RepositoryCapability.EXACT_TEXT,
            new CapabilityOutcome(
                RepositoryCapability.EXACT_TEXT,
                RepositoryCapabilityState.AVAILABLE_NO_MATCH,
                null,
                0,
                false),
            RepositoryCapability.SYMBOL,
            new CapabilityOutcome(
                RepositoryCapability.SYMBOL,
                RepositoryCapabilityState.AVAILABLE_HIT,
                null,
                1,
                false));

    assertThat(longSymbol.characterCount()).isGreaterThan(2_000);
    assertThatThrownBy(
            () -> RepositoryEvidenceBatch.of(query, List.of(longSymbol), outcomes, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds the query budget");
  }

  @Test
  void fullEnvelopeBudgetCountsEscapedFieldsAndOutcomeMetadata() {
    RepositoryQuery roomy = query(QueryBudget.DEFAULT);
    RepositoryEvidence escaped =
        new RepositoryEvidence(
            null,
            RepositoryEvidenceScope.REPOSITORY,
            RepositoryCapability.EXACT_TEXT,
            RepositoryEvidenceKind.TEXT_MATCH,
            "quoted\"path.java",
            1,
            1,
            null,
            null,
            "line one\nline two\\tail",
            null,
            RepositoryEvidenceProvenance.EXACT,
            1,
            RepositoryEvidenceAmbiguity.UNAMBIGUOUS,
            roomy.expectedSnapshot(),
            roomy.fingerprint());
    Map<RepositoryCapability, CapabilityOutcome> outcomes =
        Map.of(
            RepositoryCapability.EXACT_TEXT,
            new CapabilityOutcome(
                RepositoryCapability.EXACT_TEXT,
                RepositoryCapabilityState.AVAILABLE_HIT,
                null,
                1,
                false),
            RepositoryCapability.SYMBOL,
            new CapabilityOutcome(
                RepositoryCapability.SYMBOL,
                RepositoryCapabilityState.UNAVAILABLE,
                "reason-with-escaped-metadata",
                0,
                false));

    RepositoryEvidenceBatch measured =
        RepositoryEvidenceBatch.of(roomy, List.of(escaped), outcomes, false);
    RepositoryQuery tooSmall =
        query(
            new QueryBudget(
                2, 10, 10, measured.returnedCharacters() - 1, measured.estimatedTokens(), 100));
    RepositoryEvidence rebound =
        new RepositoryEvidence(
            null,
            escaped.scope(),
            escaped.capability(),
            escaped.kind(),
            escaped.path(),
            escaped.startLine(),
            escaped.endLine(),
            escaped.symbol(),
            escaped.relationship(),
            escaped.excerpt(),
            null,
            escaped.provenance(),
            escaped.confidence(),
            escaped.ambiguity(),
            tooSmall.expectedSnapshot(),
            tooSmall.fingerprint());

    assertThat(measured.returnedCharacters()).isGreaterThan(escaped.characterCount());
    assertThatThrownBy(
            () -> RepositoryEvidenceBatch.of(tooSmall, List.of(rebound), outcomes, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("response envelope");
  }

  @Test
  void outcomeReasonIsBoundedBeforeItCanEnterTheEnvelope() {
    assertThatThrownBy(
            () ->
                new CapabilityOutcome(
                    RepositoryCapability.EXACT_TEXT,
                    RepositoryCapabilityState.UNAVAILABLE,
                    "R".repeat(CapabilityOutcome.MAX_REASON_CHARACTERS + 1),
                    0,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason exceeds");
  }

  private static RepositoryQuery query(QueryBudget budget) {
    RepositorySnapshotId snapshot =
        new RepositorySnapshotId(
            "project-1",
            "repo-1",
            "abc123",
            "dirty",
            "inventory",
            "native",
            1,
            Instant.EPOCH,
            RepositoryFreshness.CURRENT);
    return new RepositoryQuery(
        "project-1",
        "repo-1",
        "x",
        Set.of(RepositoryCapability.EXACT_TEXT, RepositoryCapability.SYMBOL),
        null,
        null,
        snapshot,
        budget,
        false);
  }

  private static RepositoryEvidence evidence(
      RepositoryQuery query, RepositoryEvidenceKind kind, String path, int line) {
    return new RepositoryEvidence(
        null,
        RepositoryEvidenceScope.REPOSITORY,
        kind == RepositoryEvidenceKind.TEXT_MATCH
            ? RepositoryCapability.EXACT_TEXT
            : RepositoryCapability.SYMBOL,
        kind,
        path,
        line,
        line,
        null,
        null,
        "x",
        null,
        kind == RepositoryEvidenceKind.TEXT_MATCH
            ? RepositoryEvidenceProvenance.EXACT
            : RepositoryEvidenceProvenance.HEURISTIC_SYMBOL,
        1,
        RepositoryEvidenceAmbiguity.UNAMBIGUOUS,
        query.expectedSnapshot(),
        query.fingerprint());
  }

  private static Map<RepositoryCapability, CapabilityOutcome> validOutcomes() {
    return Map.of(
        RepositoryCapability.EXACT_TEXT,
        new CapabilityOutcome(
            RepositoryCapability.EXACT_TEXT,
            RepositoryCapabilityState.AVAILABLE_HIT,
            null,
            1,
            false),
        RepositoryCapability.SYMBOL,
        new CapabilityOutcome(
            RepositoryCapability.SYMBOL,
            RepositoryCapabilityState.AVAILABLE_NO_MATCH,
            null,
            0,
            false));
  }
}
