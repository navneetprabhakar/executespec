package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.engine.CodeGenEngine;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.swebench.SwebenchContainerStore;
import com.navneet.executespec.swebench.SwebenchInstanceLoader;
import com.navneet.executespec.swebench.SwebenchProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the blind-spec contract for benchmark generation.
 *
 * <p>SWE-bench Pro's official evaluation setting includes problem_statement + requirements +
 * interface in the agent prompt (arXiv:2509.16941); the held-out signals (FAIL_TO_PASS /
 * PASS_TO_PASS / test_patch / gold patch) must NEVER appear. This test locks both directions.
 */
class BenchmarkBlindSpecTest {

  private static final String GOLD_PATCH = "diff --git a/secret_gold.go b/secret_gold.go";
  private static final String TEST_PATCH = "diff --git a/hidden_test.go b/hidden_test.go";
  private static final String FAILING_TEST_ID = "TestHiddenThing/case_1";
  private static final String PASSING_TEST_ID = "TestExistingThing/case_2";

  private static SwebenchInstanceLoader.SwebenchInstance instance(
      String interfaceText, String requirements) {
    return new SwebenchInstanceLoader.SwebenchInstance(
        "acme__widget-1234",
        "acme/widget",
        "deadbeef",
        "Widget crashes when the flux capacitor is empty.",
        List.of(FAILING_TEST_ID),
        List.of(PASSING_TEST_ID),
        "1.0",
        TEST_PATCH,
        GOLD_PATCH,
        "sweap-acme-widget", // dockerhubTag != blank => isSweBenchPro()
        "go",
        interfaceText,
        requirements);
  }

  private static BenchmarkGenerationService service() {
    return new BenchmarkGenerationService(
        new SwebenchProperties(),
        mock(SwebenchInstanceLoader.class),
        mock(SwebenchContainerStore.class),
        mock(CodeGenEngine.class),
        mock(PipelineRunRepository.class),
        mock(RunCostTracker.class));
  }

  @Test
  void injectsRequirementsAndInterfaceSectionsWhenPresent() {
    String requirements = "- Empty capacitor must return ErrEmptyFlux, not panic.";
    String interfaceText = "func NewFluxGuard(cap *Capacitor) (*FluxGuard, error)";
    String spec = service().synthesizeBlindSpec(instance(interfaceText, requirements));

    assertThat(spec).contains("# Requirements");
    assertThat(spec).contains(requirements);
    assertThat(spec).contains("# Interface");
    assertThat(spec).contains(interfaceText);
    // Interface must be a full section, not the old cramped "- Interface:" repo bullet.
    assertThat(spec).doesNotContain("- Interface:");
    // Ordering: Issue -> Requirements -> Interface -> How to work.
    assertThat(spec.indexOf("# Issue")).isLessThan(spec.indexOf("# Requirements"));
    assertThat(spec.indexOf("# Requirements")).isLessThan(spec.indexOf("# Interface"));
    assertThat(spec.indexOf("# Interface")).isLessThan(spec.indexOf("# How to work"));
  }

  @Test
  void omitsSectionsWhenFieldsBlank() {
    String spec = service().synthesizeBlindSpec(instance("", ""));
    assertThat(spec).doesNotContain("# Requirements");
    assertThat(spec).doesNotContain("# Interface");
    assertThat(spec).contains("# Issue");
    assertThat(spec).contains("# How to work");
  }

  @Test
  void neverLeaksHeldOutSignals() {
    for (String spec :
        List.of(
            service().synthesizeBlindSpec(instance("iface", "reqs")),
            service().synthesizeBlindSpec(instance("", "")))) {
      assertThat(spec).doesNotContain(GOLD_PATCH);
      assertThat(spec).doesNotContain(TEST_PATCH);
      assertThat(spec).doesNotContain(FAILING_TEST_ID);
      assertThat(spec).doesNotContain(PASSING_TEST_ID);
      assertThat(spec).doesNotContainIgnoringCase("FAIL_TO_PASS");
    }
  }

  @Test
  void keepsBlindFramingAndVerifyChecklist() {
    String spec = service().synthesizeBlindSpec(instance("iface", "reqs"));
    assertThat(spec).contains("/app"); // Pro workdir
    assertThat(spec).contains("offline (no network)");
    assertThat(spec).contains("BEFORE completeTask, self-verify");
  }
}
