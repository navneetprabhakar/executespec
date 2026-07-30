package com.navneet.executespec.contractsurface;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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

  private void write(String relative, String content) throws Exception {
    Path path = workspace.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
  }
}
