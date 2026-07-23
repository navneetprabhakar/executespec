package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class FastContextProductionContainmentTest {

  @Test
  void externalExperimentIsAbsentFromRuntimeAndCustomerWebSources() throws Exception {
    assertThat(Path.of("src/main/java/com/navneet/executespec/tool/FastContextExperiment.java"))
        .doesNotExist();
    assertTreeContainsNoBrand(Path.of("src/main"));
    assertTreeContainsNoBrand(Path.of("frontend/src"));
    assertTreeContainsNoBrand(Path.of("website"));
  }

  private static void assertTreeContainsNoBrand(Path root) throws Exception {
    try (Stream<Path> files = Files.walk(root)) {
      files
          .filter(Files::isRegularFile)
          .filter(FastContextProductionContainmentTest::isTextSource)
          .forEach(
              file -> {
                try {
                  assertThat(Files.readString(file).toLowerCase(Locale.ROOT))
                      .as("external experiment must not be exposed by %s", file)
                      .doesNotContain("fastcontext");
                } catch (Exception e) {
                  throw new IllegalStateException("Unable to inspect " + file, e);
                }
              });
    }
  }

  private static boolean isTextSource(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".java")
        || name.endsWith(".yml")
        || name.endsWith(".yaml")
        || name.endsWith(".properties")
        || name.endsWith(".json")
        || name.endsWith(".xml")
        || name.endsWith(".ts")
        || name.endsWith(".tsx")
        || name.endsWith(".html")
        || name.endsWith(".txt");
  }
}
