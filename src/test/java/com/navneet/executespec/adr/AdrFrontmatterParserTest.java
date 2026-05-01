package com.navneet.executespec.adr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class AdrFrontmatterParserTest {

  private final AdrFrontmatterParser parser = new AdrFrontmatterParser();

  @Test
  void parsesAllRequiredAndOptionalFields() {
    String content =
        """
        ---
        id: 42
        title: Use Postgres for transactional storage
        status: accepted
        date: 2026-04-25
        deciders: [navneet, alice]
        supersedes: [10, 11]
        related_adrs: [40, 41]
        related_standards: ['no-checked-in-secrets']
        tags: [database, persistence]
        ---

        # ADR 0042: Use Postgres for transactional storage

        ## Context
        ...
        """;
    AdrFrontmatterParser.Parsed p = parser.parse(content);
    assertThat(p.id()).isEqualTo(42);
    assertThat(p.title()).isEqualTo("Use Postgres for transactional storage");
    assertThat(p.status()).isEqualTo(Adr.Status.ACCEPTED);
    assertThat(p.date()).isEqualTo(LocalDate.of(2026, 4, 25));
    assertThat(p.deciders()).containsExactly("navneet", "alice");
    assertThat(p.supersedes()).containsExactly(10, 11);
    assertThat(p.relatedStandards()).containsExactly("no-checked-in-secrets");
    assertThat(p.markdownBody()).contains("# ADR 0042");
  }

  @Test
  void missingFrontmatter_throws() {
    assertThatThrownBy(() -> parser.parse("# Just markdown\nNo frontmatter here."))
        .isInstanceOf(AdrFrontmatterParser.FrontmatterException.class)
        .hasMessageContaining("missing YAML frontmatter");
  }

  @Test
  void emptyFile_throws() {
    assertThatThrownBy(() -> parser.parse(""))
        .isInstanceOf(AdrFrontmatterParser.FrontmatterException.class);
    assertThatThrownBy(() -> parser.parse(null))
        .isInstanceOf(AdrFrontmatterParser.FrontmatterException.class);
  }

  @Test
  void allMissingRequiredFields_collectedInOnePass() {
    String content =
        """
        ---
        tags: [x]
        ---
        Body
        """;
    assertThatThrownBy(() -> parser.parse(content))
        .isInstanceOf(AdrFrontmatterParser.FrontmatterException.class)
        .satisfies(
            ex ->
                assertThat(((AdrFrontmatterParser.FrontmatterException) ex).errors)
                    .containsExactlyInAnyOrder(
                        "'id' required", "'title' required",
                        "'status' required", "'date' required"));
  }

  @Test
  void invalidStatus_collected() {
    String content =
        """
        ---
        id: 1
        title: x
        status: super-accepted
        date: 2026-04-25
        ---
        Body
        """;
    assertThatThrownBy(() -> parser.parse(content))
        .isInstanceOf(AdrFrontmatterParser.FrontmatterException.class)
        .hasMessageContaining("'status' must be PROPOSED|ACCEPTED|DEPRECATED|SUPERSEDED");
  }

  @Test
  void invalidDateFormat_collected() {
    String content =
        """
        ---
        id: 1
        title: x
        status: accepted
        date: 25-04-2026
        ---
        Body
        """;
    assertThatThrownBy(() -> parser.parse(content))
        .isInstanceOf(AdrFrontmatterParser.FrontmatterException.class)
        .hasMessageContaining("'date' must be ISO yyyy-MM-dd");
  }

  @Test
  void status_caseInsensitive() {
    String content =
        """
        ---
        id: 1
        title: x
        status: ACCEPTED
        date: 2026-04-25
        ---
        Body
        """;
    assertThat(parser.parse(content).status()).isEqualTo(Adr.Status.ACCEPTED);
  }

  @Test
  void integerIdRequired() {
    String content =
        """
        ---
        id: forty-two
        title: x
        status: accepted
        date: 2026-04-25
        ---
        Body
        """;
    assertThatThrownBy(() -> parser.parse(content))
        .isInstanceOf(AdrFrontmatterParser.FrontmatterException.class)
        .hasMessageContaining("'id' must be an integer");
  }

  @Test
  void adrStatusEnum_normalisesUnknown_toProposed() {
    Adr a = new Adr();
    a.setStatus("FUTURE_STATUS");
    assertThat(a.statusEnum()).isEqualTo(Adr.Status.PROPOSED);
    a.setStatus(null);
    assertThat(a.statusEnum()).isEqualTo(Adr.Status.PROPOSED);
    a.setStatus("ACCEPTED");
    assertThat(a.statusEnum()).isEqualTo(Adr.Status.ACCEPTED);
  }
}
