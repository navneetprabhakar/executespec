package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class GeneratedSourceContractGroundingTest {

  private static final String REPOSITORY = "acme/inventory";
  private static final String SHA = "a".repeat(40);
  private static final String OTHER_SHA = "b".repeat(40);
  private static final String GROUNDING_NOTE =
      "Source contract for `sortInventory`: not established by complete exact-source evidence.";
  private static final String SYMBOLS =
      """
      # Code map

      - `sortInventory`
      - `normalizeItem`
      - `compareText`
      """;
  private static final String LIVE_WRAPPED_FALSE_CLAIM =
      """
- **`sortInventory`** – normalises the inventory and returns a new array sorted by a validated
  field (`sku`, `name`, `category`, `priceCents`, `quantity`) and direction, with `sku`
  ascending as secondary sort. String comparisons use a helper `compareText` whose exact
  behaviour is not deterministically established.
""";
  private static final String VALID_SOURCE =
      """
      export function sortInventory(items, field) {
        const allowedSortFields = new Set(['sku', 'name', 'priceCents', 'quantity']);
        if (!allowedSortFields.has(field)) throw new Error('invalid field');
        return [...items];
      }
      """;

  @Test
  void degradesExactLiveWrappedSentenceWhenDivisionSuppressesEvidenceForTheWholeFile() {
    String source =
        VALID_SOURCE
            + "\nexport function summarizeInventory(values) { return values.valueCents / 100; }\n";
    String exact =
        ExportedFunctionEvidence.render(REPOSITORY, SHA, Map.of("src/inventory.js", source));

    assertThat(exact).isEmpty();
    assertThat(
            GeneratedSourceContractGrounding.sanitizeForRepository(
                LIVE_WRAPPED_FALSE_CLAIM, SYMBOLS + exact, REPOSITORY, SHA))
        .contains("- " + GROUNDING_NOTE)
        .doesNotContain("`category`", "`priceCents`", "validated field");
  }

  @Test
  void exactEvidenceIsBoundToExpectedRepositoryAndRevision() {
    String evidence = exactEvidence(VALID_SOURCE);
    String claim = validClaim();

    assertThat(
            GeneratedSourceContractGrounding.sanitizeForRepository(
                claim, SYMBOLS + evidence, REPOSITORY, SHA))
        .isEqualTo(claim);
    assertThat(
            GeneratedSourceContractGrounding.sanitizeForRepository(
                claim, SYMBOLS + evidence, "acme/other", SHA))
        .isEqualTo(GROUNDING_NOTE);
    assertThat(
            GeneratedSourceContractGrounding.sanitizeForRepository(
                claim, SYMBOLS + evidence, REPOSITORY, OTHER_SHA))
        .isEqualTo(GROUNDING_NOTE);
  }

  @Test
  void unrelatedLiteralCollectionAndMembershipCheckCannotAuthorizeClaim() {
    String source =
        """
        export function sortInventory(items, field) {
          const documentedFields = ['sku', 'name', 'category'];
          const allowedSortFields = new Set(['sku', 'name']);
          if (!allowedSortFields.has(field)) throw new Error('invalid field');
          return [...items];
        }
        """;
    String claim = "`sortInventory` accepts valid fields (`sku`, `name`, `category`).";

    assertThat(sanitizeForRepository(claim, source)).isEqualTo(GROUNDING_NOTE);
  }

  @Test
  void membershipMustUseTheSameCollectionThatContainsTheClaimedValues() {
    String source =
        """
        export function sortInventory(items, field) {
          const documentedFields = new Set(['sku', 'name', 'category']);
          const allowedSortFields = new Set(['sku', 'name']);
          if (!allowedSortFields.has(field)) throw new Error('invalid field');
          return [...items];
        }
        """;
    String claim = "`sortInventory` accepts valid fields (`sku`, `name`, `category`).";

    assertThat(sanitizeForRepository(claim, source)).isEqualTo(GROUNDING_NOTE);
  }

  @Test
  void commentedOrQuotedPseudoCodeCannotAuthorizeAContract() {
    String source =
        """
        export function sortInventory(items, field) {
          // const allowedSortFields = new Set(['sku', 'name', 'priceCents', 'quantity']);
          // allowedSortFields.has(field);
          const example = "const quotedFields = ['sku', 'name', 'priceCents', 'quantity'];"
              + " quotedFields.includes(field);";
          return [...items];
        }
        """;

    assertThat(sanitizeForRepository(validClaim(), source)).isEqualTo(GROUNDING_NOTE);
  }

  @Test
  void exhaustiveClaimMustEqualRatherThanSubsetTheBoundCollection() {
    String claim = "`sortInventory` accepts valid fields (`sku`, `name`, `priceCents`).";

    assertThat(sanitizeForRepository(claim, VALID_SOURCE)).isEqualTo(GROUNDING_NOTE);
  }

  @Test
  void aBoundLiteralArrayIncludesCheckCanAuthorizeAnExactExhaustiveClaim() {
    String source =
        """
        export function sortInventory(items, field) {
          const allowedSortFields = ['sku', 'name', 'priceCents', 'quantity'];
          if (!allowedSortFields.includes(field)) throw new Error('invalid field');
          return [...items];
        }
        """;

    assertThat(sanitizeForRepository(validClaim(), source)).isEqualTo(validClaim());
  }

  @Test
  void sourcePathAndLineCitationIsNotTreatedAsAContractValue() {
    String claim =
        "`sortInventory` accepts valid fields (`sku`, `name`, `priceCents`, `quantity`), "
            + "defined at `src/inventory.js:2`.";

    assertThat(sanitizeForRepository(claim, VALID_SOURCE)).isEqualTo(claim);
  }

  @Test
  void plainUnquotedProseCannotBypassFailClosedGrounding() {
    String claim =
        "sortInventory accepts valid fields sku, name, category, priceCents and quantity.";

    assertThat(
            GeneratedSourceContractGrounding.sanitizeForRepository(claim, SYMBOLS, REPOSITORY, SHA))
        .isEqualTo(GROUNDING_NOTE);
  }

  @Test
  void purposeProseAndAnotherFunctionCannotCrossGroundTheSubjectSymbol() {
    String sortBody =
        """
        export function sortInventory(items, field) {
          if (!ALLOWED_SORT_FIELDS.has(field)) throw new Error('invalid field');
          return [...items];
        }
        """;
    String otherBody =
        """
        export function normalizeItem(item) {
          const allowed = new Set(['sku', 'name', 'category', 'priceCents', 'quantity']);
          if (allowed.has(item.field)) return item;
          return { category: item.category ?? 'category' };
        }
        """;
    String exact =
        ExportedFunctionEvidence.render(
            REPOSITORY, SHA, Map.of("src/sort.js", sortBody, "src/normalize.js", otherBody));
    String purpose = "\n**Purpose:** supports category, sku, name, priceCents, and quantity.\n";

    assertThat(
            GeneratedSourceContractGrounding.sanitizeForRepository(
                LIVE_WRAPPED_FALSE_CLAIM, SYMBOLS + purpose + exact, REPOSITORY, SHA))
        .contains(GROUNDING_NOTE)
        .doesNotContain("validated field");
  }

  @Test
  void truncatedSameSymbolEvidenceCannotAuthorizeAnExhaustiveContract() {
    String source =
        "export function sortInventory(items, field) {\n"
            + "  const allowedSortFields = new Set(['sku', 'name', 'priceCents', 'quantity']);\n"
            + "  if (!allowedSortFields.has(field)) throw new Error('invalid');\n"
            + "  const padding = '"
            + "x".repeat(1_600)
            + "';\n  return items;\n}\n";
    String evidence = exactEvidence(source);

    assertThat(evidence).contains("Truncated: true");
    assertThat(
            GeneratedSourceContractGrounding.sanitizeForRepository(
                validClaim(), SYMBOLS + evidence, REPOSITORY, SHA))
        .isEqualTo(GROUNDING_NOTE);
  }

  @Test
  void noAuthorityContextAlwaysDegradesEvenValidSingleRepositoryEvidence() {
    String evidence = exactEvidence(VALID_SOURCE);

    assertThat(
            GeneratedSourceContractGrounding.sanitizeWithoutSourceAuthority(
                validClaim(), SYMBOLS + evidence))
        .isEqualTo(GROUNDING_NOTE);
  }

  @Test
  void noAuthorityContextPreventsCrossRepositorySymbolGrounding() {
    String repoOne = exactEvidence(VALID_SOURCE);
    String repoTwo =
        ExportedFunctionEvidence.render(
            "acme/catalog", OTHER_SHA, Map.of("src/catalog.js", VALID_SOURCE));

    assertThat(
            GeneratedSourceContractGrounding.sanitizeWithoutSourceAuthority(
                validClaim(), SYMBOLS + repoOne + repoTwo))
        .isEqualTo(GROUNDING_NOTE);
  }

  @Test
  void preservesMarkdownWrappersWhileDegradingProseClaims() {
    String markdown =
        """
        ### `sortInventory` only supports fields (`sku`, `name`, `category`).

        | Contract |
        | --- |
        | `sortInventory` accepts valid fields (`sku`, `name`, `category`). |
        """;

    String sanitized =
        GeneratedSourceContractGrounding.sanitizeWithoutSourceAuthority(markdown, SYMBOLS);

    assertThat(sanitized)
        .contains("### " + GROUNDING_NOTE)
        .contains("| " + GROUNDING_NOTE + " |")
        .doesNotContain("`category`");
  }

  @Test
  void fencedAndIndentedCodeExamplesRemainByteExactWhileProseIsDegraded() {
    String claim = "`sortInventory` accepts valid fields (`sku`, `name`, `category`).";
    String markdown =
        claim
            + "\n\n```text\n"
            + claim
            + "\n```\n\n~~~text\n"
            + claim
            + "\n~~~\n\n    "
            + claim
            + "\n";

    String sanitized =
        GeneratedSourceContractGrounding.sanitizeWithoutSourceAuthority(markdown, SYMBOLS);

    assertThat(sanitized)
        .startsWith(GROUNDING_NOTE)
        .contains("```text\n" + claim + "\n```")
        .contains("~~~text\n" + claim + "\n~~~")
        .contains("    " + claim);
    assertThat(occurrences(sanitized, claim)).isEqualTo(3);
  }

  @Test
  void canonicalGroundingNoteIsExactlyIdempotent() {
    String markdown = "- " + GROUNDING_NOTE + "\n";

    assertThat(GeneratedSourceContractGrounding.sanitizeWithoutSourceAuthority(markdown, SYMBOLS))
        .isEqualTo(markdown);
  }

  @Test
  void groundingNoteSubstringCannotHideASecondUnsupportedClaim() {
    String malicious =
        GROUNDING_NOTE
            + " However, `sortInventory` only supports fields (`sku`, `name`, `category`).";

    String sanitized =
        GeneratedSourceContractGrounding.sanitizeWithoutSourceAuthority(malicious, SYMBOLS);

    assertThat(sanitized).isEqualTo(GROUNDING_NOTE).doesNotContain("`category`");
  }

  @Test
  void recognizesDirectExhaustiveContractWordingVariants() {
    assertDegraded("`sortInventory` validates fields (`sku`, `name`).");
    assertDegraded("`sortInventory` allows options (`sku`, `name`).");
    assertDegraded("`sortInventory` fields are restricted to (`sku`, `name`).");
    assertDegraded("`sortInventory` only supports fields (`sku`, `name`).");
    assertDegraded("Valid modes for `sortInventory` are (`sku`, `name`).");
  }

  private static void assertDegraded(String claim) {
    assertThat(GeneratedSourceContractGrounding.sanitizeWithoutSourceAuthority(claim, SYMBOLS))
        .isEqualTo(GROUNDING_NOTE);
  }

  private static String sanitizeForRepository(String claim, String source) {
    return GeneratedSourceContractGrounding.sanitizeForRepository(
        claim, SYMBOLS + exactEvidence(source), REPOSITORY, SHA);
  }

  private static String exactEvidence(String source) {
    return ExportedFunctionEvidence.render(REPOSITORY, SHA, Map.of("src/inventory.js", source));
  }

  private static String validClaim() {
    return "`sortInventory` accepts valid fields (`sku`, `name`, `priceCents`, `quantity`).";
  }

  private static int occurrences(String value, String needle) {
    int count = 0;
    for (int at = value.indexOf(needle);
        at >= 0;
        at = value.indexOf(needle, at + needle.length())) {
      count++;
    }
    return count;
  }
}
