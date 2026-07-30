package com.navneet.executespec.integration;

import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;

/** Preserves stable deterministic failure identity at the outer pipeline unwind boundary. */
record RunFailureDiagnostic(String code, String reason) {

  static RunFailureDiagnostic from(Throwable failure, String fallback) {
    Throwable cursor = failure;
    while (cursor != null) {
      if (cursor instanceof BrownfieldHttpContractGuard.ContractCompatibilityException contract) {
        return new RunFailureDiagnostic(contract.code(), contract.getMessage());
      }
      cursor = cursor.getCause() == cursor ? null : cursor.getCause();
    }
    return new RunFailureDiagnostic(fallback, fallback);
  }
}
