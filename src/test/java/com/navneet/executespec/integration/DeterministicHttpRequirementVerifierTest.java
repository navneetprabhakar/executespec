package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeterministicHttpRequirementVerifierTest {

  @TempDir Path workspace;

  private final DeterministicHttpRequirementVerifier verifier =
      new DeterministicHttpRequirementVerifier(new SpringHttpSurfaceExtractor());

  @Test
  void exactRequiredRouteMustExistInCurrentSource() throws Exception {
    writeController("/todos");
    Plan plan =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Expose POST /todos")
                        .description("Create the endpoint")
                        .build()))
            .build();

    assertThat(verifier.verify(plan, null, workspace).missing()).isEmpty();
    assertThatThrownBy(
            () ->
                verifier.assertSatisfied(
                    plan, "The application must also expose GET /todos/{id}", workspace))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GET /todos/{id}");
  }

  @Test
  void nonHttpAndAmbiguousRequirementsAreNotApplicable() {
    Plan cli = Plan.builder().runtimeSurface(Plan.RuntimeSurface.CLI).build();
    Plan ambiguous = Plan.builder().runtimeSurface(Plan.RuntimeSurface.HTTP).build();

    assertThat(verifier.verify(cli, "implement POST /todos", workspace).applicable()).isFalse();
    assertThat(verifier.verify(ambiguous, "add a todo endpoint", workspace).applicable()).isFalse();
  }

  @Test
  void explicitRemovalIsNotTreatedAsARequiredNewRoute() {
    Plan plan = Plan.builder().runtimeSurface(Plan.RuntimeSurface.HTTP).build();

    assertThat(verifier.verify(plan, "Remove POST /legacy safely", workspace).applicable())
        .isFalse();
  }

  @Test
  void methodNotAllowedAcceptanceIsVerifiedWithoutRequiringTheForbiddenMethod() throws Exception {
    writeController("GET", "/api/system/ping");
    Plan plan = Plan.builder().runtimeSurface(Plan.RuntimeSurface.HTTP).build();
    String request =
        "Add GET /api/system/ping and prove POST /api/system/ping is rejected with HTTP 405.";

    var verification = verifier.verify(plan, request, workspace);

    assertThat(verification.required())
        .containsExactly(new SpringHttpSurfaceExtractor.RouteKey("GET", "/api/system/ping"));
    assertThat(verification.forbidden())
        .containsExactly(new SpringHttpSurfaceExtractor.RouteKey("POST", "/api/system/ping"));
    assertThat(verification.missing()).isEmpty();
    assertThat(verification.presentForbidden()).isEmpty();
    assertThat(verification.unprovenMethodRejections()).isEmpty();
    verifier.assertSatisfied(plan, request, workspace);
  }

  @Test
  void implementedForbiddenMethodFailsClosed() throws Exception {
    writeControllerBody(
        """
        @GetMapping("/api/system/ping")
        void ping() {}
        @PostMapping("/api/system/ping")
        void forbiddenPost() {}
        """);
    Plan plan = Plan.builder().runtimeSurface(Plan.RuntimeSurface.HTTP).build();

    assertThatThrownBy(
            () ->
                verifier.assertSatisfied(
                    plan,
                    "GET /api/system/ping must succeed and POST /api/system/ping must return 405",
                    workspace))
        .hasMessageContaining("DETERMINISTIC_HTTP_FORBIDDEN_METHOD_PRESENT")
        .hasMessageContaining("POST /api/system/ping");
  }

  @Test
  void absentEndpointCannotMasqueradeAsMethodNotAllowed() {
    Plan plan = Plan.builder().runtimeSurface(Plan.RuntimeSurface.HTTP).build();

    assertThatThrownBy(
            () ->
                verifier.assertSatisfied(
                    plan, "POST /api/system/ping must return HTTP 405", workspace))
        .hasMessageContaining("DETERMINISTIC_HTTP_METHOD_REJECTION_UNPROVEN");
  }

  @Test
  void exactUserPolarityOverridesContradictoryPlanRestatement() throws Exception {
    writeController("GET", "/api/system/ping");
    Plan plan =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .tasks(
                List.of(
                    PlannedTask.builder().id("T1").title("Expose POST /api/system/ping").build()))
            .build();

    verifier.assertSatisfied(
        plan,
        "GET /api/system/ping must succeed; POST /api/system/ping must be rejected with 405",
        workspace);
  }

  @Test
  void methodlessRequestMappingViolatesForbiddenMethodConstraint() throws Exception {
    writeControllerBody("@RequestMapping(\"/api/system/ping\")\nvoid ping() {}");
    Plan plan = Plan.builder().runtimeSurface(Plan.RuntimeSurface.HTTP).build();

    assertThatThrownBy(
            () ->
                verifier.assertSatisfied(
                    plan,
                    "GET /api/system/ping must succeed; POST /api/system/ping must return 405",
                    workspace))
        .hasMessageContaining("DETERMINISTIC_HTTP_FORBIDDEN_METHOD_PRESENT");
  }

  @Test
  void ordinaryPostSuccessContractRemainsRequired() throws Exception {
    writeController("POST", "/items");
    Plan plan = Plan.builder().runtimeSurface(Plan.RuntimeSurface.HTTP).build();

    verifier.assertSatisfied(plan, "POST /items returns HTTP 201", workspace);
  }

  private void writeController(String path) throws Exception {
    writeController("POST", path);
  }

  private void writeController(String method, String path) throws Exception {
    String annotation =
        method.substring(0, 1) + method.substring(1).toLowerCase(java.util.Locale.ROOT) + "Mapping";
    writeControllerBody("@" + annotation + "(\"" + path + "\")\nvoid route() {}");
  }

  private void writeControllerBody(String body) throws Exception {
    Path file = workspace.resolve("src/main/java/example/TodoController.java");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        package example;
        @RestController
        class TodoController {
          %s
        }
        """
            .formatted(body));
  }
}
