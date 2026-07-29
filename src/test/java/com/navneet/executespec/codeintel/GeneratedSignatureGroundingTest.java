package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeneratedSignatureGroundingTest {

  private static final String SOURCE =
      """
      class Game {
        boolean isValidMove(int row, int col, GameConfig config)
        void makeMove(int row, int col, String marker, GameConfig config)
      }
      class AIPlayer {
        Move findBestMove(String[][] board, GameConfig config, Map<String, Integer> memo)
      }
      """;

  @Test
  void unverifiableConcreteSignaturesAreDowngradedToNames() {
    String generated =
        """
        Calls `isValidMove(int,int)`, `makeMove(int,int,char)`, and
        `AIPlayer.findBestMove(char[][])`.
        """;

    String sanitized = GeneratedSignatureGrounding.sanitize(generated, SOURCE);

    assertThat(sanitized)
        .contains("`isValidMove`", "`makeMove`", "`AIPlayer.findBestMove`")
        .doesNotContain("`isValidMove(int,int)`")
        .doesNotContain("`makeMove(int,int,char)`")
        .doesNotContain("`AIPlayer.findBestMove(char[][])`");
  }

  @Test
  void exactSourceBackedSignatureAndNonMethodInlineCodeRemainUntouched() {
    String generated =
        "Use `isValidMove(int row, int col, GameConfig config)` in `Game.java`; run `mvn test`.";

    assertThat(GeneratedSignatureGrounding.sanitize(generated, SOURCE)).isEqualTo(generated);
  }

  @Test
  void generatedArchitectureCannotGroundItsOwnHallucinationForProjectDocs() {
    String bundle =
        """
        # Architecture document
        Calls `minimax(char[][],boolean,String)`.
        # Repository codemap: acme/game
        boolean minimax(String[][] board, boolean maximizing, GameConfig config)
        """;

    String evidence = GeneratedSignatureGrounding.codemapEvidence(bundle);

    assertThat(evidence)
        .doesNotContain("minimax(char[][],boolean,String)")
        .contains("minimax(String[][] board, boolean maximizing, GameConfig config)");
  }

  @Test
  void declarationCooccurrenceDoesNotGroundAClaimedCallEdge() {
    String evidence =
        """
        # Repository codemap: acme/game
        ## Functions
        ### `src/AIPlayer.java`
        - `class AIPlayer`
        - `evaluate`
        ### `src/TicTacToe.java`
        - `class TicTacToe`
        - `checkWin`
        """;

    assertThat(
            GeneratedSignatureGrounding.unsupportedBehaviorClaim(
                "`AIPlayer.evaluate` invokes `TicTacToe.checkWin` for terminal positions.",
                evidence))
        .hasValueSatisfying(reason -> assertThat(reason).contains("unsupported call-edge claim"));
  }

  @Test
  void declarationDoesNotGroundInactiveStubOrUnusedClaims() {
    String evidence =
        """
        # Repository codemap: acme/game
        ## Functions
        ### `src/AIPlayer.java`
        - `minimax`
        - `generateCacheString`
        """;

    assertThat(
            GeneratedSignatureGrounding.unsupportedBehaviorClaim(
                "`generateCacheString` is an inactive stub and is not used by minimax.", evidence))
        .hasValueSatisfying(
            reason -> assertThat(reason).contains("unsupported activity/liveness claim"));
  }

  @Test
  void collaborationAndDependencyEuphemismsDoNotGroundRelationships() {
    String evidence =
        """
        # Repository codemap: acme/game
        ## Functions
        ### `src/AIPlayer.java`
        - `class AIPlayer`
        - `findBestMove`
        ### `src/TicTacToe.java`
        - `class TicTacToe`
        - `checkWin`
        """;

    for (String relation :
        java.util.List.of(
            "works with",
            "interacts with",
            "collaborates with",
            "forms a collaboration with",
            "depends on",
            "uses",
            "is exposed by")) {
      assertThat(
              GeneratedSignatureGrounding.unsupportedBehaviorClaim(
                  "`AIPlayer.findBestMove` " + relation + " `TicTacToe.checkWin` during play.",
                  evidence))
          .as(relation)
          .hasValueSatisfying(reason -> assertThat(reason).contains("unsupported call-edge claim"));
    }
  }

  @Test
  void speculativeMaturityLabelsNearKnownSymbolsAreRejected() {
    String evidence =
        """
        # Repository codemap: acme/game
        ## Functions
        ### `src/AIPlayer.java`
        - `generateCacheString`
        """;

    for (String claim :
        java.util.List.of(
            "The hinted `generateCacheString` caching mechanism may be extended.",
            "`generateCacheString` is a placeholder.",
            "`generateCacheString` remains a prototype.",
            "`generateCacheString` is a candidate caching hook.")) {
      assertThat(GeneratedSignatureGrounding.unsupportedBehaviorClaim(claim, evidence))
          .as(claim)
          .hasValueSatisfying(
              reason -> assertThat(reason).contains("unsupported speculative activity/maturity"));
    }
  }

  @Test
  void unrelatedCandidateLanguageIsNotAKnownSymbolMaturityLabel() {
    String evidence =
        """
        # Repository codemap: acme/game
        ## Functions
        ### `src/AIPlayer.java`
        - `minimax`
        """;

    assertThat(
            GeneratedSignatureGrounding.unsupportedBehaviorClaim(
                "`minimax` evaluates candidate moves.", evidence))
        .isEmpty();
  }

  @Test
  void exactLiveRelationAndCachingClaimsDegradeToDeclarationOnlyNotes() {
    String evidence =
        """
        # Repository codemap: acme/game
        ## Functions
        ### `src/AIPlayer.java`
        - `minimax`
        - `evaluate`
        - `horizontalCheck`
        - `generateCacheString`
        """;
    String markdown =
        """
        ## High-level architecture
        `minimax` calls `evaluate` and `horizontalCheck`.
        The hinted `generateCacheString` caching mechanism is a candidate extension point.
        ## Key data flows
        Relevant declarations are listed below.
        """;

    String sanitized = GeneratedSignatureGrounding.sanitizeUnsupportedBehavior(markdown, evidence);

    assertThat(sanitized)
        .contains("## High-level architecture", "## Key data flows")
        .contains(
            "Declared codemap symbols: `minimax`, `evaluate`, `horizontalCheck`;"
                + " codemap evidence does not establish runtime relationships or liveness.")
        .contains(
            "Declared codemap symbols: `generateCacheString`;"
                + " codemap evidence does not establish runtime relationships or liveness.")
        .doesNotContain("calls", "hinted", "candidate extension");
    assertThat(GeneratedSignatureGrounding.unsupportedBehaviorClaim(sanitized, evidence)).isEmpty();
  }

  @Test
  void neutralMultiSymbolNavigationDegradesButSingleSymbolInventoryRemainsPublishable() {
    String evidence =
        """
        # Repository codemap: acme/game
        ## Functions
        ### `src/AIPlayer.java`
        - `minimax`
        - `generateCacheString`
        """;

    String multiSymbol = "Relevant methods include `minimax` and `generateCacheString`.";
    assertThat(GeneratedSignatureGrounding.unsupportedBehaviorClaim(multiSymbol, evidence))
        .hasValueSatisfying(reason -> assertThat(reason).contains("unsupported multi-symbol"));
    assertThat(GeneratedSignatureGrounding.sanitizeUnsupportedBehavior(multiSymbol, evidence))
        .isEqualTo(
            "Declared codemap symbols: `minimax`, `generateCacheString`; codemap evidence does not"
                + " establish runtime relationships or liveness.");

    String singleSymbol = "`minimax` is listed in the repository codemap.";
    assertThat(GeneratedSignatureGrounding.unsupportedBehaviorClaim(singleSymbol, evidence))
        .isEmpty();
    assertThat(GeneratedSignatureGrounding.sanitizeUnsupportedBehavior(singleSymbol, evidence))
        .isEqualTo(singleSymbol);
  }

  @Test
  void exactDelegationAndReturnEuphemismsDegradeWithoutVerbMatching() {
    String evidence =
        """
        # Repository codemap: acme/game
        ## Functions
        - `class TicTacToe`
        - `evaluate`
        - `PossibleMove`
        """;
    String markdown =
        """
        Terminal-state scoring is delegated back to `TicTacToe` via `evaluate`.
        The best `PossibleMove` is returned to `TicTacToe`.
        """;

    String sanitized = GeneratedSignatureGrounding.sanitizeUnsupportedBehavior(markdown, evidence);

    assertThat(sanitized)
        .contains(
            "Declared codemap symbols: `TicTacToe`, `evaluate`; codemap evidence does not establish"
                + " runtime relationships or liveness.")
        .contains(
            "Declared codemap symbols: `TicTacToe`, `PossibleMove`; codemap evidence does not"
                + " establish runtime relationships or liveness.")
        .doesNotContain("delegated back", "returned to");
    assertThat(GeneratedSignatureGrounding.unsupportedBehaviorClaim(sanitized, evidence)).isEmpty();
  }

  @Test
  void exactSingleSymbolRelianceHintAndIncompleteIntegrationClaimsDegrade() {
    String evidence =
        """
        # Repository codemap: acme/game
        ## Functions
        - `class TicTacToe`
        - `generateCacheString`
        """;
    String markdown =
        """
        The architecture document notes that the AI relies on `TicTacToe` evaluation methods.
        The caching mechanism hinted at by `generateCacheString` is not fully integrated.
        """;

    String sanitized = GeneratedSignatureGrounding.sanitizeUnsupportedBehavior(markdown, evidence);

    assertThat(sanitized)
        .contains(
            "Declared codemap symbols: `TicTacToe`; codemap evidence does not establish runtime"
                + " relationships or liveness.")
        .contains(
            "Declared codemap symbols: `generateCacheString`; codemap evidence does not establish"
                + " runtime relationships or liveness.")
        .doesNotContain("relies on", "hinted at by", "not fully integrated");
    assertThat(GeneratedSignatureGrounding.unsupportedBehaviorClaim(sanitized, evidence)).isEmpty();
  }

  @Test
  void grammaticalSingleSymbolVariantsAreAlsoDegraded() {
    String evidence =
        """
        # Repository codemap: acme/game
        ## Functions
        - `class TicTacToe`
        - `generateCacheString`
        """;

    for (String claim :
        java.util.List.of(
            "The AI may rely on `TicTacToe`.",
            "The AI relied on `TicTacToe`.",
            "The AI is relying on `TicTacToe`.",
            "Caching hints at `generateCacheString`.",
            "`generateCacheString` isn't fully integrated.",
            "`generateCacheString` was not integrated.",
            "`generateCacheString` is partially integrated.")) {
      assertThat(GeneratedSignatureGrounding.sanitizeUnsupportedBehavior(claim, evidence))
          .as(claim)
          .contains("codemap evidence does not establish runtime relationships or liveness")
          .doesNotContain(claim);
    }
  }

  @Test
  void codeExamplesAreNotMistakenForPublishedBehaviorClaims() {
    String evidence =
        """
        # Repository codemap: acme/game
        ## Functions
        ### `src/AIPlayer.java`
        - `minimax`
        - `generateCacheString`
        """;
    String markdown =
        """
        Example pseudocode:
        ```
        minimax calls generateCacheString
        ```
        """;

    assertThat(GeneratedSignatureGrounding.unsupportedBehaviorClaim(markdown, evidence)).isEmpty();
  }

  @Test
  void directionalMermaidIsRejectedWhileOrdinaryFencedExamplesRemainExcluded() {
    String evidence =
        """
        # Repository codemap: acme/game
        ## Functions
        ### `src/AIPlayer.java`
        - `class AIPlayer`
        - `findBestMove`
        """;
    String markdown =
        """
        ```mermaid
        flowchart LR
          AIPlayer --> Game
        ```
        """;

    assertThat(GeneratedSignatureGrounding.unsupportedBehaviorClaim(markdown, evidence))
        .hasValueSatisfying(
            reason -> assertThat(reason).contains("unsupported directional Mermaid edge"));
  }

  @Test
  void directionalMermaidIsRemovedButOrdinaryFencedContentIsPreserved() {
    String evidence =
        """
        # Repository codemap: acme/game
        ## Functions
        - `minimax`
        """;
    String markdown =
        """
        Before.
        ```mermaid
        sequenceDiagram
          AIPlayer->>TicTacToe: evaluate
        ```
        ```text
        minimax calls another function
        ```
        After.
        """;

    String sanitized = GeneratedSignatureGrounding.sanitizeUnsupportedBehavior(markdown, evidence);

    assertThat(sanitized)
        .contains(
            "> Directional diagram omitted: codemap evidence does not establish runtime"
                + " relationships or liveness.")
        .contains("```text", "minimax calls another function", "Before.", "After.")
        .doesNotContain("sequenceDiagram", "AIPlayer->>TicTacToe");
  }
}
