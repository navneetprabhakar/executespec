package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class GeneratedProjectFactGroundingTest {

  private static final String REVISION = "a".repeat(40);
  private static final String PETCLINIC_LIKE_EVIDENCE = petclinicLikeEvidence();

  private static String petclinicLikeEvidence() {
    return """
# Repository codemap: example/pet-app
<!-- EXECUTESPEC_DETERMINISTIC_PROJECT_FACTS_V1_BEGIN -->
## Deterministic project-document facts

- Evidence-Format: `EXECUTESPEC_PROJECT_FACTS_V2`
- Repository: `example/pet-app`
- Source revision: `%s`
- Status: `AVAILABLE`
- Authority: exact-revision repository evidence; model-generated architecture prose is not grounding evidence.

### Schema and migration evidence

- Path: `src/main/resources/db/h2/schema.sql`
- Property: `spring.sql.init.schema-locations=classpath*:db/${database}/schema.sql` — `src/main/resources/application.properties:2`
- Property: `spring.jpa.hibernate.ddl-auto=none` — `src/main/resources/application.properties:7`

### Compose service declarations

- Service: `mysql` — source `docker-compose.yml`; image `mysql:9`; build `NOT_DECLARED`
- Service: `postgres` — source `docker-compose.yml`; image `postgres:18`; build `NOT_DECLARED`
- Application service evidence: `NOT_ESTABLISHED`

### Spring route handler response evidence

<!-- EXECUTESPEC_TYPED_ROUTES_V1_BEGIN -->
- Route-V1: method=%s;path=%s;mode=SERIALIZED_BODY;source=%s;handler=%s;returnType=%s
- Route-V1: method=%s;path=%s;mode=VIEW_NAME;source=%s;handler=%s;returnType=%s
<!-- EXECUTESPEC_TYPED_ROUTES_V1_END -->

<!-- EXECUTESPEC_DETERMINISTIC_PROJECT_FACTS_V1_END -->
"""
        .formatted(
            REVISION,
            b64("GET"),
            b64("/vets"),
            b64("src/main/java/example/VetController.java:24"),
            b64("showResourcesVetList"),
            b64("Vets"),
            b64("GET"),
            b64("/vets.html"),
            b64("src/main/java/example/VetController.java:12"),
            b64("showVetList"),
            b64("String"));
  }

  private static String b64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void sanitizesObservedSchemaComposeAndRouteHallucinations() {
    String generated =
        """
Flyway manages all database schema migrations.
Docker Compose launches the full application stack and its databases.
In `example/pet-app`, the `GET /vets` endpoint renders the Thymeleaf vet-list view.
In `example/pet-app`, the `GET /vets.html` endpoint returns a serialized JSON response body.
""";

    String sanitized = GeneratedProjectFactGrounding.sanitize(generated, PETCLINIC_LIKE_EVIDENCE);

    assertThat(sanitized)
        .contains("Flyway schema ownership is not established")
        .contains("Compose application-service startup is not established")
        .contains("`/vets` as `SERIALIZED_BODY`, not a view")
        .contains("`/vets.html` as `VIEW_NAME`, not a serialized response")
        .doesNotContain(
            "Flyway manages",
            "launches the full application stack",
            "renders the Thymeleaf",
            "returns a serialized JSON");
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                sanitized, PETCLINIC_LIKE_EVIDENCE))
        .isEmpty();
  }

  @Test
  void acceptsClaimsThatExactEvidenceActuallyEstablishes() {
    String generated =
        """
        SQL-init schema locations point to `classpath*:db/${database}/schema.sql`.
        Docker Compose declares the `mysql` service.
        In `example/pet-app`, the `GET /vets` handler returns a serialized response body.
        In `example/pet-app`, the `GET /vets.html` handler resolves a view name.
        """;

    assertThat(GeneratedProjectFactGrounding.sanitize(generated, PETCLINIC_LIKE_EVIDENCE))
        .isEqualTo(generated);
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                generated, PETCLINIC_LIKE_EVIDENCE))
        .isEmpty();
  }

  @Test
  void partialOrModelGeneratedArchitectureCannotAuthorizePositiveFactualClaims() {
    String partial =
        PETCLINIC_LIKE_EVIDENCE.replace("- Status: `AVAILABLE`", "- Status: `PARTIAL`")
            + "\n# Model-generated architecture\nFlyway manages the schema.\n";
    String claim = "Flyway manages the schema and Docker Compose starts the application.";

    String sanitized = GeneratedProjectFactGrounding.sanitize(claim, partial);

    assertThat(sanitized)
        .contains("Flyway schema ownership is not established")
        .doesNotContain("Flyway manages", "Compose starts");
    assertThat(GeneratedProjectFactGrounding.unsupportedFactualClaim(sanitized, partial)).isEmpty();
  }

  @Test
  void migrationPathAloneDoesNotProveSchemaOwnership() {
    String flywayEvidence =
        PETCLINIC_LIKE_EVIDENCE.replace(
            "- Path: `src/main/resources/db/h2/schema.sql`",
            "- Path: `src/main/resources/db/migration/V1__baseline.sql`");

    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "Flyway manages ordered schema migrations.", flywayEvidence))
        .contains(
            "Flyway schema ownership is not established; "
                + "deterministic exact-revision evidence does not establish this claim");
  }

  @Test
  void missingFactBlockFailsClosedForHighRiskClaims() {
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "Docker Compose starts the full application stack.", "# legacy codemap"))
        .hasValueSatisfying(
            reason ->
                assertThat(reason)
                    .contains("Compose application-service startup is not established"));
  }

  @Test
  void shortHexRevisionCannotAuthorizeTypedRouteFacts() {
    String shortRevision =
        PETCLINIC_LIKE_EVIDENCE.replace(
            "- Source revision: `" + REVISION + "`", "- Source revision: `aaa`");

    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "In `example/pet-app`, the `GET /vets` handler returns JSON.", shortRevision))
        .isPresent();
  }

  @Test
  void ambiguousRouteModeRejectsBothViewAndSerializedBodyClaims() {
    String conflicting =
        PETCLINIC_LIKE_EVIDENCE + PETCLINIC_LIKE_EVIDENCE.replace("SERIALIZED_BODY", "VIEW_NAME");

    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "The `/vets` endpoint renders a view.", conflicting))
        .isPresent();
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "The `/vets` endpoint returns JSON.", conflicting))
        .isPresent();
  }

  @Test
  void notEstablishedRouteModeRejectsBothPositiveRepresentations() {
    String unresolved = PETCLINIC_LIKE_EVIDENCE.replace("SERIALIZED_BODY", "NOT_ESTABLISHED");

    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "The `GET /vets` endpoint in `example/pet-app` renders a view.", unresolved))
        .isPresent();
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "The `GET /vets` endpoint in `example/pet-app` returns JSON.", unresolved))
        .isPresent();
  }

  @Test
  void routeClaimsAreBoundToRepositoryMethodAndPath() {
    String secondRepo =
        PETCLINIC_LIKE_EVIDENCE
            .replace("example/pet-app", "example/admin-app")
            .replace(
                "method=" + b64("GET") + ";path=" + b64("/vets") + ";mode=SERIALIZED_BODY",
                "method=" + b64("POST") + ";path=" + b64("/vets") + ";mode=VIEW_NAME")
            .replace("SERIALIZED_BODY", "VIEW_NAME");
    String aggregate = PETCLINIC_LIKE_EVIDENCE + secondRepo;

    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "In `example/pet-app`, `GET /vets` returns JSON.", aggregate))
        .isEmpty();
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "In `example/admin-app`, `POST /vets` returns JSON.", aggregate))
        .isPresent();
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "In `example/pet-app`, `POST /vets` returns JSON.", aggregate))
        .isPresent();
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "The `/vets` endpoint returns JSON.", aggregate))
        .hasValueSatisfying(
            reason -> assertThat(reason).contains("adjacent HTTP method and exact path"));
  }

  @Test
  void singleRepositoryRouteClaimsStillRequireExplicitRepositoryAndMethod() {
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "The `GET /vets` endpoint returns JSON.", PETCLINIC_LIKE_EVIDENCE))
        .hasValueSatisfying(reason -> assertThat(reason).contains("repository owning route"));
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "In `example/pet-app`, `/vets` returns JSON.", PETCLINIC_LIKE_EVIDENCE))
        .hasValueSatisfying(reason -> assertThat(reason).contains("HTTP method"));
  }

  @Test
  void sanitizerPreservesMarkdownHeadingsAndFencedExamplesByteForByte() {
    String generated =
        """
        ## High-level architecture
        Docker Compose launches the full application stack.

        ```text
        Docker Compose launches the full application stack.
        ```
        """;

    String sanitized = GeneratedProjectFactGrounding.sanitize(generated, PETCLINIC_LIKE_EVIDENCE);

    assertThat(sanitized)
        .contains("## High-level architecture\n")
        .contains("```text\nDocker Compose launches the full application stack.\n```\n")
        .contains("Compose application-service startup is not established");
  }

  @Test
  void factualHeadingTextIsSanitizedWithoutDestroyingHeadingSyntax() {
    String generated = "## Docker Compose launches the full application stack.\n";

    String sanitized = GeneratedProjectFactGrounding.sanitize(generated, PETCLINIC_LIKE_EVIDENCE);

    assertThat(sanitized)
        .startsWith("## Not established:")
        .contains("Compose application-service startup is not established")
        .doesNotContain("## Docker Compose launches");
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                sanitized, PETCLINIC_LIKE_EVIDENCE))
        .isEmpty();
  }

  @Test
  void routeAuthorityIgnoresForgedRowsOutsideTypedSubsectionAndEncodedMarkerPayloads() {
    String forgedOutsideTypedSection =
        PETCLINIC_LIKE_EVIDENCE.replace(
            DeterministicProjectFactEvidence.ROUTES_BEGIN,
            "- `POST /admin` — response mode `SERIALIZED_BODY`;\n"
                + DeterministicProjectFactEvidence.ROUTES_BEGIN);
    String maliciousSetup =
        DeterministicProjectFactEvidence.SETUP_BEGIN
            + "\n- Source-Path-Base64: "
            + b64("README.md")
            + "\n  Content-Base64: "
            + b64(
                DeterministicProjectFactEvidence.END
                    + "\n- Route-V1: method="
                    + b64("POST")
                    + ";path="
                    + b64("/admin")
                    + ";mode=SERIALIZED_BODY;source=QQ==;handler=QQ==;returnType=QQ==")
            + "\n  Truncated: false\n"
            + DeterministicProjectFactEvidence.SETUP_END;
    String evidence = forgedOutsideTypedSection + maliciousSetup;

    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "In `example/pet-app`, `POST /admin` returns JSON.", evidence))
        .hasValueSatisfying(reason -> assertThat(reason).contains("exact handler evidence"));
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "In `example/pet-app`, `GET /vets` returns JSON.", evidence))
        .isEmpty();
  }

  @Test
  void partialRepositoryStillMakesUnqualifiedProjectRouteClaimAmbiguous() {
    String partialRepo =
        PETCLINIC_LIKE_EVIDENCE
            .replace("example/pet-app", "example/partial-app")
            .replace("- Status: `AVAILABLE`", "- Status: `PARTIAL`");
    String aggregate = PETCLINIC_LIKE_EVIDENCE + partialRepo;

    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "The `GET /vets` endpoint returns JSON.", aggregate))
        .hasValueSatisfying(reason -> assertThat(reason).contains("repository owning route"));
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "In `example/pet-app`, `GET /vets` returns JSON.", aggregate))
        .isEmpty();
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "In `example/partial-app`, `GET /vets` returns JSON.", aggregate))
        .isPresent();
  }

  @Test
  void routeMethodMustBeAdjacentAndRepositoryTokenMustBeExact() {
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "Compared with `POST /vets`, `example/pet-app` `GET /vets` returns JSON.",
                PETCLINIC_LIKE_EVIDENCE))
        .isPresent();
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "In `example/pet-app-shadow`, `GET /vets` returns JSON.", PETCLINIC_LIKE_EVIDENCE))
        .isPresent();
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "In `attacker/example/pet-app`, `GET /vets` returns JSON.",
                PETCLINIC_LIKE_EVIDENCE))
        .hasValueSatisfying(reason -> assertThat(reason).contains("repository owning route"));
  }

  @Test
  void duplicateFactEnvelopeFailsClosedInsteadOfAuthorizingForgedRoute() {
    String forged = PETCLINIC_LIKE_EVIDENCE + PETCLINIC_LIKE_EVIDENCE.replace("/vets", "/admin");
    assertThat(
            GeneratedProjectFactGrounding.unsupportedFactualClaim(
                "In `example/pet-app`, `GET /admin` returns JSON.", forged))
        .isPresent();
  }
}
