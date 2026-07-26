package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyContractEnricher.*;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.*;
import static com.navneet.executespec.agent.PlanSafetyReservationTextSupport.*;
import static com.navneet.executespec.agent.PlanSafetyStructureSupport.*;
import static com.navneet.executespec.agent.PlanSafetyTextSupport.*;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class PlanSafetyTaskPhase3 {

  private PlanSafetyTaskPhase3() {}

  static void apply(PlanSafetyPolicy policy, PlanSafetyTaskPatch patch) {
    if (!patch.reservationSchemaProducer) {
      String withoutForeignSchema = removeForeignReservationSchemaCorrection(patch.description);
      if (!Objects.equals(patch.description, withoutForeignSchema)) {
        patch.description = withoutForeignSchema;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " removed reservation-schema ownership from a foreign task");
      }
    }
    if (policy.orderStatusConsumed()
        && patch.orderSchemaProducer
        && !orderSchemaDefinesStatus(
            patch.taskEvidence
                + "\n"
                + String.join("\n", patch.constraints).toLowerCase(Locale.ROOT))) {
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Define and migrate a persisted Order.status field with the exact lifecycle values"
                  + " consumed by reservation and cancellation services; all producer and"
                  + " consumer tasks must use the same field and values.",
              patch.task.id(),
              "producer/consumer Order.status contract",
              patch.summaries);
    }
    if (policy.orderStatusContract() != null && patch.orderSchemaProducer) {
      String normalizedProducerDescription =
          normalizeOrderStatusValues(
              PlanSafetyOrderSupport.normalizeOrderStatusField(patch.description),
              policy.orderStatusContract());
      if (!Objects.equals(patch.description, normalizedProducerDescription)) {
        patch.description = normalizedProducerDescription;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " producer description aligned to Order.status lifecycle");
      }
      patch.applied +=
          removeConstraints(
              patch.constraints,
              constraint ->
                  constraint.toLowerCase(Locale.ROOT).contains("order.status lifecycle")
                      && !constraint.equals(policy.orderStatusContract().constraint()),
              patch.task.id(),
              "conflicting Order.status lifecycle",
              patch.summaries);
      patch.applied +=
          addConstraint(
              patch.constraints,
              policy.orderStatusContract().constraint(),
              patch.task.id(),
              "canonical Order.status lifecycle",
              patch.summaries);
    }
    if (policy.reservationAggregateRequired() && patch.reservationSchemaProducer) {
      String aggregateNormalized = rewriteReservationAggregateSchema(patch.description);
      if (!Objects.equals(patch.description, aggregateNormalized)) {
        patch.description = aggregateNormalized;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " replaces per-line Reservation with parent aggregate schema");
      }
      String aggregateSchemaCorrection =
          "CONTRACT CORRECTION (binding schema): Order persists status (string: PENDING |"
              + " RESERVED, default PENDING) and owns OrderLine rows; Reservation persists"
              + " order_id, status (default ACTIVE), and owns ReservationLine rows;"
              + " Each ReservationLine"
              + " persists reservation_id, order_line_id, inventory_id, sku, and reserved"
              + " quantity;"
              + " the Alembic migration must create all three aggregates and their foreign"
              + " keys/check constraints.";
      String positionedAggregateSchema =
          cleanDescriptionWhitespace(patch.description.replace(aggregateSchemaCorrection, ""));
      positionedAggregateSchema =
          positionedAggregateSchema.isEmpty()
              ? aggregateSchemaCorrection
              : positionedAggregateSchema + "\n\n" + aggregateSchemaCorrection;
      if (!patch.description.equals(positionedAggregateSchema)) {
        patch.description = positionedAggregateSchema;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " positions complete aggregate schema correction once");
      }
      String reservationIdempotencyNormalized =
          patch
              .description
              .replaceAll(
                  "(?i)Reservation\\.idempotency_key[^.\\n]*\\.",
                  "Idempotent replay ownership belongs only to the canonical IdempotencyRecord;")
              .replaceAll(
                  "(?i)Reservation status defaults to ACTIVE; idempotency_key indexing",
                  "Reservation status defaults to ACTIVE; IdempotencyRecord replay persistence");
      if (!Objects.equals(patch.description, reservationIdempotencyNormalized)) {
        patch.description = reservationIdempotencyNormalized;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " removes Reservation.idempotency_key alias");
      }
      patch.applied +=
          removeConstraints(
              patch.constraints,
              constraint -> {
                String normalized = constraint.toLowerCase(Locale.ROOT);
                return (normalized.contains("reservation model has fields")
                        && (normalized.contains("single product_id/quantity")
                            || normalized.contains("not a separate reservationline")))
                    || normalized.contains("single persisted record per order line")
                    || normalized.contains("one reservation per line")
                    || normalized.contains("there is no reservationline")
                    || normalized.contains("not a separate reservationline")
                    || (normalized.contains("order_line_id is unique")
                        && normalized.contains("reservation"));
              },
              patch.task.id(),
              "conflicting per-member Reservation schema",
              patch.summaries);
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Reservation is one persisted parent aggregate with its own lifecycle and a"
                  + " one-to-many collection of ReservationLine child rows; product/SKU and"
                  + " quantity belong to the child rows, never duplicated on the parent.",
              patch.task.id(),
              "canonical Reservation aggregate ownership",
              patch.summaries);
      patch.applied +=
          removeConstraints(
              patch.constraints,
              constraint ->
                  constraint.toLowerCase(Locale.ROOT).contains("reservation.idempotency_key"),
              patch.task.id(),
              "Reservation-owned idempotency alias",
              patch.summaries);
    }
    patch.reservationLifecycleTask =
        policy.reservationAggregateRequired()
            && (patch.reservationSchemaProducer
                || containsAny(
                    patch.taskIdentityEvidence,
                    "reservation.py",
                    "reservation service",
                    "reservation_service",
                    "reservationout",
                    "reservationresponse",
                    "reservation api",
                    "/reservations",
                    "reservation.status",
                    "reservationline",
                    "release reservation",
                    "cancel reservation"));
    if (patch.reservationLifecycleTask) {
      String aggregateConsumerDescription = rewriteReservationAggregateConsumers(patch.description);
      if (!Objects.equals(patch.description, aggregateConsumerDescription)) {
        patch.description = aggregateConsumerDescription;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " aligned reservation service/DTO shape to parent aggregate");
      }
      for (int index = 0; index < patch.constraints.size(); index++) {
        String constraint = patch.constraints.get(index);
        String normalizedConstraint = rewriteReservationAggregateConsumers(constraint);
        if (!constraint.equals(normalizedConstraint)) {
          patch.constraints.set(index, normalizedConstraint);
          patch.applied++;
          patch.summaries.add(
              patch.task.id() + " aligned reservation constraint to parent aggregate");
        }
      }
      String normalizedReservation = normalizeReservationStatusValues(patch.description);
      if (!Objects.equals(patch.description, normalizedReservation)) {
        patch.description = normalizedReservation;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " description aligned to Reservation lifecycle");
      }
      if (patch.frontendOnlyTask) {
        String normalizedFrontend = normalizeFrontendReservationContract(patch.description);
        if (!Objects.equals(patch.description, normalizedFrontend)) {
          patch.description = normalizedFrontend;
          patch.applied++;
          patch.summaries.add(
              patch.task.id() + " frontend reservation calls match the backend aggregate API");
        }
      }
      patch.applied +=
          removeConstraints(
              patch.constraints,
              constraint -> {
                String normalized = constraint.toLowerCase(Locale.ROOT);
                return normalized.matches(
                        "(?s).*reservation\\.status\\s+(?:lifecycle|allowed"
                            + " values|values|enum|default).*")
                    && !constraint.equals(RESERVATION_LIFECYCLE_CONSTRAINT);
              },
              patch.task.id(),
              "conflicting Reservation lifecycle",
              patch.summaries);
      patch.applied +=
          addConstraint(
              patch.constraints,
              RESERVATION_LIFECYCLE_CONSTRAINT,
              patch.task.id(),
              "canonical Reservation lifecycle",
              patch.summaries);
    }
    if (!policy.canonicalApiEndpoints().isEmpty()
        && (patch.taskEvidence.contains("/api/")
            || isFrontendOnlyTask(patch.task)
            || isTestOnlyTask(patch.task))) {
      List<String> ownedEndpoints =
          canonicalEndpointsUsedBy(patch.description, policy.canonicalApiEndpoints());
      if (!ownedEndpoints.isEmpty()) {
        patch.applied +=
            addConstraint(
                patch.constraints,
                "Use these exact public API paths for the operations in this task: "
                    + String.join(", ", ownedEndpoints)
                    + ". Producers, frontend clients, and black-box tests must not invent"
                    + " alternate endpoint shapes.",
                patch.task.id(),
                "canonical public API paths",
                patch.summaries);
      }
    }

    if (policy.authenticatedPrincipalRequired()) {
      String authHonestDescription =
          patch.description.replaceAll(
              "(?i)\\bno authentication (?:layer|support|required)\\b",
              "authenticated-principal abstraction with verified runtime credentials");
      if (!Objects.equals(patch.description, authHonestDescription)) {
        patch.description = authHonestDescription;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " removes the contradictory unauthenticated-runtime claim");
      }
    }
    if (policy.authenticatedPrincipalRequired() && patch.apiOrServiceTask) {
      String ownershipClause =
          policy.resourceOwnershipRequired()
              ? " and enforce read/update/delete access only for resources owned by that principal"
              : "";
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Derive principal identity from a verified credential; never trust caller-selected"
                  + " identity input"
                  + ownershipClause
                  + ".",
              patch.task.id(),
              policy.resourceOwnershipRequired()
                  ? "verified principal and owned-resource boundary"
                  : "verified principal boundary",
              patch.summaries);
    }
    if (policy.authenticatedPrincipalRequired() && patch.authenticationProofTask) {
      if (patch.credentialTask || patch.dedicatedAcceptanceTestTask) {
        patch.applied +=
            addConstraint(
                patch.constraints,
                "Test that forged credentials and caller-selected identity input are rejected.",
                patch.task.id(),
                "adversarial authentication proof",
                patch.summaries);
      }
      if (policy.resourceOwnershipRequired()
          && (patch.ownedResourceBehaviorTask || patch.dedicatedAcceptanceTestTask)) {
        patch.applied +=
            addConstraint(
                patch.constraints,
                "Test that cross-principal owned-resource access is rejected.",
                patch.task.id(),
                "adversarial ownership proof",
                patch.summaries);
      }
      if (policy.principalScopedIdempotencyRequired()
          && (patch.idempotencyBehaviorTask || patch.dedicatedAcceptanceTestTask)) {
        patch.applied +=
            addConstraint(
                patch.constraints,
                "Test that two authenticated principals can reuse the same Idempotency-Key"
                    + " without collision or response leakage.",
                patch.task.id(),
                "principal-scoped idempotency proof",
                patch.summaries);
      }
    }
    if (policy.authenticatedPrincipalRequired() && patch.credentialTask) {
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Never persist or log raw API keys or bearer tokens. If the service owns credential"
                  + " persistence, store a one-way verifier; an explicitly requested external"
                  + " token map may be compared only in memory and must fail closed.",
              patch.task.id(),
              "credential storage boundary",
              patch.summaries);
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Production/runtime code must not seed, ship, log, document, or accept predictable"
                  + " default credentials or bearer tokens; credential fixtures belong only in"
                  + " isolated test code.",
              patch.task.id(),
              "no runtime credential fixtures",
              patch.summaries);
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Any persisted credential-verifier record must reference a stable"
                  + " principal/customer ID; never use a credential hash as principal identity.",
              patch.task.id(),
              "credential verifier identity boundary",
              patch.summaries);
    }

    if (policy.reservationArithmetic() && patch.createReservationTask) {
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Reject reservation quantity <= 0 before stock arithmetic; test zero and negative"
                  + " quantities through the public API.",
              patch.task.id(),
              "positive reservation domain",
              patch.summaries);
    }
    if (patch.createReservationTask) {
      String raceNormalized = rewriteSameOrderReservationRace(patch.description);
      if (!Objects.equals(patch.description, raceNormalized)) {
        patch.description = raceNormalized;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " aligned same-key replay and different-key race outcomes");
      }
      patch.applied +=
          removeConstraints(
              patch.constraints,
              constraint ->
                  constraint.toLowerCase(Locale.ROOT).startsWith("distinguish races explicitly:"),
              patch.task.id(),
              "stale same-order race outcome",
              patch.summaries);
      if (policy.requirements().contains("concurrent") && policy.exactIdempotentReplayRequired()) {
        patch.applied +=
            addConstraint(
                patch.constraints,
                "Distinguish races explicitly: concurrent identical requests for the same Order"
                    + " and Idempotency-Key return the same successful status/body, with the"
                    + " loser marked as replay, while different keys for the same Order yield one"
                    + " success and one INVALID_STATE. Different Orders competing for"
                    + " insufficient overlapping stock yield one success and one"
                    + " INSUFFICIENT_STOCK. No path may return 5xx.",
                patch.task.id(),
                "same-key replay versus different-key race outcomes",
                patch.summaries);
      }
    }
    boolean crossWarehouseAllocationRequired =
        containsAny(
            policy.requirements(),
            "allocate across warehouses",
            "split across warehouses",
            "from multiple warehouses",
            "across matching inventory rows");
    if (patch.createReservationTask && !crossWarehouseAllocationRequired) {
      String withoutInventedAllocation =
          patch.description.replaceAll(
              "(?is)- \\*\\*Multi-warehouse allocation race:\\*\\*.*?(?=- \\*\\*Double-cancel"
                  + " race:|$)",
              "");
      if (!Objects.equals(patch.description, withoutInventedAllocation)) {
        patch.description = withoutInventedAllocation;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " removed unrequested cross-warehouse order allocation");
      }
      patch.applied +=
          removeConstraints(
              patch.constraints,
              constraint ->
                  constraint
                      .toLowerCase(Locale.ROOT)
                      .contains("allocate requested quantity across matching inventory rows"),
              patch.task.id(),
              "unrequested cross-warehouse order allocation",
              patch.summaries);
    }
    if (patch.createReservationTask && crossWarehouseAllocationRequired) {
      String multiWarehouseMutation = rewriteMultiWarehouseReservationMutation(patch.description);
      if (!Objects.equals(patch.description, multiWarehouseMutation)) {
        patch.description = multiWarehouseMutation;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " keys reservation mutations by resolved inventory identity");
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              "For each OrderLine SKU, allocate requested quantity across matching Inventory rows"
                  + " in deterministic (warehouse_id, inventory_id) order. Persist one"
                  + " ReservationLine per allocation with order_line_id and inventory_id; every"
                  + " guarded stock UPDATE must key by inventory_id, never SKU alone. Test a"
                  + " reservation split across two warehouses.",
              patch.task.id(),
              "deterministic multi-warehouse allocation",
              patch.summaries);
    }

    if (policy.sqliteConcurrencyRequired()
        && (patch.createReservationTask
            || patch.stockMutationTask
            || patch.cancellationBehaviorTask)) {
      PlanSafetyInstructionCanonicalizer.RewriteResult sqliteRewrite =
          PlanSafetyInstructionCanonicalizer.rewriteUnsafeSqliteConcurrency(
              patch.description, patch.constraints);
      patch.description = sqliteRewrite.description();
      patch.constraints = sqliteRewrite.constraints();
      if (sqliteRewrite.rewrites() > 0) {
        patch.applied += sqliteRewrite.rewrites();
        patch.summaries.add(
            patch.task.id()
                + " removed "
                + sqliteRewrite.rewrites()
                + " ineffective SQLite row-lock instruction(s) · SQLite concurrency boundary");
      }
      if (policy.sqliteStockConcurrencyRequired() && patch.stockMutationTask) {
        patch.applied +=
            addConstraint(
                patch.constraints,
                "SQLite does not provide effective SELECT FOR UPDATE row locks; use BEGIN"
                    + " IMMEDIATE or one conditional UPDATE with stock >= quantity and verify"
                    + " the affected-row count using separate-connection race tests.",
                patch.task.id(),
                "SQLite-effective atomic stock mutation",
                patch.summaries);
      }
      if (policy.sqliteOverlapConcurrencyRequired() && patch.createReservationTask) {
        patch.applied +=
            addConstraint(
                patch.constraints,
                "SQLite overlap protection must serialize the overlap check plus insert with"
                    + " BEGIN IMMEDIATE or an equivalent database-enforced guard; a SELECT then"
                    + " INSERT in a deferred transaction is not concurrency-safe. Prove it with"
                    + " separate connections synchronized before the decision, asserting one"
                    + " 201, one 409, no 5xx, and final persisted state.",
                patch.task.id(),
                "SQLite-effective concurrent overlap guard",
                patch.summaries);
      }
    }

    PlanSafetyInstructionCanonicalizer.RewriteResult executableRewrite =
        PlanSafetyInstructionCanonicalizer.rewriteContradictoryExecutableInstructions(
            patch.description,
            patch.constraints,
            policy.authenticatedPrincipalRequired() && patch.apiOrServiceTask,
            policy.authenticatedPrincipalRequired() && patch.credentialTask,
            policy.invalidCancellationRequired() && patch.invalidCancellationContractTask,
            policy.blackBoxHttpRequired() && patch.realHttpTestTask);
    patch.description = executableRewrite.description();
    patch.constraints = executableRewrite.constraints();
    if (executableRewrite.rewrites() > 0) {
      patch.applied += executableRewrite.rewrites();
      patch.summaries.add(
          patch.task.id()
              + " canonicalized "
              + executableRewrite.rewrites()
              + " contradictory executable instruction(s) · worker prompt consistency");
    }
  }
}
