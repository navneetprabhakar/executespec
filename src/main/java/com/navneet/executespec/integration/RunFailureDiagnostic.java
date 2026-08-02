package com.navneet.executespec.integration;

import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor;

/** Preserves stable deterministic failure identity at the outer pipeline unwind boundary. */
record RunFailureDiagnostic(String code, String reason) {

  static RunFailureDiagnostic from(Throwable failure, String fallback) {
    Throwable cursor = failure;
    while (cursor != null) {
      if (cursor instanceof BrownfieldHttpContractGuard.ContractCompatibilityException contract) {
        return new RunFailureDiagnostic(contract.code(), contract.getMessage());
      }
      if (cursor instanceof SpringHttpSurfaceExtractor.SurfaceExtractionException) {
        return new RunFailureDiagnostic(
            "BROWNFIELD_HTTP_SURFACE_UNPARSABLE",
            "Brownfield HTTP contract verification could not parse generated Java source");
      }
      cursor = cursor.getCause() == cursor ? null : cursor.getCause();
    }
    return new RunFailureDiagnostic(fallback, fallback);
  }
}
