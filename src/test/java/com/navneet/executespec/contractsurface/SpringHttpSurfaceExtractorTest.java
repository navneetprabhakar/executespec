package com.navneet.executespec.contractsurface;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor.RouteKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringHttpSurfaceExtractorTest {

  @TempDir Path workspace;

  private final SpringHttpSurfaceExtractor extractor = new SpringHttpSurfaceExtractor();

  @Test
  void combinesContextClassAndMethodMappingsIncludingArrays() throws Exception {
    write(
        "src/main/resources/application.yml",
        """
        server:
          servlet:
            context-path: /simulation/
        """);
    write(
        "src/main/java/example/CasinoController.java",
        """
        package example;

        @RestController
        @RequestMapping(path = {"/casino", "/legacy-casino"})
        public class CasinoController {
          @PostMapping(path = {"", "/"})
          public Result create() { return null; }

          @RequestMapping(value = {"/status", "/health/"},
              method = {RequestMethod.GET, RequestMethod.HEAD})
          public Result status() { return null; }
        }
        """);

    var snapshot = extractor.extract(workspace);
    Set<RouteKey> keys =
        snapshot.routes().stream()
            .map(SpringHttpSurfaceExtractor.Route::key)
            .collect(Collectors.toSet());

    assertThat(keys)
        .containsExactlyInAnyOrder(
            new RouteKey("POST", "/simulation/casino"),
            new RouteKey("POST", "/simulation/legacy-casino"),
            new RouteKey("GET", "/simulation/casino/status"),
            new RouteKey("HEAD", "/simulation/casino/status"),
            new RouteKey("GET", "/simulation/casino/health"),
            new RouteKey("HEAD", "/simulation/casino/health"),
            new RouteKey("GET", "/simulation/legacy-casino/status"),
            new RouteKey("HEAD", "/simulation/legacy-casino/status"),
            new RouteKey("GET", "/simulation/legacy-casino/health"),
            new RouteKey("HEAD", "/simulation/legacy-casino/health"));
    assertThat(snapshot.digest()).matches("[0-9a-f]{64}");
    assertThat(snapshot.routes())
        .allMatch(route -> route.source().contains("CasinoController.java:"));
    assertThat(snapshot.configurationSources())
        .extracting(source -> source.value() + " " + source.source())
        .containsExactly("/simulation src/main/resources/application.yml:3");
    assertThat(snapshot.schemaVersion()).isEqualTo(2);
  }

  @Test
  void immutableSourceSnapshotPreservesExactRouteAndConfigurationProvenance() {
    var snapshot =
        extractor.extract(
            Map.of(
                "src/main/resources/application.properties",
                "spring.application.name=casino\nserver.servlet.context-path=/simulation\n",
                "src/main/java/example/SimulationController.java",
                """
                package example;
                @RestController
                class SimulationController {
                  @PostMapping("/casino-game")
                  Object simulate() { return null; }
                }
                """));

    assertThat(snapshot.routes())
        .extracting(route -> route.method() + " " + route.path() + " " + route.source())
        .containsExactly(
            "POST /simulation/casino-game src/main/java/example/SimulationController.java:4");
    assertThat(snapshot.configurationSources())
        .extracting(source -> source.property() + "=" + source.value() + " " + source.source())
        .containsExactly(
            "server.servlet.context-path=/simulation"
                + " src/main/resources/application.properties:2");
  }

  @Test
  void parsesJavaRecordsWhenExtractingWorkspaceSurface() throws Exception {
    write(
        "src/main/java/example/ClinicSummary.java",
        """
        package example;
        public record ClinicSummary(long ownerCount, long vetCount) {}
        """);
    write(
        "src/main/java/example/ClinicSummaryController.java",
        """
        package example;
        @RestController
        class ClinicSummaryController {
          @GetMapping("/api/clinic/summary")
          ClinicSummary summary() { return new ClinicSummary(0, 0); }
        }
        """);

    assertThat(extractor.extract(workspace).routes())
        .extracting(route -> route.method() + " " + route.path())
        .containsExactly("GET /api/clinic/summary");
  }

  @Test
  void parsesJavaRecordsWhenExtractingImmutableSourceSnapshot() {
    var snapshot =
        extractor.extract(
            Map.of(
                "src/main/java/example/ClinicSummary.java",
                "package example; public record ClinicSummary(long ownerCount) {}",
                "src/main/java/example/ClinicSummaryController.java",
                """
                package example;
                @RestController
                class ClinicSummaryController {
                  @GetMapping("/api/clinic/summary")
                  ClinicSummary summary() { return new ClinicSummary(0); }
                }
                """));

    assertThat(snapshot.routes())
        .extracting(route -> route.method() + " " + route.path())
        .containsExactly("GET /api/clinic/summary");
  }

  @Test
  void rejectsRecoveredMalformedJavaWhenExtractingWorkspaceSurface() throws Exception {
    write(
        "src/main/java/example/BrokenController.java",
        """
        package example;
        @RestController
        class BrokenController {
          @GetMapping("/api/broken")
          Object broken( { return null; }
        }
        """);

    assertThatThrownBy(() -> extractor.extract(workspace))
        .isInstanceOf(SpringHttpSurfaceExtractor.SurfaceExtractionException.class)
        .hasMessageContaining("BrokenController.java");
  }

  @Test
  void rejectsRecoveredMalformedJavaWhenExtractingImmutableSourceSnapshot() {
    assertThatThrownBy(
            () ->
                extractor.extract(
                    Map.of(
                        "src/main/java/example/BrokenController.java",
                        """
                        package example;
                        @RestController
                        class BrokenController {
                          @GetMapping("/api/broken")
                          Object broken( { return null; }
                        }
                        """)))
        .isInstanceOf(SpringHttpSurfaceExtractor.SurfaceExtractionException.class)
        .hasMessageContaining("BrokenController.java");
  }

  @Test
  void requestMappingWithoutMethodIsCanonicalAnyAndTestSourcesAreIgnored() throws Exception {
    write("src/main/resources/application.properties", "server.servlet.context-path=/root\n");
    write(
        "src/main/java/example/Controller.java",
        """
        @RequestMapping("/v1/")
        class Controller {
          @RequestMapping
          void any() {}
          @DeleteMapping("items/{id}/")
          void delete() {}
        }
        """);
    write(
        "src/test/java/example/FakeController.java",
        """
        class FakeController {
          @GetMapping("/must-not-appear")
          void fake() {}
        }
        """);

    assertThat(extractor.extract(workspace).routes())
        .extracting(route -> route.method() + " " + route.path())
        .containsExactly("ANY /root/v1", "DELETE /root/v1/items/{id}");
  }

  @Test
  void inventoriesMultipleControllersInOneSourceAgainstTheirOwnClassMappings() throws Exception {
    write(
        "src/main/java/example/Controllers.java",
        """
        package example;

        @RestController
        @RequestMapping("/orders")
        class OrderController {
          @GetMapping("/{id}")
          Object get() { return null; }
        }

        @RestController
        @RequestMapping("/customers")
        class CustomerController {
          @PostMapping
          Object create() { return null; }
        }
        """);

    assertThat(extractor.extract(workspace).routes())
        .extracting(route -> route.method() + " " + route.path())
        .containsExactly("GET /orders/{id}", "POST /customers");
  }

  @Test
  void resolvesContextPathPerModuleWithoutLeakingFirstRepositoryConfiguration() throws Exception {
    write(
        "repos/catalog/src/main/resources/application.yml",
        """
        server:
          servlet:
            context-path: /catalog
        """);
    write(
        "repos/catalog/src/main/java/example/CatalogController.java",
        """
        @RestController
        @RequestMapping("/items")
        class CatalogController {
          @GetMapping
          Object list() { return null; }
        }
        """);
    write(
        "repos/checkout/src/main/resources/application.properties",
        "server.servlet.context-path=/checkout\n");
    write(
        "repos/checkout/src/main/java/example/CheckoutController.java",
        """
        @RestController
        class CheckoutController {
          @PostMapping("/orders")
          Object create() { return null; }
        }
        """);

    assertThat(extractor.extract(workspace).routes())
        .extracting(route -> route.method() + " " + route.path())
        .containsExactly("GET /catalog/items", "POST /checkout/orders");
  }

  @Test
  void inventoriesDeepMultiRepositoryControllerWithoutDepthTruncation() throws Exception {
    String module = "repos/customer-app/services/edge/apps/api/modules/casino";
    write(
        module + "/src/main/resources/application.yml",
        """
        server:
          servlet:
            context-path: /simulation
        """);
    write(
        module
            + "/src/main/java/com/acme/platform/simulation/http/controller/CasinoController.java",
        """
        package com.acme.platform.simulation.http.controller;

        @RestController
        class CasinoController {
          @PostMapping("/casino-game")
          Object simulate() { return null; }
        }
        """);

    assertThat(extractor.extract(workspace).routes())
        .extracting(route -> route.method() + " " + route.path() + " " + route.source())
        .containsExactly(
            "POST /simulation/casino-game "
                + module
                + "/src/main/java/com/acme/platform/simulation/http/controller/"
                + "CasinoController.java:5");
  }

  @Test
  void skipsBuildVendorAndGitTreesBeforeApplyingTraversalBudgets() throws Exception {
    write(
        "src/main/java/example/Controller.java",
        """
        @RestController
        class Controller {
          @GetMapping("/health")
          Object health() { return null; }
        }
        """);
    write("target/deep/Broken.java", "not valid java");
    write("build/deep/Broken.java", "not valid java");
    write("dist/deep/Broken.java", "not valid java");
    write("vendor/deep/Broken.java", "not valid java");
    write(".git/objects/Broken.java", "not valid java");

    var snapshot =
        extractor.extract(
            workspace, new SpringContractSourceInventory.TraversalLimits(16, 1, 1024));

    assertThat(snapshot.routes())
        .extracting(route -> route.method() + " " + route.path())
        .containsExactly("GET /health");
  }

  @Test
  void inventoriesProductionControllerUnderGeneratedNamedPath() throws Exception {
    write(
        "repos/generated/src/main/java/example/GeneratedController.java",
        """
        @RestController
        class GeneratedController {
          @GetMapping("/generated-health")
          Object health() { return null; }
        }
        """);

    assertThat(extractor.extract(workspace).routes())
        .extracting(route -> route.method() + " " + route.path())
        .containsExactly("GET /generated-health");
  }

  @Test
  void failsClosedWhenEmptyDirectoryTraversalExceedsEntryBudget() throws Exception {
    Files.createDirectories(workspace.resolve("one/two/three"));

    assertThatThrownBy(
            () ->
                extractor.extract(
                    workspace, new SpringContractSourceInventory.TraversalLimits(2, 1, 1024)))
        .isInstanceOf(SpringHttpSurfaceExtractor.SurfaceExtractionException.class)
        .hasMessageContaining("visited-entry budget exceeded (3 > 2)")
        .hasMessageContaining("one/two");
  }

  @Test
  void rejectsSymlinkInsteadOfSilentlyOmittingLinkedController() throws Exception {
    write(
        "target/LinkedController.java",
        """
        @RestController
        class LinkedController {
          @GetMapping("/linked")
          Object linked() { return null; }
        }
        """);
    Path link = workspace.resolve("src/main/java/example/LinkedController.java");
    Files.createDirectories(link.getParent());
    Files.createSymbolicLink(link, workspace.resolve("target/LinkedController.java"));

    assertThatThrownBy(() -> extractor.extract(workspace))
        .isInstanceOf(SpringHttpSurfaceExtractor.SurfaceExtractionException.class)
        .hasMessageContaining("symbolic link cannot be admitted")
        .hasMessageContaining("src/main/java/example/LinkedController.java");
  }

  @Test
  void failsClosedWhenSourceFileBudgetIsExceeded() throws Exception {
    write("src/main/java/example/First.java", "class First {}");
    write("src/main/java/example/Second.java", "class Second {}");

    assertThatThrownBy(
            () ->
                extractor.extract(
                    workspace, new SpringContractSourceInventory.TraversalLimits(10, 1, 1024)))
        .isInstanceOf(SpringHttpSurfaceExtractor.SurfaceExtractionException.class)
        .hasMessageContaining("source-file budget exceeded (2 > 1)")
        .hasMessageContaining("src/main/java/example/");
  }

  @Test
  void boundedReadFailsClosedWhenSourceExceedsRemainingByteBudget() throws Exception {
    write(
        "src/main/java/example/Controller.java",
        """
        @RestController
        class Controller {
          @GetMapping("/health")
          Object health() { return null; }
        }
        """);

    assertThatThrownBy(
            () ->
                extractor.extract(
                    workspace, new SpringContractSourceInventory.TraversalLimits(10, 10, 16)))
        .isInstanceOf(SpringHttpSurfaceExtractor.SurfaceExtractionException.class)
        .hasMessageContaining("source-byte budget exceeded")
        .hasMessageContaining("src/main/java/example/Controller.java");
  }

  @Test
  void failsClosedForDynamicMappingPathInsteadOfSilentlyDroppingRoute() throws Exception {
    write(
        "src/main/java/example/DynamicController.java",
        """
        @RestController
        @RequestMapping(ApiPaths.ROOT)
        class DynamicController {
          @GetMapping(ApiPaths.ITEMS + "/{id}")
          Object get() { return null; }
        }
        """);

    assertThatThrownBy(() -> extractor.extract(workspace))
        .isInstanceOf(SpringHttpSurfaceExtractor.SurfaceExtractionException.class)
        .hasMessageContaining("dynamic mapping path expression")
        .hasMessageContaining("DynamicController.java:2");
  }

  @Test
  void failsClosedForComposedMetaMapping() throws Exception {
    write(
        "src/main/java/example/ComposedController.java",
        """
        @Target(ElementType.METHOD)
        @Retention(RetentionPolicy.RUNTIME)
        @GetMapping("/health")
        @interface HealthEndpoint {}

        @RestController
        class ComposedController {
          @HealthEndpoint
          Object health() { return null; }
        }
        """);

    assertThatThrownBy(() -> extractor.extract(workspace))
        .isInstanceOf(SpringHttpSurfaceExtractor.SurfaceExtractionException.class)
        .hasMessageContaining("composed/meta-annotated Spring mappings are not supported")
        .hasMessageContaining("ComposedController.java:3");
  }

  @Test
  void failsClosedForUnknownMappingAnnotationUsage() throws Exception {
    write(
        "src/main/java/example/ExternalMappingController.java",
        """
        @RestController
        class ExternalMappingController {
          @ExternalReadMapping("/health")
          Object health() { return null; }
        }
        """);

    assertThatThrownBy(() -> extractor.extract(workspace))
        .isInstanceOf(SpringHttpSurfaceExtractor.SurfaceExtractionException.class)
        .hasMessageContaining("unknown/composed mapping annotation cannot be inventoried");
  }

  @Test
  void failsClosedWhenModuleProfilesDeclareDifferentContextPaths() throws Exception {
    write(
        "service/src/main/resources/application.yml",
        """
        server:
          servlet:
            context-path: /service
        """);
    write(
        "service/src/main/resources/application-dev.properties",
        "server.servlet.context-path=/service-dev\n");
    write(
        "service/src/main/java/example/Controller.java",
        """
        @RestController
        class Controller {
          @GetMapping("/health")
          Object health() { return null; }
        }
        """);

    assertThatThrownBy(() -> extractor.extract(workspace))
        .isInstanceOf(SpringHttpSurfaceExtractor.SurfaceExtractionException.class)
        .hasMessageContaining("ambiguous server.servlet.context-path values for module service");
  }

  private void write(String relative, String content) throws Exception {
    Path path = workspace.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
  }
}
