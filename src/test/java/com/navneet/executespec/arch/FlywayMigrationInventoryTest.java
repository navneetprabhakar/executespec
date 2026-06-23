package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class FlywayMigrationInventoryTest {

  private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
  private static final Pattern MIGRATION_FILE =
      Pattern.compile("^V(?<version>\\d+(?:_\\d+)?)__(?<description>[a-z0-9_]+)\\.sql$");

  @Test
  void migrationInventoryHasNoSilentVersionDrift() throws IOException {
    List<MigrationFile> migrations = readMigrations();

    assertThat(migrations).hasSize(248);
    assertThat(migrations.getLast().version()).isEqualTo("248");

    assertThat(migrations.stream().map(MigrationFile::version)).doesNotHaveDuplicates();
    assertThat(migrations.stream().map(MigrationFile::fileName))
        .allMatch(name -> MIGRATION_FILE.matcher(name).matches());

    assertThat(migrations.stream().filter(MigrationFile::fractional).map(MigrationFile::version))
        .containsExactly("60_1");
    assertThat(majorGaps(migrations)).containsExactly("V118->V120");
  }

  @Test
  void classpathMigrationInventoryMatchesSourceInventory() throws IOException, URISyntaxException {
    List<String> sourceFiles = readMigrations().stream().map(MigrationFile::fileName).toList();
    List<String> classpathFiles =
        readMigrations(classpathMigrationDir()).stream().map(MigrationFile::fileName).toList();

    assertThat(classpathFiles).containsExactlyElementsOf(sourceFiles);
  }

  private static List<MigrationFile> readMigrations() throws IOException {
    return readMigrations(MIGRATION_DIR);
  }

  private static List<MigrationFile> readMigrations(Path dir) throws IOException {
    try (var stream = Files.list(dir)) {
      return stream
          .filter(path -> path.getFileName().toString().endsWith(".sql"))
          .map(FlywayMigrationInventoryTest::toMigrationFile)
          .sorted(
              Comparator.comparingInt(MigrationFile::major).thenComparing(MigrationFile::version))
          .toList();
    }
  }

  private static Path classpathMigrationDir() throws URISyntaxException {
    var resource = Thread.currentThread().getContextClassLoader().getResource("db/migration");
    assertThat(resource).as("classpath db/migration resource").isNotNull();
    assertThat(resource.getProtocol()).as("classpath db/migration protocol").isEqualTo("file");
    return Path.of(resource.toURI());
  }

  private static MigrationFile toMigrationFile(Path path) {
    String fileName = path.getFileName().toString();
    var matcher = MIGRATION_FILE.matcher(fileName);
    assertThat(matcher.matches()).as("migration filename %s", fileName).isTrue();
    return new MigrationFile(fileName, matcher.group("version"));
  }

  private static List<String> majorGaps(List<MigrationFile> migrations) {
    List<Integer> majors =
        migrations.stream().map(MigrationFile::major).distinct().sorted().toList();
    return java.util.stream.IntStream.range(1, majors.size())
        .filter(i -> majors.get(i) - majors.get(i - 1) > 1)
        .mapToObj(i -> "V" + majors.get(i - 1) + "->V" + majors.get(i))
        .toList();
  }

  private record MigrationFile(String fileName, String version) {
    int major() {
      int separator = version.indexOf('_');
      return Integer.parseInt(separator >= 0 ? version.substring(0, separator) : version);
    }

    boolean fractional() {
      return version.contains("_");
    }
  }
}
