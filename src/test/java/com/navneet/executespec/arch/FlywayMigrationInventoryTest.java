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

    // V119 is an applied historical migration and must remain on the classpath.
    // V60_1 is fractional. V1..V309 is 310 files; the audit-remediation fix wave added seven more
    // (V310 billing, V315 execution-gate, V316 provider-call reservation, V317 test-execution
    // provenance, V320 integration ownership, V323 run publication policy, V327 solution decision
    // authority) for 317, and the orchestrator-remainder lane added V329 (plan-approval contract
    // gate report, RR-819) for 318. The spend-admission lane then added V331 (managed-credit run
    // admission reservation, RR-1664) for 319. The GDPR-erasure lane then added V334
    // (pending-erasure obligation, RR-090) for 320. The notification/error-honesty P1 batch then
    // renumbered its reserved V332 to V335 (billing-reminder delivery dedupe, RR-518) because V334
    // was
    // already the applied head — an out-of-order V332 below V334 fails Flyway validation on any DB
    // that took V334 first. The billing-webhook temporal-correctness lane then added V336
    // (billing-activation idempotency + provider-event high-water mark, RR-054/RR-055/RR-056) for
    // 322. The migration-safe additive-column P1 lane then added V337 (LLM-attempt role provenance,
    // RR-430), V338 (agent_task plan_index, RR-1446), V339 (code_review_scan coverage, RR-1696),
    // and V340 (graduate the lean worker toolset) for 326 — contiguous above the V336 head.
    assertThat(migrations).hasSize(326);
    assertThat(migrations.getLast().version()).isEqualTo("340");

    assertThat(migrations.stream().map(MigrationFile::version)).doesNotHaveDuplicates();
    assertThat(migrations.stream().map(MigrationFile::fileName))
        .allMatch(name -> MIGRATION_FILE.matcher(name).matches());

    assertThat(migrations.stream().filter(MigrationFile::fractional).map(MigrationFile::version))
        .containsExactly("60_1");
    // Deliberate, permanent gaps. The audit-remediation fix wave ran parallel lanes, each
    // pre-assigned one migration number so two lanes could not claim the same one. Several lanes
    // turned out not to need theirs (V311-V314, V318-V319, V321-V322, V324-V326, V328, V330) —
    // several
    // found
    // the fix needed no schema change at all, which is a better outcome than a migration written
    // to justify a reservation. The GDPR-erasure lane was reserved V334 and lands there; the
    // notification/error-honesty P1 batch was reserved V332 and lands there (RR-518
    // billing-reminder
    // dedupe). V333 was left unused for the same reason.
    //
    // The numbers are NOT renumbered to close the gaps: these files may already be applied in a
    // local or dev database, and renaming an applied migration orphans it in
    // flyway_schema_history. Flyway does not require contiguous versions.
    //
    // This stays an EXACT assertion so a genuinely missing file still fails. Adding a new entry
    // here must mean "a number was reserved and deliberately not used", never "a migration went
    // missing".
    assertThat(majorGaps(migrations))
        .containsExactly(
            "V310->V315",
            "V317->V320",
            "V320->V323",
            "V323->V327",
            "V327->V329",
            "V329->V331",
            "V331->V334");
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
