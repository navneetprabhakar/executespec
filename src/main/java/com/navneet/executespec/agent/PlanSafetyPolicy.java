package com.navneet.executespec.agent;

import java.util.Map;

record PlanSafetyPolicy(
    Plan plan,
    String requirements,
    boolean authenticatedPrincipalRequired,
    boolean resourceOwnershipRequired,
    boolean principalScopedIdempotencyRequired,
    boolean exactIdempotentReplayRequired,
    boolean migrationsRequired,
    boolean reservationArithmetic,
    boolean reservationAggregateRequired,
    boolean reservationLedgerRequired,
    boolean sqliteConcurrencyRequired,
    boolean sqliteStockConcurrencyRequired,
    boolean sqliteOverlapConcurrencyRequired,
    boolean invalidCancellationRequired,
    boolean blackBoxHttpRequired,
    boolean canonicalSanitizedErrorsRequired,
    boolean orderStatusConsumed,
    OrderStatusContract orderStatusContract,
    boolean mixedDatabaseSessionModes,
    boolean synchronousDatabaseRequired,
    boolean realHttpAcceptanceRequired,
    boolean pureBuildAndTestCommandsRequired,
    boolean rootFullStackBuildRequired,
    boolean idempotencyHeaderRequired,
    boolean setupDocumentationRequired,
    boolean singleDevelopmentCommandRequired,
    boolean[] documentationOwnerAssigned,
    boolean canonicalTransferModelExists,
    Map<ApiOperation, String> canonicalApiEndpoints) {}
