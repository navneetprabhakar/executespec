package com.navneet.executespec.contracts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the V135 contract-registry entities — focused on the
 * forward-compat enum normalisers (NULL/blank/unknown → safe default,
 * never crashes the API on a value a newer node wrote).
 */
class ContractRegistryEntityTest {

    @Test
    void contractType_normalisesNullBlankAndUnknown_toOpenApi() {
        ContractRegistry r = new ContractRegistry();
        r.setType(null);
        assertThat(r.typeEnum()).isEqualTo(ContractRegistry.ContractType.OPENAPI);
        r.setType("FUTURE_FORMAT");
        assertThat(r.typeEnum()).isEqualTo(ContractRegistry.ContractType.OPENAPI);
    }

    @Test
    void contractType_roundTripsForAllKnownValues() {
        for (ContractRegistry.ContractType t : ContractRegistry.ContractType.values()) {
            ContractRegistry r = new ContractRegistry();
            r.setType(t.name());
            assertThat(r.typeEnum()).isEqualTo(t);
        }
    }

    @Test
    void detectionMethod_normalisesNullAndUnknown_toAutoFile() {
        ContractRegistry r = new ContractRegistry();
        r.setDetectionMethod(null);
        assertThat(r.detectionMethodEnum()).isEqualTo(ContractRegistry.DetectionMethod.AUTO_FILE);
        r.setDetectionMethod("FUTURE_METHOD");
        assertThat(r.detectionMethodEnum()).isEqualTo(ContractRegistry.DetectionMethod.AUTO_FILE);
    }

    @Test
    void detectionMethod_roundTripsForAllKnownValues() {
        for (ContractRegistry.DetectionMethod m : ContractRegistry.DetectionMethod.values()) {
            ContractRegistry r = new ContractRegistry();
            r.setDetectionMethod(m.name());
            assertThat(r.detectionMethodEnum()).isEqualTo(m);
        }
    }

    @Test
    void driftEvent_resolvedActionNullForUnresolved() {
        ContractDriftEvent e = new ContractDriftEvent();
        assertThat(e.resolvedActionEnum()).isNull();
        e.setResolvedAction("");
        assertThat(e.resolvedActionEnum()).isNull();
        e.setResolvedAction("FUTURE_ACTION"); // unknown forward-compat
        assertThat(e.resolvedActionEnum()).isNull();
    }

    @Test
    void driftEvent_resolvedActionRoundTrips() {
        ContractDriftEvent e = new ContractDriftEvent();
        e.setResolvedAction("REFRESHED");
        assertThat(e.resolvedActionEnum()).isEqualTo(ContractDriftEvent.ResolvedAction.REFRESHED);
        e.setResolvedAction("DISMISSED");
        assertThat(e.resolvedActionEnum()).isEqualTo(ContractDriftEvent.ResolvedAction.DISMISSED);
    }
}
