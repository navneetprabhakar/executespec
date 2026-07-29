package com.navneet.executespec.integration;

/** Internal event published in the transaction and delivered only after its successful commit. */
record PlanGateOpenedCommitted(String runId, int credits, double estimatedCost) {}
