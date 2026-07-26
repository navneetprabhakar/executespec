package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyContractEnricher.*;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.*;
import static com.navneet.executespec.agent.PlanSafetyStructureSupport.*;
import static com.navneet.executespec.agent.PlanSafetyTextSupport.*;

import java.util.Locale;
import java.util.Objects;

final class PlanSafetyTaskPhase2 {

  private PlanSafetyTaskPhase2() {}

  static void apply(PlanSafetyPolicy policy, PlanSafetyTaskPatch patch) {
    patch.taskIdentityEvidence = taskIdentityEvidence(patch.task);
    patch.frontendOnlyTask = isFrontendOnlyTask(patch.task);
    boolean ownsFrontendPackage =
        patch.frontendOnlyTask
            && patch.touchesFiles.stream()
                .map(path -> safe(path).replace('\\', '/').toLowerCase(Locale.ROOT))
                .anyMatch(path -> path.endsWith("/package.json") || path.equals("package.json"));
    boolean frontendTestsRequired =
        containsAny(policy.requirements(), "vitest", "frontend test", "browser e2e", "playwright");
    if (hasCanonicalAsyncDatabaseContract(policy.plan().tasks()) && !patch.frontendOnlyTask) {
      String asyncBoundDescription = bindAsyncDatabaseAlternatives(patch.description);
      if (!Objects.equals(patch.description, asyncBoundDescription)) {
        patch.description = asyncBoundDescription;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " bound database session dependency to the scaffold's async stack");
      }
    }
    if (ownsFrontendPackage && frontendTestsRequired) {
      String scriptContract =
          "Define executable package.json scripts for `build`, `test` (Vitest), and `test:e2e`"
              + " (Playwright when browser E2E is required), with matching devDependencies; root"
              + " orchestration must call these declared scripts rather than inventing commands.";
      if (!patch.description.contains(scriptContract)) {
        patch.description = patch.description + "\n\nPACKAGE SCRIPT CONTRACT: " + scriptContract;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " owns frontend test and E2E package scripts");
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              scriptContract,
              patch.task.id(),
              "frontend package script ownership",
              patch.summaries);
    }
    if (patch.frontendOnlyTask
        && policy.requirements().contains("never use floating point for money")) {
      String moneySafeDescription = rewriteFrontendMoneyParsing(patch.description);
      if (!Objects.equals(patch.description, moneySafeDescription)) {
        patch.description = moneySafeDescription;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " frontend money conversion avoids floating point");
      }
      if (containsAny(patch.taskEvidence, "price", "total", "minor unit")) {
        patch.applied +=
            addConstraint(
                patch.constraints,
                "Parse user-entered money as a decimal string into integer minor units by"
                    + " splitting and validating whole/fractional digits; never multiply or"
                    + " round a JavaScript floating-point number for money.",
                patch.task.id(),
                "integer-only frontend money conversion",
                patch.summaries);
      }
    }
    if ((policy.mixedDatabaseSessionModes() || policy.synchronousDatabaseRequired())
        && !patch.frontendOnlyTask
        && containsAny(
            patch.taskEvidence,
            "sqlalchemy",
            "database.py",
            "sessionmaker",
            "db: session",
            "get_db")) {
      patch.applied +=
          removeConstraints(
              patch.constraints,
              constraint ->
                  containsAny(
                      constraint.toLowerCase(Locale.ROOT),
                      "asyncengine",
                      "async engine",
                      "async_sessionmaker",
                      "async get_db",
                      "asyncsession",
                      "async session",
                      "engine must be async",
                      "sqlalchemy 2 (async)",
                      "aiosqlite driver"),
              patch.task.id(),
              "mixed SQLAlchemy session mode",
              patch.summaries);
      String synchronousDatabaseContract =
          "Use one synchronous SQLAlchemy 2 database contract throughout: Engine, sessionmaker,"
              + " Session, and a synchronous get_db dependency.";
      patch.applied +=
          addConstraint(
              patch.constraints,
              synchronousDatabaseContract,
              patch.task.id(),
              "single SQLAlchemy session mode",
              patch.summaries);
      String synchronousDescription = rewriteSynchronousDatabaseContract(patch.description);
      if (!Objects.equals(patch.description, synchronousDescription)) {
        patch.description = synchronousDescription;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " description uses one synchronous SQLAlchemy contract");
      }
    }
    patch.orderReservationTask =
        patch.orderSchemaProducer
            || patch.reservationSchemaProducer
            || containsAny(
                patch.taskIdentityEvidence, "order", "reservation", "idempotency", "cancel order");
    patch.applied +=
        removeConstraints(
            patch.constraints,
            constraint -> patch.frontendOnlyTask && isBackendImplementationConstraint(constraint),
            patch.task.id(),
            "backend-only contract from frontend task",
            patch.summaries);
    patch.applied +=
        removeConstraints(
            patch.constraints,
            constraint -> !patch.orderReservationTask && isOrderReservationConstraint(constraint),
            patch.task.id(),
            "order/reservation contract from unrelated task",
            patch.summaries);
    patch.failingAcceptanceInstructions =
        removeConstraints(
            patch.constraints,
            PlanSafetyStructureSupport::permitsKnownFailingAcceptance,
            patch.task.id(),
            "required acceptance cannot knowingly remain red",
            patch.summaries);
    patch.applied += patch.failingAcceptanceInstructions;
    if (patch.failingAcceptanceInstructions > 0) {
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Required tests are executable acceptance gates. If they expose a product defect,"
                  + " fix the owning production code and rerun them; do not complete with a"
                  + " required test knowingly failing.",
              patch.task.id(),
              "green acceptance gate",
              patch.summaries);
    }
    patch.documentationTask =
        containsAny(
            patch.taskEvidence,
            "openapi specification",
            "openapi.yaml",
            "swagger ui",
            "readme",
            "documentation");
    patch.apiOrServiceTask =
        !patch.documentationTask
            && containsAny(
                patch.taskEvidence,
                "post /reservations",
                "get /reservations",
                "cancel_reservation",
                "get_reservation",
                "reservationservice",
                "reservation_service",
                "reservation route",
                "authentication",
                "auth.py",
                "idempotency service",
                "idempotency middleware",
                "idempotency.py",
                "routes.py",
                "dependencies.py");
    patch.testTask =
        containsAny(
            patch.taskEvidence,
            "tests/",
            "pytest test",
            "testclient",
            "test fixture",
            "test_",
            "playwright",
            "e2e test",
            "acceptance test",
            "acceptance suite");
    patch.credentialTask =
        !patch.documentationTask
            && containsAny(
                patch.taskEvidence, "api_key", "api key", "bearer token", "credential", "token");
    patch.dedicatedAcceptanceTestTask =
        !patch.frontendOnlyTask && patch.testTask && isDedicatedTestTask(patch.task);
    patch.explicitRealHttpTestTask =
        !patch.frontendOnlyTask
            && patch.testTask
            && (containsAny(
                    patch.taskEvidence,
                    "real http client",
                    "server launched as a subprocess",
                    "launch uvicorn",
                    "live server",
                    "loopback http",
                    "against the fastapi app",
                    "black-box",
                    "black box")
                || (patch.taskEvidence.contains("testclient")
                    && containsAny(
                        patch.taskIdentityEvidence,
                        "api and tests",
                        "final verification",
                        "acceptance",
                        "integration",
                        "black-box",
                        "black box"))
                || (patch.dedicatedAcceptanceTestTask && patch.taskEvidence.contains("conftest")));
    patch.acceptanceContractTask =
        patch.explicitRealHttpTestTask
            || (patch.dedicatedAcceptanceTestTask
                && containsAny(
                    patch.taskEvidence,
                    "integration test",
                    "functional test",
                    "acceptance test",
                    "end-to-end",
                    "black-box",
                    "concurrency test",
                    "test_concurrency",
                    "over http"));
    boolean ownsReadme =
        patch.touchesFiles.stream()
            .map(path -> safe(path).replace('\\', '/').toLowerCase(Locale.ROOT))
            .anyMatch(path -> path.equals("readme.md") || path.endsWith("/readme.md"));
    boolean fallbackDocumentationOwner =
        !policy.documentationOwnerAssigned()[0]
            && patch.dedicatedAcceptanceTestTask
            && containsAny(patch.taskIdentityEvidence, "black-box", "black box", "acceptance");
    if (policy.setupDocumentationRequired() && (ownsReadme || fallbackDocumentationOwner)) {
      if (!ownsReadme) patch.touchesFiles.add("README.md");
      if (policy.singleDevelopmentCommandRequired()
          && patch.touchesFiles.stream().noneMatch(path -> path.equals("dev.sh"))) {
        patch.touchesFiles.add("dev.sh");
      }
      policy.documentationOwnerAssigned()[0] = true;
      String readmeContract =
          "Create the root README.md with exact dependency setup, Alembic migration, backend"
              + " start, frontend start, build, unit/integration, browser E2E, and black-box"
              + " acceptance commands. Commands must be copy-pasteable from the directory they"
              + " document and must name required environment variables."
              + (policy.singleDevelopmentCommandRequired()
                  ? " Provide one root `./dev.sh` command that applies migrations and starts both"
                      + " the backend and frontend development servers with bounded child-process"
                      + " cleanup; document the separate service commands only as diagnostics."
                  : "");
      if (!patch.description.contains(readmeContract)) {
        patch.description = patch.description + "\n\n" + readmeContract;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " owns exact setup/start documentation in README.md");
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              "README.md must contain exact, copy-pasteable setup, migration, backend/frontend"
                  + " start, build, and every test-suite command with correct working-directory"
                  + " assumptions."
                  + (policy.singleDevelopmentCommandRequired()
                      ? " Its primary development startup must be the single root `./dev.sh`"
                          + " command, not two commands described as one."
                      : ""),
              patch.task.id(),
              "required setup/start documentation",
              patch.summaries);
    }
    if (!patch.acceptanceContractTask) {
      patch.applied +=
          removeConstraints(
              patch.constraints,
              constraint ->
                  constraint.contains(
                      "Functional tests must launch the documented production server"),
              patch.task.id(),
              "black-box acceptance contract belongs only to its dedicated test task",
              patch.summaries);
    }
    patch.realHttpTestTask =
        !patch.frontendOnlyTask
            && patch.testTask
            && (patch.acceptanceContractTask
                || (policy.realHttpAcceptanceRequired()
                    && containsAny(
                        patch.taskEvidence,
                        "pytest api tests",
                        "test_orders",
                        "test_api",
                        "http integration")));
    patch.authenticationProofTask =
        patch.testTask
            && (patch.apiOrServiceTask || patch.credentialTask || patch.realHttpTestTask);
    patch.ownedResourceBehaviorTask =
        !patch.documentationTask
            && containsAny(
                patch.taskEvidence,
                "post /reservations",
                "get /reservations",
                "cancel_reservation",
                "get_reservation",
                "reservationservice",
                "reservation_service",
                "reservation route",
                "routes.py");
    patch.idempotencyBehaviorTask =
        !patch.documentationTask
            && !patch.frontendOnlyTask
            && TaskExpansionPlanContract.isImplementationTask(patch.task)
            && (containsAny(
                    patch.taskIdentityEvidence,
                    "idempotency service",
                    "idempotency middleware",
                    "/services/idempotency.py",
                    "/routers/orders.py",
                    "/routes/orders.py",
                    "order_service.py",
                    "idempotency_store",
                    "idempotency store")
                || (policy.exactIdempotentReplayRequired()
                    && containsAny(
                        safe(patch.task.description()).toLowerCase(Locale.ROOT),
                        "post /orders",
                        "post /api/orders",
                        "reserve endpoint",
                        "reservation endpoint")
                    && containsAny(
                        safe(patch.task.description()).toLowerCase(Locale.ROOT),
                        "idempotency-key",
                        "idempotency_key",
                        "idempotencyrecord")));
    patch.idempotencyContractTask =
        policy.exactIdempotentReplayRequired()
            && !patch.frontendOnlyTask
            && (containsAny(
                    patch.taskIdentityEvidence,
                    "idempotency_record",
                    "idempotency service",
                    "idempotency middleware",
                    "/services/idempotency.py",
                    "/routers/orders.py",
                    "order_service.py")
                || TaskExpansionPlanContract.isIdempotencySchemaOwner(patch.task, policy.plan())
                || patch.dedicatedAcceptanceTestTask);
    if (!patch.idempotencyBehaviorTask) {
      String withoutBehaviorContracts =
          PlanSafetyIdempotencySupport.removeBehaviorContractsFromFrontend(patch.description);
      if (!Objects.equals(patch.description, withoutBehaviorContracts)) {
        patch.description = withoutBehaviorContracts;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " removed idempotency transaction behavior from a non-owner task");
      }
      patch.applied +=
          removeConstraints(
              patch.constraints,
              PlanSafetyIdempotencySupport::isBehaviorOnlyConstraint,
              patch.task.id(),
              "frontend idempotency transaction ownership",
              patch.summaries);
    }
    patch.createReservationTask =
        !patch.documentationTask
            && !patch.frontendOnlyTask
            && (patch.dedicatedAcceptanceTestTask
                || containsCreateReservationEndpoint(patch.taskEvidence)
                || containsAny(
                    patch.taskEvidence,
                    "create_reservation",
                    "atomic reservation",
                    "reserve stock",
                    "decrements on_hand and increments reserved",
                    "stock decrement",
                    "decrement stock",
                    "reservation core logic",
                    "last unit"));
    patch.stockMutationTask =
        !patch.documentationTask
            && !patch.frontendOnlyTask
            && (patch.dedicatedAcceptanceTestTask
                || containsAny(
                    patch.taskEvidence,
                    "create_reservation",
                    "stock decrement",
                    "decrement stock",
                    "last unit")
                || containsAny(
                    patch.taskIdentityEvidence, "reservation service", "reservation_service"));
    patch.cancellationBehaviorTask =
        !patch.documentationTask
            && !patch.frontendOnlyTask
            && (patch.dedicatedAcceptanceTestTask
                || containsAny(patch.taskEvidence, "cancel_reservation")
                || (containsAny(patch.taskEvidence, "cancel", "cancellation")
                    && containsAny(patch.taskEvidence, "order", "reservation"))
                || (containsAny(patch.taskEvidence, "cancel", "cancellation")
                    && containsAny(patch.taskEvidence, "order", "reservation"))
                || (containsAny(patch.taskEvidence, "cancel", "cancellation")
                    && containsAny(
                        patch.taskEvidence,
                        "double cancellation",
                        "second cancel",
                        "already-cancelled",
                        "already cancelled",
                        "invalid state",
                        "exactly once"))
                || containsAny(
                    patch.taskIdentityEvidence, "reservation service", "reservation_service"));
    patch.invalidCancellationContractTask =
        patch.dedicatedAcceptanceTestTask
            || containsAny(patch.taskIdentityEvidence, "reservation service", "reservation_service")
            || containsAny(
                patch.taskEvidence,
                "double cancellation",
                "second cancel",
                "already-cancelled",
                "already cancelled",
                "invalid state",
                "exactly once");
    patch.errorContractTask =
        containsAny(
            patch.taskEvidence,
            "error response",
            "error handling",
            "exception",
            "sanitized error",
            "validation-errors",
            "sanitized-errors",
            "black-box acceptance",
            "http integration test");

    if (policy.canonicalSanitizedErrorsRequired() && patch.errorContractTask) {
      String canonicalErrorContract =
          "Every non-2xx API response must use exactly"
              + " {\"error\":{\"code\":\"STABLE_CODE\",\"message\":\"sanitized"
              + " message\",\"details\":{}}}; this binding contract supersedes any earlier"
              + " flat {error, detail}, FastAPI detail, or alternate test expectation.";
      patch.applied +=
          addConstraint(
              patch.constraints,
              canonicalErrorContract,
              patch.task.id(),
              "canonical sanitized HTTP error envelope",
              patch.summaries);
      if (patch.description != null
          && !patch.description.contains("binding contract supersedes any earlier flat")) {
        patch.description =
            patch.description + "\n\nCONTRACT CORRECTION: " + canonicalErrorContract;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " description += canonical sanitized error correction");
      }
    }
    if (patch.idempotencyContractTask) {
      String canonicalIdempotencyContract =
          "Use one exact IdempotencyRecord contract everywhere: key, scope, request_body_hash,"
              + " state, nullable response_status, and nullable response_body; enforce a database"
              + " unique constraint on (key, scope), use PROCESSING/COMPLETED states, and keep"
              + " ownership, business mutation, and completed replay in one transaction."
              + " On IdempotencyRecord, idempotency_key and scope_hash are forbidden aliases;"
              + " the business Order aggregate must not own replay/idempotency fields. This does"
              + " not prohibit the HTTP Idempotency-Key header.";
      String normalizedIdempotencyDescription = normalizeIdempotencyRecordNames(patch.description);
      if (!Objects.equals(patch.description, normalizedIdempotencyDescription)) {
        patch.description = normalizedIdempotencyDescription;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " description aligned to canonical IdempotencyRecord names");
      }
      for (int index = 0; index < patch.constraints.size(); index++) {
        String constraint = patch.constraints.get(index);
        String normalizedConstraint = normalizeIdempotencyRecordNames(constraint);
        if (!constraint.equals(normalizedConstraint)) {
          patch.constraints.set(index, normalizedConstraint);
          patch.applied++;
          patch.summaries.add(
              patch.task.id() + " hard constraint aligned to canonical IdempotencyRecord names");
        }
      }
      patch.applied +=
          removeConstraints(
              patch.constraints,
              constraint ->
                  constraint.contains("Use one exact IdempotencyRecord contract everywhere:")
                      && !constraint.equals(canonicalIdempotencyContract),
              patch.task.id(),
              "corrupted canonical idempotency lifecycle",
              patch.summaries);
      patch.applied +=
          addConstraint(
              patch.constraints,
              canonicalIdempotencyContract,
              patch.task.id(),
              "canonical idempotency producer/consumer contract",
              patch.summaries);
      patch.applied +=
          removeConstraints(
              patch.constraints,
              constraint ->
                  containsAny(
                      constraint.toLowerCase(Locale.ROOT),
                      "does not prohibit order.idempotency_key",
                      "persisting the accepted key on order"),
              patch.task.id(),
              "business aggregate replay ownership",
              patch.summaries);
      patch.touchesFiles.removeIf(
          path ->
              path != null
                  && path.replace('\\', '/')
                      .toLowerCase(Locale.ROOT)
                      .endsWith("/middleware/idempotency.py"));
      if (patch.description != null
          && (patch.description.contains("idempotency_key")
              || patch.description.contains("scope_hash")
              || patch.description.contains("IdempotencyRecord"))
          && !patch.description.contains("idempotency_key and scope_hash are forbidden")) {
        patch.description =
            patch.description
                + "\n\nCONTRACT CORRECTION (binding and superseding earlier aliases): "
                + canonicalIdempotencyContract;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " description += canonical idempotency alias correction");
      }
      String canonicalDescription = rewriteLegacyIdempotencyRecordSchema(patch.description);
      if (!Objects.equals(patch.description, canonicalDescription)) {
        patch.description = canonicalDescription;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " description uses the canonical IdempotencyRecord schema");
      }
    }
    patch.reservationScopedIdempotencyHeader =
        policy.requirements().contains("mutating reservation request")
            || policy.requirements().contains("reservation requests")
            || policy.requirements().contains("reservation mutations");
    patch.orderCreationIdempotencyHeader =
        policy.requirements().matches("(?s).*idempotency.{0,120}order creation.*")
            || policy.requirements().matches("(?s).*order creation.{0,120}idempotency.*")
            || policy.requirements().matches("(?s).*idempotency.{0,120}order endpoint.*")
            || policy.requirements().matches("(?s).*order endpoint.{0,120}idempotency.*");
    patch.idempotencyTransportTask =
        policy.idempotencyHeaderRequired()
            && ((patch.reservationScopedIdempotencyHeader
                    && containsAny(
                        patch.taskEvidence,
                        "post /reservations",
                        "post /api/reservations",
                        "reservation service",
                        "reservation_service",
                        "release",
                        "cancel"))
                || (patch.orderCreationIdempotencyHeader
                    && containsAny(
                        patch.taskEvidence,
                        "ordercreate",
                        "post /orders",
                        "create_order",
                        "createorder")));
    if (patch.idempotencyTransportTask) {
      String rewritten =
          patch.orderCreationIdempotencyHeader
              ? rewriteIdempotencyHeaderTransport(patch.description)
              : patch.description;
      if (!Objects.equals(patch.description, rewritten)) {
        patch.description = rewritten;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " description uses header-only idempotency transport");
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              patch.reservationScopedIdempotencyHeader
                  ? "Idempotency-Key is a required HTTP header for mutating reservation"
                      + " endpoints; their JSON request bodies must not declare idempotency_key."
                      + " Persist the accepted value only through the canonical IdempotencyRecord"
                      + " key field."
                  : "Idempotency-Key is a required HTTP header for order creation; OrderCreate"
                      + " and the JSON request body must not declare idempotency_key. Persisting"
                      + " the accepted key is allowed only on the canonical IdempotencyRecord;"
                      + " the Order business aggregate must not own replay fields.",
              patch.task.id(),
              "header-only idempotency transport",
              patch.summaries);
    }
  }
}
