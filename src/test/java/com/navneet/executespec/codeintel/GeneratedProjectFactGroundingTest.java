package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeneratedProjectFactGroundingTest {

  private static final String PETCLINIC_LIKE_EVIDENCE =
      """
# Repository codemap: example/pet-app
<!-- EXECUTESPEC_DETERMINISTIC_PROJECT_FACTS_V1_BEGIN -->
## Deterministic project-document facts

- Repository: `example/pet-app`
- Source revision: `deadbeef`
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

- `GET /vets` — handler `showResourcesVetList`; return type `Vets`; response mode `SERIALIZED_BODY`; source `src/main/java/example/VetController.java:24`
- `GET /vets.html` — handler `showVetList`; return type `String`; response mode `VIEW_NAME`; source `src/main/java/example/VetController.java:12`

<!-- EXECUTESPEC_DETERMINISTIC_PROJECT_FACTS_V1_END -->
""";

  @Test
  void sanitizesObservedSchemaComposeAndRouteHallucinations() {
    String generated =
        """
        Flyway manages all database schema migrations.
        Docker Compose launches the full application stack and its databases.
        The `/vets` endpoint renders the Thymeleaf vet-list view.
        The `/vets.html` endpoint returns a serialized JSON response body.
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
        The `/vets` handler returns a serialized response body.
        The `/vets.html` handler resolves a view name.
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
            .replace("GET /vets`", "POST /vets`")
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
        .hasValueSatisfying(reason -> assertThat(reason).contains("repository owning route"));
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
}
