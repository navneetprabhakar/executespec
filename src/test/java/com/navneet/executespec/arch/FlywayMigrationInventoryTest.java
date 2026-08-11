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
    // V340 (graduate the lean worker toolset), V341 (backfill the durable run-usage floor), V342
    // (durable sandbox cleanup retry), V343 (sandbox release-readiness gates), V344
    // (validation test-evidence scope), V345 (immutable RequirementIR artifact), V346 (durable
    // plan-gate admission binding + continuation outbox), and V347 (atomic gate-open identity
    // contract) for 333 — contiguous above V336. V348 then adds atomic plan-gate decision
    // integrity, and V349 separates the immutable payer org from the host-project tenant org for
    // external collaborators. V350 carries that split into pipeline and durable LLM context
    // identity, bringing the exact inventory to 336. V351 adds the dormant per-operation LLM
    // attempt-fencing foundation, bringing the exact inventory to 337. V352 permits exact,
    // structurally valid BLOCKED RequirementIR evidence to open a visible pending gate while
    // preserving fail-closed approval, bringing the exact inventory to 338. V353 adds durable,
    // idempotent project-architecture refresh jobs, bringing the exact inventory to 339. V354
    // makes physical LLM-attempt admission atomic with the exact logical-operation lease, and V355
    // adds the ordered partial index used for bounded stale agent-turn recovery, bringing the exact
    // inventory to 341. V356 adds agent-session archive/transcript integrity, V357 persists task
    // route explanations, V358 adds durable generated-document jobs, and V359 adds first-writer-
    // wins CI-failure delivery claims, bringing the exact inventory to 345. V360 persists
    // brownfield HTTP-contract evidence, and V361 binds validation evidence to an exact workspace
    // generation, bringing the exact inventory to 347. V362 persists deterministic workflow and
    // command execution evidence; V363 confirmation-fences uncertain generated-document retries,
    // V364 atomically fences continuation approvals, V365 adds bounded stale-receipt recovery;
    // V366 persists exact score-zero causality; V367 binds validation reports to runtime-surface
    // applicability; V368 force-disables legacy Apply-to-Run until transactional correction is
    // proven; V369 persists the crash-recoverable correction transaction, bringing the exact
    // inventory to 355. V370 converts V369's CHAR(64) digest columns to VARCHAR(64) because
    // Hibernate schema-validation rejected the bpchar/entity mismatch and crash-looped the dev
    // deployment, bringing the exact inventory to 356. V371 persists skipped-test counts, bringing
    // the exact inventory to 357. V372 binds cost history to the exact effective route and task
    // complexity shape, bringing the exact inventory to 358. V373 binds existing-PR runs to their
    // immutable checkout branch, repository, pull request, and admitted head SHA, bringing the
    // exact inventory to 359. V374 introduces the durable workspace-generation transaction,
    // immutable verification receipts, and the accepted-generation run frontier, bringing the
    // exact inventory to 360. V375 preserves original BUILD rejection evidence while recording
    // recovery failures separately, bringing the exact inventory to 361. V376 seeds the
    // default-off BUILD shared-code-change-loop rollout control, bringing the exact inventory to
    // 362. V377 adds immutable BUILD attempt contract, route, token, call, and cost evidence,
    // bringing the exact inventory to 363. V378 freezes the actually observed pre-provider BUILD
    // failure evidence, bringing the exact inventory to 364. V379 adds the nullable, staged
    // workspace-origin discriminator for new BUILD writes without rewriting historical rows,
    // bringing the exact inventory to 365. V380 adds the replayable task-transition outbox that
    // prevents a restored workspace from being resolved while its task row remains stale. V381
    // database-fences rollback evidence and resolution against the exact current workspace owner.
    // V382 preserves resolved-attempt immutability while permitting only the V380 task-transition
    // receipt state machine, bringing the exact inventory to 368.
    assertThat(migrations).hasSize(368);
    assertThat(migrations.getLast().version()).isEqualTo("382");

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
