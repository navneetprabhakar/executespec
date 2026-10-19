package com.navneet.executespec.integration;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor;

/** Preserves stable deterministic failure identity at the outer pipeline unwind boundary. */
record RunFailureDiagnostic(String code, String reason) {

  private static final Pattern WORKSPACE_CODE =
      Pattern.compile("^(WORKSPACE_(?:GENERATION|IGNORED_ARTIFACT)_[A-Z0-9_]+)");

  static RunFailureDiagnostic from(Throwable failure, String fallback) {
    ArrayDeque<Throwable> pending = new ArrayDeque<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    if (failure != null) pending.add(failure);
    while (!pending.isEmpty() && seen.size() < 32) {
      Throwable cursor = pending.removeFirst();
      if (!seen.add(cursor)) continue;
      if (cursor
          instanceof
          com.navneet.executespec.workspacegeneration.BuildPlanCapabilityPolicy
              .UnsupportedRuntimeException) {
        return new RunFailureDiagnostic(
            "BUILD_PLAN_RUNTIME_UNSUPPORTED",
            "The generated plan does not match a qualified BUILD runtime; revise the plan before"
                + " execution.");
      }
      if (cursor instanceof BrownfieldHttpContractGuard.ContractCompatibilityException contract) {
        return new RunFailureDiagnostic(contract.code(), contract.getMessage());
      }
      if (cursor instanceof SpringHttpSurfaceExtractor.SurfaceExtractionException) {
        return new RunFailureDiagnostic(
            "BROWNFIELD_HTTP_SURFACE_UNPARSABLE",
            "Brownfield HTTP contract verification could not parse generated Java source");
      }
      Matcher workspace =
          WORKSPACE_CODE.matcher(cursor.getMessage() == null ? "" : cursor.getMessage());
      if (workspace.find()) {
        String exact = workspace.group(1);
        boolean recovery =
            exact.contains("ROLLBACK")
                || exact.startsWith("WORKSPACE_IGNORED_ARTIFACT_")
                || exact.contains("RECOVERY");
        return recovery
            ? new RunFailureDiagnostic(
                "WORKSPACE_GENERATION_RECOVERY_FAILED",
                "Generated changes were rejected, but the workspace could not be proven restored;"
                    + " publication was blocked.")
            : new RunFailureDiagnostic(
                "WORKSPACE_GENERATION_FAILED",
                "Generated changes did not cross the BUILD acceptance boundary; no unverified"
                    + " generation was accepted or published.");
      }
      if (cursor.getCause() != null && cursor.getCause() != cursor) pending.add(cursor.getCause());
      Collections.addAll(pending, cursor.getSuppressed());
    }
    return new RunFailureDiagnostic(fallback, fallback);
  }
}
