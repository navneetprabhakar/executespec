package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyContractEnricher.*;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.*;
import static com.navneet.executespec.agent.PlanSafetyExecutionTextSupport.*;
import static com.navneet.executespec.agent.PlanSafetyStructureSupport.*;
import static com.navneet.executespec.agent.PlanSafetyTextSupport.*;

import java.util.Locale;
import java.util.Objects;

final class PlanSafetyTaskPhase1 {
  private PlanSafetyTaskPhase1() {}

  static void apply(PlanSafetyPolicy policy, PlanSafetyTaskPatch patch) {
    PlanSafetyMigrationPhase.apply(policy, patch);
    if (policy.canonicalTransferModelExists()) {
      for (int index = 0; index < patch.touchesFiles.size(); index++) {
        String path = patch.touchesFiles.get(index);
        if (path != null && path.toLowerCase(Locale.ROOT).endsWith("/models/stock_transfer.py")) {
          patch.touchesFiles.set(
              index,
              path.substring(0, path.length() - "stock_transfer.py".length()) + "transfer.py");
          patch.applied++;
          patch.summaries.add(patch.task.id() + " touches canonical StockTransfer model file");
        }
      }
    }
    int duplicateConstraints = patch.task.hardConstraints().size() - patch.constraints.size();
    if (duplicateConstraints > 0) {
      patch.applied += duplicateConstraints;
      patch.summaries.add(
          patch.task.id() + " removed " + duplicateConstraints + " duplicate hard constraint(s)");
    }
    patch.description =
        TaskExpanderAgent.normalizeTechnicalSpacing(
            patch.description == null ? patch.task.description() : patch.description);
    patch.description = rewritePydanticSettingsDependency(patch.description);
    patch.description = rewriteReversibleMigrationLanguage(patch.description);
    String withoutPlaceholder =
        patch
            .description
            .replaceAll(
                "(?i),?\\s*then (?:the )?acceptance placeholder\\.?",
                "; later acceptance tasks add maintained tests under the same test command.")
            .replaceAll("(?i)acceptance placeholder", "maintained black-box acceptance tests");
    if (!Objects.equals(patch.description, withoutPlaceholder)) {
      patch.description = withoutPlaceholder;
      patch.applied++;
      patch.summaries.add(patch.task.id() + " removed non-executable acceptance placeholder");
    }
    if (!Objects.equals(patch.description, patch.task.description())) {
      patch.applied++;
      patch.summaries.add(patch.task.id() + " normalized technical version/file spacing");
    }
    patch.globallyNormalizedDescription = normalizeIdempotencyRecordNames(patch.description);
    if (!Objects.equals(patch.description, patch.globallyNormalizedDescription)) {
      patch.description = patch.globallyNormalizedDescription;
      patch.applied++;
      patch.summaries.add(patch.task.id() + " normalized shared idempotency names and error codes");
    }
    for (int index = 0; index < patch.constraints.size(); index++) {
      String constraint = patch.constraints.get(index);
      String normalizedConstraint =
          rewriteReversibleMigrationLanguage(normalizeIdempotencyRecordNames(constraint));
      if (!constraint.equals(normalizedConstraint)) {
        patch.constraints.set(index, normalizedConstraint);
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " normalized shared idempotency constraint terminology");
      }
    }
    if (policy.orderStatusContract() != null || policy.reservationAggregateRequired()) {
      String canonicalDescription = patch.description;
      if (policy.orderStatusContract() != null) {
        canonicalDescription =
            PlanSafetyOrderSupport.normalizeCanonicalStatusReference(canonicalDescription, "Order");
      }
      if (policy.reservationAggregateRequired()) {
        canonicalDescription =
            PlanSafetyOrderSupport.normalizeCanonicalStatusReference(
                canonicalDescription, "Reservation");
      }
      if (!Objects.equals(patch.description, canonicalDescription)) {
        patch.description = canonicalDescription;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " aligned lifecycle references to canonical persisted status fields");
      }
      for (int index = 0; index < patch.constraints.size(); index++) {
        String constraint = patch.constraints.get(index);
        String canonicalConstraint = constraint;
        if (policy.orderStatusContract() != null) {
          canonicalConstraint =
              PlanSafetyOrderSupport.normalizeCanonicalStatusReference(
                  canonicalConstraint, "Order");
        }
        if (policy.reservationAggregateRequired()) {
          canonicalConstraint =
              PlanSafetyOrderSupport.normalizeCanonicalStatusReference(
                  canonicalConstraint, "Reservation");
        }
        if (!constraint.equals(canonicalConstraint)) {
          patch.constraints.set(index, canonicalConstraint);
          patch.applied++;
          patch.summaries.add(
              patch.task.id()
                  + " aligned lifecycle constraint to canonical persisted status fields");
        }
      }
    }
    String migrationEvidence =
        (safe(patch.description) + "\n" + String.join("\n", patch.constraints))
            .toLowerCase(Locale.ROOT);
    if (migrationEvidence.contains("forward-only")
        && migrationEvidence.matches("(?s).*downgrade.{0,100}(?:drop|removes?).*")) {
      patch.description = patch.description.replaceAll("(?i)\\bforward-only\\b", "reversible");
      for (int index = 0; index < patch.constraints.size(); index++) {
        patch.constraints.set(
            index, patch.constraints.get(index).replaceAll("(?i)\\bforward-only\\b", "reversible"));
      }
      patch.applied++;
      patch.summaries.add(
          patch.task.id()
              + " reconciled cross-field migration direction with the executable downgrade");
    }
    if (policy.requirements().contains("audit")
        && taskEvidence(patch.task).contains("stockaudit")) {
      String auditNormalized =
          patch
              .description
              .replace("\"ADJUSTMENT\"", "\"adjustment\"")
              .replace("\"TRANSFER\"", "\"transfer\"");
      if (!Objects.equals(patch.description, auditNormalized)) {
        patch.description = auditNormalized;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " normalized StockAudit event type values");
      }
    }
    if (policy.requirements().contains("audit")
        && containsAny(taskEvidence(patch.task), "audit", "stock mutation", "reservation")) {
      java.util.List<String> requiredAuditOperations =
          PlanSafetyAuditSupport.requiredAuditOperations(policy.requirements());
      String normalizedAuditDescription =
          PlanSafetyAuditSupport.normalizeAuditOperations(
              patch.description, requiredAuditOperations);
      if (!Objects.equals(patch.description, normalizedAuditDescription)) {
        patch.description = normalizedAuditDescription;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " normalized immutable audit operation semantics");
      }
      String auditContract = PlanSafetyAuditSupport.bindingAuditContract(requiredAuditOperations);
      if (auditContract != null) {
        patch.applied +=
            addConstraint(
                patch.constraints,
                auditContract,
                patch.task.id(),
                "requirement-derived immutable audit operations",
                patch.summaries);
      }
    }
    patch.spacingNormalized = normalizeTechnicalSpacing(patch.description);
    if (!Objects.equals(patch.description, patch.spacingNormalized)) {
      patch.description = patch.spacingNormalized;
      patch.applied++;
      patch.summaries.add(patch.task.id() + " repaired technical identifier spacing");
    }
    patch.endpointNormalized =
        normalizeApiEndpoints(patch.description, policy.canonicalApiEndpoints());
    if (policy.requirements().contains("audit")
        && containsAny(
            patch.endpointNormalized.toLowerCase(Locale.ROOT), "audit", "history", "timeline")) {
      patch.endpointNormalized =
          normalizeAuditHistoryReads(
              patch.endpointNormalized,
              policy.canonicalApiEndpoints().get(ApiOperation.AUDIT_HISTORY));
      patch.constraints.replaceAll(
          constraint ->
              normalizeAuditHistoryReads(
                  constraint, policy.canonicalApiEndpoints().get(ApiOperation.AUDIT_HISTORY)));
    }
    if (!isFrontendOnlyTask(patch.task) && planUsesApiBasePath(policy.plan())) {
      patch.endpointNormalized = normalizeBackendApiBasePath(patch.endpointNormalized);
    }
    if (policy.reservationAggregateRequired()) {
      patch.endpointNormalized =
          patch.endpointNormalized.replaceAll(
              "(?i)(POST\\s+`?)/api/reservations/reserve(`?)", "$1/api/reservations$2");
    }
    if (!Objects.equals(patch.description, patch.endpointNormalized)) {
      patch.description = patch.endpointNormalized;
      patch.applied++;
      patch.summaries.add(patch.task.id() + " reuses canonical backend API endpoint paths");
    }
    patch.taskEvidence = taskEvidence(patch.task);
    patch.backendInventoryReadTask =
        !isFrontendOnlyTask(patch.task)
            && !isTestOnlyTask(patch.task)
            && containsAny(
                patch.taskEvidence, "product/stock listing", "list products", "get /api/stock");
    if (policy.requirements().contains("inventory overview") && patch.backendInventoryReadTask) {
      String readContract =
          "Expose GET /api/warehouses for the warehouse selector and overview counts, alongside"
              + " the canonical product and stock read endpoints; frontend and black-box tests"
              + " use this exact path.";
      if (!patch.description.contains(readContract)) {
        patch.description = patch.description + "\n\nCONTRACT CORRECTION: " + readContract;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " description += warehouse read API");
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              readContract,
              patch.task.id(),
              "warehouse read API ownership",
              patch.summaries);
    }
    patch.backendOrderApiTask =
        !isFrontendOnlyTask(patch.task)
            && !isTestOnlyTask(patch.task)
            && containsAny(patch.taskEvidence, "order creation api", "post /api/orders");
    if (policy.requirements().contains("order creation") && patch.backendOrderApiTask) {
      String orderReadContract =
          "Expose GET /api/orders and GET /api/orders/{order_id} using the same Order/OrderLine"
              + " response contract as creation; frontend and persistence tests use these exact"
              + " paths.";
      if (!patch.description.contains(orderReadContract)) {
        patch.description = patch.description + "\n\nCONTRACT CORRECTION: " + orderReadContract;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " description += order read APIs");
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              orderReadContract,
              patch.task.id(),
              "order read API ownership",
              patch.summaries);
    }
    patch.stockMutationServiceTask =
        !isFrontendOnlyTask(patch.task)
            && !isTestOnlyTask(patch.task)
            && containsAny(patch.taskEvidence, "stock adjustment", "stock transfer")
            && containsAny(taskIdentityEvidence(patch.task), "service", "inventory_service");
    if (patch.stockMutationServiceTask) {
      String guardedStockCorrection =
          "CONTRACT CORRECTION (binding concurrency): never implement stock mutation as a"
              + " read-compute-write assignment. Adjustment must use one conditional UPDATE"
              + " (`on_hand = on_hand + :delta` with `on_hand + :delta >= reserved`) and verify"
              + " rowcount, so adjustment cannot consume reserved units. Transfer must"
              + " atomically guard/decrement the source with `on_hand - :quantity >= reserved`,"
              + " increment the destination, and insert audit rows in the same transaction; any"
              + " failed guard rolls back the entire mutation and audit write.";
      if (!patch.description.contains(guardedStockCorrection)) {
        patch.description = patch.description + "\n\n" + guardedStockCorrection;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " description += guarded stock mutation correction");
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              "All stock decrements/adjustments must be database-guarded conditional UPDATEs"
                  + " with affected-row verification and must preserve `on_hand >= reserved`;"
                  + " read-compute-write ORM assignment is forbidden. Transfer source decrement,"
                  + " destination increment, and audit insert are one rollback-safe transaction.",
              patch.task.id(),
              "database-guarded stock mutation",
              patch.summaries);
    }
    patch.ownsStockMutationApi =
        !isFrontendOnlyTask(patch.task)
            && !isTestOnlyTask(patch.task)
            && containsAny(patch.taskEvidence, "stock adjustment", "stock transfer")
            && patch.taskEvidence.contains("/api/");
    if (policy.requirements().contains("audit") && patch.ownsStockMutationApi) {
      String auditPath = policy.canonicalApiEndpoints().get(ApiOperation.AUDIT_HISTORY);
      String adjustmentsPath = policy.canonicalApiEndpoints().get(ApiOperation.ADJUST_STOCK);
      String transfersPath = policy.canonicalApiEndpoints().get(ApiOperation.TRANSFER_STOCK);
      if (auditPath != null) {
        patch.constraints.removeIf(
            constraint -> {
              String normalized = safe(constraint).toLowerCase(Locale.ROOT);
              return containsAny(normalized, "audit history", "adjustment/transfer records")
                  && !normalized.contains(auditPath);
            });
        patch.applied +=
            addConstraint(
                patch.constraints,
                "Expose unified read-only audit history at GET "
                    + auditPath
                    + "; return persisted adjustment/transfer records with timestamps and"
                    + " warehouse/product data. Frontend and acceptance tests must use this"
                    + " exact path.",
                patch.task.id(),
                "unified backend audit-history API ownership",
                patch.summaries);
      } else if (adjustmentsPath != null && transfersPath != null) {
        patch.applied +=
            addConstraint(
                patch.constraints,
                "Expose read-only audit history at GET "
                    + adjustmentsPath
                    + " and GET "
                    + transfersPath
                    + "; return persisted adjustment/transfer records with timestamps and"
                    + " warehouse/product data. Frontend and acceptance tests must use these"
                    + " exact paths.",
                patch.task.id(),
                "backend audit-history API ownership",
                patch.summaries);
      }
    }
    patch.ownsIdempotencyModel =
        patch.touchesFiles.stream()
            .filter(Objects::nonNull)
            .map(path -> path.toLowerCase(Locale.ROOT))
            .anyMatch(path -> path.endsWith("/models/idempotency.py"));
    patch.ownsMigration =
        patch.touchesFiles.stream()
            .filter(Objects::nonNull)
            .map(path -> path.toLowerCase(Locale.ROOT))
            .anyMatch(path -> path.contains("/alembic/versions/") || path.contains("/migration"));
    if (policy.migrationsRequired()
        && patch.ownsIdempotencyModel
        && !patch.ownsMigration
        && (!PlanSafetyMigrationSupport.requiresConsolidatedMigration(policy.plan())
            || PlanSafetyMigrationSupport.isConsolidatedMigrationOwner(
                patch.task, policy.plan()))) {
      String migrationPath = idempotencyMigrationPath(policy.plan().tasks());
      if (!patch.touchesFiles.contains(migrationPath)) {
        patch.touchesFiles.add(migrationPath);
        patch.applied++;
        patch.summaries.add(patch.task.id() + " owns the IdempotencyRecord forward migration");
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Add a forward Alembic migration that creates IdempotencyRecord with the exact"
                  + " canonical fields and unique (key, scope) constraint; a model file without"
                  + " a migration is incomplete.",
              patch.task.id(),
              "IdempotencyRecord migration ownership",
              patch.summaries);
    }
    patch.orderSchemaProducer =
        TaskExpanderAgent.isOrderSchemaProducer(patch.task, patch.taskEvidence)
            || (TaskExpanderAgent.isPersistenceSchemaProducer(patch.task, patch.taskEvidence)
                && consumesOrderStatus(patch.taskEvidence));
    if (patch.orderSchemaProducer && policy.exactIdempotentReplayRequired()) {
      String separatedOwnership = removeBusinessAggregateIdempotencyFields(patch.description);
      if (!Objects.equals(patch.description, separatedOwnership)) {
        patch.description = separatedOwnership;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " moved replay ownership off the Order business aggregate");
      }
    }
    patch.reservationSchemaProducer =
        patch.touchesFiles.stream()
                .filter(Objects::nonNull)
                .map(path -> path.replace('\\', '/').toLowerCase(Locale.ROOT))
                .anyMatch(
                    path ->
                        path.endsWith("/models/reservation.py")
                            || path.endsWith("/model/reservation.py")
                            || path.endsWith("/reservation_model.py"))
            || (TaskExpanderAgent.isPersistenceSchemaProducer(patch.task, patch.taskEvidence)
                && containsAny(
                    patch.taskEvidence,
                    "reservation model",
                    "reservation table",
                    "class reservation",
                    "reservation aggregate",
                    "**reservation**",
                    "reservationline"));
    patch.orderEvidence =
        patch.taskEvidence + "\n" + String.join("\n", patch.constraints).toLowerCase(Locale.ROOT);
    if (policy.orderStatusContract() != null
        && !Objects.equals(patch.task.id(), policy.orderStatusContract().producerTaskId())
        && consumesOrderStatus(patch.orderEvidence)) {
      String normalizedDescription =
          normalizeOrderStatusValues(
              PlanSafetyOrderSupport.normalizeOrderStatusField(patch.description),
              policy.orderStatusContract());
      if (!Objects.equals(patch.description, normalizedDescription)) {
        patch.description = normalizedDescription;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " description aligned to producer Order.status values");
      }
      for (int index = 0; index < patch.constraints.size(); index++) {
        String constraint = patch.constraints.get(index);
        String normalizedConstraint =
            normalizeOrderStatusValues(constraint, policy.orderStatusContract());
        if (!constraint.equals(normalizedConstraint)) {
          patch.constraints.set(index, normalizedConstraint);
          patch.applied++;
          patch.summaries.add(
              patch.task.id() + " hard constraint aligned to producer Order.status values");
        }
      }
      patch.taskEvidence =
          (safe(patch.task.title())
                  + "\n"
                  + safe(patch.description)
                  + "\n"
                  + String.join("\n", patch.task.touchesFiles()))
              .toLowerCase(Locale.ROOT);
    }
  }

  private static String normalizeAuditHistoryReads(String value, String auditPath) {
    if (value == null || value.isBlank() || auditPath == null || auditPath.isBlank()) {
      return safe(value);
    }
    return value
        .replaceAll(
            "(?i)GET\\s+`?/api/[A-Za-z0-9_{}?=&./:-]*(?:adjust|transfer)"
                + "[A-Za-z0-9_{}?=&./:-]*`?",
            "GET " + auditPath)
        .replaceAll(
            "(?i)(GET\\s+"
                + java.util.regex.Pattern.quote(auditPath)
                + ")(?:\\s+(?:and|,)"
                + "\\s+GET\\s+"
                + java.util.regex.Pattern.quote(auditPath)
                + ")+",
            "$1");
  }
}
