package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentActionCatalogTest {

  private final AgentActionCatalog catalog = new AgentActionCatalog();

  @Test
  void cliGetsLocalToolsButWebDoesNot() {
    var cli = catalog.capabilities("CLI", "FREE");
    var web = catalog.capabilities("WEB", "FREE");

    assertThat(cli)
        .extracting("name")
        .contains("local_git_diff", "local_git_status", "local_code_task");
    assertThat(web)
        .extracting("name")
        .doesNotContain("local_git_diff", "local_git_status", "local_code_task");
  }

  @Test
  void tierFilteringHidesPaidAndEnterpriseTools() {
    var free = catalog.capabilities("CLI", "FREE");
    var pro = catalog.capabilities("CLI", "PRO");
    var enterprise = catalog.capabilities("CLI", "ENTERPRISE");

    assertThat(free)
        .extracting("name")
        .doesNotContain("run_code_review", "manage_org_model_overrides");
    assertThat(pro)
        .extracting("name")
        .contains("run_code_review")
        .doesNotContain("manage_org_model_overrides");
    assertThat(enterprise)
        .extracting("name")
        .contains("run_code_review", "manage_org_model_overrides");
  }

  @Test
  void capabilityMetadataDeclaresSideEffectsAndConfirmation() {
    var approve =
        catalog.capabilities("VSCODE", "FREE").stream()
            .filter(c -> c.name().equals("approve_run"))
            .findFirst()
            .orElseThrow();

    assertThat(approve.sideEffect()).isEqualTo("billable");
    assertThat(approve.confirmationRequired()).isTrue();
    assertThat(approve.billingImpact()).isEqualTo("run_credit");
    assertThat(approve.inputSchema()).containsKey("required");
  }

  @Test
  void actionDescriptorsStayAlignedWithCapabilityMetadata() {
    var capabilitiesByName =
        catalog.allCapabilities().stream()
            .collect(java.util.stream.Collectors.toMap(c -> c.name(), c -> c));

    for (var descriptor : catalog.list()) {
      var capability = capabilitiesByName.get(descriptor.type());

      assertThat(capability).as("capability for %s", descriptor.type()).isNotNull();
      assertThat(capability.confirmationRequired())
          .as("confirmation for %s", descriptor.type())
          .isEqualTo(descriptor.requiresConfirmation());
      assertThat(capability.billingImpact())
          .as("billing impact for %s", descriptor.type())
          .isEqualTo(descriptor.billingImpact());
      assertThat(capability.inputSchema())
          .as("required fields for %s", descriptor.type())
          .containsEntry("required", descriptor.requiredFields());
    }
  }

  @Test
  void webResearchToolsExposedOnEverySurface() {
    for (String surface : java.util.List.of("WEB", "CLI", "VSCODE")) {
      assertThat(catalog.capabilities(surface, "FREE"))
          .as("web research tools on %s", surface)
          .extracting("name")
          .contains("web_search", "web_fetch");
    }
  }
}
