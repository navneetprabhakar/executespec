package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyContractEnricher.*;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.*;
import static com.navneet.executespec.agent.PlanSafetyExecutionTextSupport.*;
import static com.navneet.executespec.agent.PlanSafetyReservationTextSupport.*;
import static com.navneet.executespec.agent.PlanSafetyStructureSupport.*;
import static com.navneet.executespec.agent.PlanSafetyTextSupport.*;

import java.util.Locale;
import java.util.Objects;

final class PlanSafetyTaskPhase4 {

  private PlanSafetyTaskPhase4() {}

  static void apply(PlanSafetyPolicy policy, PlanSafetyTaskPatch patch) {
    if (patch.idempotencyTransportTask
        || (policy.exactIdempotentReplayRequired() && patch.idempotencyBehaviorTask)) {
      boolean idempotencySchemaOwner =
          TaskExpansionPlanContract.isIdempotencySchemaOwner(patch.task, policy.plan());
      patch.applied +=
          removeConstraints(
              patch.constraints,
              constraint -> {
                String normalized = constraint.toLowerCase(Locale.ROOT);
                return normalized.contains("order:")
                    && normalized.contains("idempotency_key")
                    && !containsAny(
                        normalized, "must not own", "must not store", "do not store", "forbidden");
              },
              patch.task.id(),
              "business-aggregate replay ownership",
              patch.summaries);
      String canonicalRecord =
          "IDEMPOTENCY PERSISTENCE CONTRACT (binding): define a durable IdempotencyRecord with"
              + " key, scope, request_body_hash, state (PROCESSING|COMPLETED), nullable"
              + " response_status, nullable response_body, timestamps, and"
              + " UniqueConstraint(key, scope). Order must not own the replay key or stored"
              + " response.";
      if (idempotencySchemaOwner && !patch.description.contains(canonicalRecord)) {
        patch.description = patch.description + "\n\n" + canonicalRecord;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " owns the durable request-fingerprint and exact-response record");
      }
      String modelPath = canonicalIdempotencyModelPath(policy.plan());
      if (idempotencySchemaOwner && !patch.touchesFiles.contains(modelPath)) {
        patch.touchesFiles.add(modelPath);
        patch.applied++;
        patch.summaries.add(patch.task.id() + " owns IdempotencyRecord persistence");
      }
      boolean ownsMigration =
          patch.touchesFiles.stream()
              .filter(Objects::nonNull)
              .map(path -> path.replace('\\', '/').toLowerCase(Locale.ROOT))
              .anyMatch(path -> path.contains("/alembic/versions/") || path.contains("/migration"));
      if (idempotencySchemaOwner
          && policy.migrationsRequired()
          && !ownsMigration
          && (!PlanSafetyMigrationSupport.requiresConsolidatedMigration(policy.plan())
              || PlanSafetyMigrationSupport.isConsolidatedMigrationOwner(
                  patch.task, policy.plan()))) {
        String migrationPath = idempotencyMigrationPath(policy.plan().tasks());
        if (!patch.touchesFiles.contains(migrationPath)) {
          patch.touchesFiles.add(migrationPath);
          patch.applied++;
          patch.summaries.add(patch.task.id() + " owns the IdempotencyRecord migration");
        }
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Use one exact IdempotencyRecord contract everywhere: key, scope,"
                  + " request_body_hash, PROCESSING/COMPLETED state, nullable response_status,"
                  + " nullable response_body, and database uniqueness on (key, scope). Do not"
                  + " store replay ownership on Order.",
              patch.task.id(),
              "durable exact idempotency replay",
              patch.summaries);
      String rewritten = rewriteIdempotencyTransaction(patch.description);
      if (!Objects.equals(patch.description, rewritten)) {
        patch.description = rewritten;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " description keeps idempotency completion in one transaction");
      }
      if (policy.sqliteConcurrencyRequired()) {
        String sqliteIdempotency =
            PlanSafetyReservationTextSupport.normalizeSqliteIdempotencyAlgorithm(patch.description);
        if (!Objects.equals(patch.description, sqliteIdempotency)) {
          patch.description = sqliteIdempotency;
          patch.applied++;
          patch.summaries.add(
              patch.task.id() + " idempotency uses a SQLite-visible writer serialization point");
        }
        patch.applied +=
            addConstraint(
                patch.constraints,
                "SQLite idempotency ownership must start BEGIN IMMEDIATE before the"
                    + " IdempotencyRecord lookup. A competing writer blocks, then re-reads the"
                    + " committed COMPLETED record; it must not poll for another transaction's"
                    + " uncommitted PROCESSING row. Claim, business mutation, and stored exact"
                    + " response complete in that one transaction.",
                patch.task.id(),
                "SQLite-executable idempotency serialization",
                patch.summaries);
      }
    }
    if (policy.reservationLedgerRequired()
        && (patch.createReservationTask || patch.cancellationBehaviorTask)) {
      String rewritten = rewriteReservationLedger(patch.description);
      if (!Objects.equals(patch.description, rewritten)) {
        patch.description = rewritten;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " description preserves on_hand during reserve/release");
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Reservation accounting leaves Inventory.on_hand unchanged: reserve increments"
                  + " reserved only, cancellation decrements reserved only, and available remains"
                  + " on_hand - reserved.",
              patch.task.id(),
              "inventory reservation ledger",
              patch.summaries);
    }
    if (patch.testTask && policy.requirements().contains("positive or negative stock adjustment")) {
      String rewritten =
          rewriteBlackBoxAcceptanceContradictions(patch.description, policy.requirements());
      if (!Objects.equals(patch.description, rewritten)) {
        patch.description = rewritten;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " covers stock-adjustment boundary cases");
      }
    }
    if (patch.realHttpTestTask) {
      String rewritten = rewriteRealHttpDatabase(patch.description);
      rewritten = rewriteBlackBoxAcceptanceContradictions(rewritten, policy.requirements());
      if (!Objects.equals(patch.description, rewritten)) {
        patch.description = rewritten;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " real-HTTP tests use a subprocess-visible database");
      }
    } else if (patch.testTask) {
      String rewritten =
          rewriteBlackBoxAcceptanceContradictions(patch.description, policy.requirements());
      if (!Objects.equals(patch.description, rewritten)) {
        patch.description = rewritten;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " test description preserves the public requirement contract");
      }
    }

    if (policy.invalidCancellationRequired() && patch.invalidCancellationContractTask) {
      String rewritten = rewriteRepeatedCancellation(patch.description);
      if (!Objects.equals(patch.description, rewritten)) {
        patch.description = rewritten;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " rejects repeated cancellation instead of replaying success");
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              "A second cancellation must return the stable invalid-state error, not a success"
                  + " replay; stock must remain restored exactly once.",
              patch.task.id(),
              "double-cancel state transition",
              patch.summaries);
    }

    patch.runtimeOrMigrationTask =
        isRuntimeOrMigrationTask(patch.taskEvidence)
            && (!patch.documentationTask
                || containsAny(patch.taskEvidence, "readme", "running the server"));
    if (policy.migrationsRequired() && patch.runtimeOrMigrationTask) {
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Run the project's configured migration mechanism from the documented default"
                  + " startup path; do not fall back to ORM schema auto-creation.",
              patch.task.id(),
              "default runtime migration boundary",
              patch.summaries);
    }
    if (policy.migrationsRequired() && patch.testTask && !patch.frontendOnlyTask) {
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Test fixtures must apply the configured migration mechanism and must not call ORM"
                  + " create_all or manually create the schema.",
              patch.task.id(),
              "migration-backed test fixture",
              patch.summaries);
    }
    if (policy.blackBoxHttpRequired()
        && patch.dedicatedAcceptanceTestTask
        && patch.acceptanceContractTask) {
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Functional tests must launch the documented production server, apply the real"
                  + " migrations to a fresh database, and exercise public behavior over real"
                  + " HTTP; in-process TestClient, dependency overrides, and unit tests are"
                  + " supplemental and cannot be the only acceptance proof.",
              patch.task.id(),
              "black-box HTTP acceptance boundary",
              patch.summaries);
      if (policy.requirements().contains("persistence across restart")) {
        String persistenceProof =
            PlanSafetyE2eSupport.usesSqlite(policy.plan())
                ? "PERSISTENCE PROOF (binding): create an adjustment, order, and reservation"
                    + " through public HTTP; stop and restart the documented backend against the"
                    + " same temporary database file; then read each resource through public HTTP"
                    + " and assert IDs, quantities, lifecycle states, and audit entries survived."
                    + " Do not inspect the SQLite file directly."
                : "PERSISTENCE PROOF (binding): create the required records through public HTTP;"
                    + " stop and restart the documented backend against the same isolated test"
                    + " data store; then read each resource through public HTTP and assert the"
                    + " required IDs, values, lifecycle states, and audit entries survived. Do"
                    + " not inspect the data store directly.";
        if (!patch.description.contains(persistenceProof)) {
          patch.description = persistenceProof + "\n\n" + patch.description;
          patch.applied++;
          patch.summaries.add(
              patch.task.id() + " description += complete restart persistence proof");
        }
        patch.applied +=
            addConstraint(
                patch.constraints,
                persistenceProof,
                patch.task.id(),
                "restart persistence acceptance proof",
                patch.summaries);
        if (containsAny(patch.taskEvidence, "playwright", "webserver")) {
          String restartStore =
              PlanSafetyE2eSupport.usesSqlite(policy.plan())
                  ? "same unique temporary SQLite file"
                  : "same isolated test data store";
          patch.applied +=
              addConstraint(
                  patch.constraints,
                  "A Playwright webServer process cannot be stopped and restarted from an"
                      + " ordinary browser spec. Implement restart persistence as a separate"
                      + " black-box process-control test/harness that launches the documented"
                      + " backend twice against the "
                      + restartStore
                      + ". Keep Playwright for browser journeys and responsive behavior.",
                  patch.task.id(),
                  "executable restart persistence harness",
                  patch.summaries);
        }
      }
    }
    if (patch.testTask
        && containsAny(patch.taskIdentityEvidence, "end-to-end", "e2e", "playwright", "browser")
        && containsAny(
            policy.requirements(),
            "real backend and frontend",
            "backend and frontend",
            "frontend and backend")) {
      boolean fastApiVite = PlanSafetyE2eSupport.usesFastApiViteStack(policy.plan());
      boolean alembic = PlanSafetyE2eSupport.usesAlembic(policy.plan());
      String normalizedHarness =
          PlanSafetyOrderSupport.normalizeOrderStatusField(
              fastApiVite && alembic
                  ? PlanSafetyE2eSupport.normalizeBrowserHarness(patch.description)
                  : patch.description);
      if (!Objects.equals(patch.description, normalizedHarness)) {
        patch.description = normalizedHarness;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " migrates the clean E2E database and keeps setup UI-driven");
      }
      String browserJourneyContract =
          "BROWSER JOURNEY CONTRACT (binding): drive every required customer journey through the"
              + " real rendered UI using Playwright page interactions. Direct API calls may"
              + " perform health/readiness checks only; do not seed business data, execute the"
              + " tested workflow, or replace UI assertions through an API client.";
      if (!patch.description.contains(browserJourneyContract)) {
        patch.description =
            patch.description.isBlank()
                ? browserJourneyContract
                : browserJourneyContract + "\n\n" + patch.description;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " description += real browser journey boundary");
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Browser end-to-end tests must drive every required customer journey through the"
                  + " real rendered UI using Playwright page interactions. Direct API calls may"
                  + " perform health/readiness checks only; they must not seed business data,"
                  + " execute the tested workflow, or replace UI assertions.",
              patch.task.id(),
              "real browser journey boundary",
              patch.summaries);
      String isolatedStateContract =
          fastApiVite && alembic
              ? "The Playwright backend webServer command must run `alembic upgrade head` against"
                  + " the isolated test DATABASE_URL before starting uvicorn."
              : "The Playwright harness must start the project's documented backend and frontend"
                  + " commands against fresh isolated test state before browser assertions.";
      patch.applied +=
          addConstraint(
              patch.constraints,
              isolatedStateContract,
              patch.task.id(),
              "fresh E2E test state",
              patch.summaries);
    }
    if ((patch.dedicatedAcceptanceTestTask || isTestOnlyTask(patch.task))
        && policy.requirements().contains("deterministic seed data")) {
      patch.applied +=
          removeConstraints(
              patch.constraints,
              constraint -> constraint.toLowerCase(Locale.ROOT).contains("post /api/seed"),
              patch.task.id(),
              "invented public database-reset endpoint",
              patch.summaries);
      boolean fastApiAlembic =
          PlanSafetyE2eSupport.usesFastApiViteStack(policy.plan())
              && PlanSafetyE2eSupport.usesAlembic(policy.plan());
      String isolatedSeedContract =
          fastApiAlembic
              ? "Tests must not invent or require a public database-reset endpoint. Each suite"
                  + " starts its own documented backend process against a unique temporary"
                  + " file-backed database, applies Alembic migrations, and invokes the"
                  + " documented startup seed path before assertions."
              : "Tests must not invent or require a public database-reset endpoint. Each suite"
                  + " starts the documented application commands against fresh isolated test"
                  + " state and invokes the project's documented initialization or seed path"
                  + " before assertions.";
      patch.applied +=
          addConstraint(
              patch.constraints,
              isolatedSeedContract,
              patch.task.id(),
              "isolated test database lifecycle",
              patch.summaries);
    }

    if (policy.migrationsRequired()) {
      String rewritten = rewriteOrmSchemaCreation(patch.description);
      if (!patch.description.equals(rewritten)) {
        patch.description = rewritten;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " description uses migrations instead of ORM create_all");
      }
    }
    patch.taskInstallsDuringBuildOrTest =
        containsAny(
                patch.taskEvidence,
                "build.sh",
                "test.sh",
                "run_tests.sh",
                "run-tests.sh",
                "make build",
                "make test")
            && containsAny(
                patch.taskEvidence, "pip install", "npm ci", "npm install", "python -m venv");
    if ((policy.pureBuildAndTestCommandsRequired() || patch.taskInstallsDuringBuildOrTest)
        && containsAny(
            patch.taskEvidence,
            "makefile",
            "make build",
            "make test",
            "build/test",
            "build.sh",
            "test.sh",
            "run_tests.sh",
            "run-tests.sh")) {
      String rewritten = rewriteBuildAndTestInstallation(patch.description);
      if (!patch.description.equals(rewritten)) {
        patch.description = rewritten;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " description separates install from build/test");
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Dependency installation must be a separate explicit setup command. Build and test"
                  + " commands must never create an environment or run pip, npm install, npm ci,"
                  + " or another package installer; they only compile, migrate/validate, and run"
                  + " tests using dependencies already present.",
              patch.task.id(),
              "pure repeatable build/test commands",
              patch.summaries);
    }
    patch.rootVerificationTask =
        containsAny(
                patch.taskIdentityEvidence, "root orchestration", "full-stack build", "build/test")
            || patch.touchesFiles.stream()
                .filter(Objects::nonNull)
                .anyMatch(
                    path ->
                        path.equalsIgnoreCase("run_tests.sh") || path.equalsIgnoreCase("build.sh"));
    if (policy.rootFullStackBuildRequired() && patch.rootVerificationTask) {
      patch.description = normalizeTechnicalSpacing(patch.description);
      if (!patch.touchesFiles.contains("build.sh")) {
        patch.touchesFiles.add("build.sh");
        patch.applied++;
        patch.summaries.add(patch.task.id() + " owns the root full-stack build script");
      }
      if (patch.touchesFiles.remove("test.sh")) {
        patch.applied++;
        patch.summaries.add(patch.task.id() + " removes the ambiguous root test.sh alias");
      }
      if (!patch.touchesFiles.contains("run_tests.sh")) {
        patch.touchesFiles.add("run_tests.sh");
        patch.applied++;
        patch.summaries.add(patch.task.id() + " owns the canonical root test script");
      }
      String rootBuildNormalized = rewriteRootBuildExecution(patch.description);
      if (!Objects.equals(patch.description, rootBuildNormalized)) {
        patch.description = rootBuildNormalized;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " keeps root build commands at repository root");
      }
      if (!safe(patch.description).contains("build.sh")) {
        patch.description =
            "Create executable root `build.sh`: run Python 3.12 compile/import checks"
                + " for backend/app, then run `npm --prefix frontend run build`. It must use"
                + " already-installed dependencies and must not run tests or package installers."
                + "\n\n"
                + safe(patch.description);
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " description defines the root full-stack build path");
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              "The root ./build.sh must compile/import-check the Python 3.12 backend and run the"
                  + " React production build via npm --prefix frontend run build, using only"
                  + " already-installed dependencies; ./run_tests.sh separately runs backend"
                  + " pytest, real-HTTP acceptance, frontend Vitest, and required browser E2E"
                  + " suites exactly once.",
              patch.task.id(),
              "full-stack root build and test orchestration",
              patch.summaries);
      if (containsAny(policy.requirements(), "black-box", "black box", "acceptance")) {
        String onceOnly =
            "RUN TESTS ONCE (binding): ./run_tests.sh must not run a black-box acceptance module"
                + " inside a broad backend pytest selection and then invoke it again separately;"
                + " either exclude it from the broad selection or run the complete backend suite"
                + " once. Invoke frontend tests only through declared package.json scripts.";
        if (!patch.description.contains(onceOnly)) {
          patch.description = patch.description + "\n\n" + onceOnly;
          patch.applied++;
          patch.summaries.add(patch.task.id() + " avoids duplicate acceptance execution");
        }
        patch.applied +=
            addConstraint(
                patch.constraints,
                onceOnly,
                patch.task.id(),
                "non-duplicated test orchestration",
                patch.summaries);
      }
    }

    patch.applied +=
        removeConstraints(
            patch.constraints,
            PlanSafetyStructureSupport::isRemovalMetaInstruction,
            patch.task.id(),
            "coherence removal instruction is not an executable constraint",
            patch.summaries);
    patch.applied +=
        removeConstraints(
            patch.constraints,
            constraint -> patch.frontendOnlyTask && isBackendImplementationConstraint(constraint),
            patch.task.id(),
            "backend-only contract from frontend task",
            patch.summaries);
  }
}
