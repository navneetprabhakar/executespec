package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlanSharedFieldContractGateCompactDeclarationTest {

  @Test
  void rejectsCompactMarkdownEntityDriftBetweenSchemaAndService() {
    List<String> violations =
        violations(
            task(
                "T2",
                "Define stock schema",
                "- **Stock** — `id` (Integer), `warehouse_id` (Integer), `product_id` (Integer),"
                    + " `quantity` (Integer), `updated_at` (DateTime)"),
            task(
                "T4",
                "Implement stock mutation",
                "- **Stock** — `id` (Integer), `warehouse_id` (Integer), `product_id` (Integer),"
                    + " `on_hand` (Integer), `reserved` (Integer), `updated_at` (DateTime)"));

    assertThat(violations)
        .containsExactly(
            "T4: shared stock field contract differs from T2; first-only=[quantity],"
                + " second-only=[on_hand, reserved]");
  }

  @Test
  void rejectsTypeScriptConsumerFieldAndTypeDriftFromMarkdownProducer() {
    List<String> fieldViolations =
        violations(
            task(
                "T2",
                "Define stock response",
                "- **StockItem** — `id` (Integer), `warehouse_id` (Integer), `product_id`"
                    + " (Integer), `quantity` (Integer)"),
            task(
                "T10",
                "Consume stock response",
                "StockItem: { id: number; warehouse_id: number; product_id: number; on_hand:"
                    + " number; reserved: number }"));

    assertThat(fieldViolations)
        .containsExactly(
            "T10: shared stockitem field contract differs from T2; first-only=[quantity],"
                + " second-only=[on_hand, reserved]");

    List<String> typeViolations =
        violations(
            task(
                "T1",
                "Define catalog response",
                "- **CatalogItem** — `id` (Integer), `code` (String), `active` (Boolean)"),
            task(
                "T8",
                "Consume catalog response",
                "CatalogItem: { id: string; code: string; active: boolean }"));

    assertThat(typeViolations)
        .containsExactly(
            "T8: shared catalogitem field type contract differs from T1;"
                + " mismatched={id=number vs string}");
  }

  @Test
  void ignoresCompactTaskLocalPropsForDifferentComponents() {
    assertThat(
            violations(
                task(
                    "T9",
                    "Build inventory table",
                    "Props: { products: Product[]; loading: boolean; onSearch: Callback }"),
                task(
                    "T10",
                    "Build transfer dialog",
                    "Props: { open: boolean; warehouseId: string; onClose: Callback }")))
        .isEmpty();
  }

  private static List<String> violations(PlannedTask... tasks) {
    List<String> violations = new ArrayList<>();
    PlanSharedFieldContractGate.addViolations(List.of(tasks), violations);
    return violations;
  }

  private static PlannedTask task(String id, String title, String description) {
    return PlannedTask.builder().id(id).title(title).description(description).build();
  }
}
