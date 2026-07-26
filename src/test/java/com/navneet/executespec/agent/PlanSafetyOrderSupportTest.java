package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlanSafetyOrderSupportTest {

  @Test
  void normalizesExplicitOrderAndReservationPersistenceFields() {
    assertThat(
            PlanSafetyOrderSupport.normalizeOrderStatusField(
                "Define the Order model with state default DRAFT and lifecycle values."))
        .contains("Order model with status default DRAFT")
        .doesNotContain("with state default");
    assertThat(
            PlanSafetyOrderSupport.normalizeCanonicalStatusReference(
                "Persist Reservation.state and migrate the Reservation model state field.",
                "Reservation"))
        .isEqualTo("Persist Reservation.status and migrate the Reservation model status field.");
  }

  @Test
  void leavesUiAndLocalStateLanguageUnchanged() {
    String orderUi =
        "The Order form keeps local state for loading, error, and confirmation behavior.";
    String reservationUi = "The Reservation page stores filter state and renders an empty state.";

    assertThat(PlanSafetyOrderSupport.normalizeOrderStatusField(orderUi)).isEqualTo(orderUi);
    assertThat(
            PlanSafetyOrderSupport.normalizeCanonicalStatusReference(reservationUi, "Reservation"))
        .isEqualTo(reservationUi);
  }

  @Test
  void leavesAnotherAggregatesStateUnchanged() {
    String evidence =
        "Define the Order model with state DRAFT. Inventory state remains a derived UI value.";

    assertThat(PlanSafetyOrderSupport.normalizeOrderStatusField(evidence))
        .isEqualTo(
            "Define the Order model with status DRAFT. Inventory state remains a derived UI"
                + " value.");
  }
}
