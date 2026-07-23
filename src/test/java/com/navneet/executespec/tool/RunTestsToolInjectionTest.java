package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RR-547 — test identifiers are concatenated into a shell string on every non-pytest path, and the
 * Go path wraps them in single quotes. These are the characters that let a model terminate that
 * quoting or inject runner options.
 */
@DisplayName("RunTestsTool — id injection")
class RunTestsToolInjectionTest {

  @Test
  @DisplayName("an apostrophe can no longer terminate the Go -run quoting")
  void apostropheRejected() {
    assertThat(RunTestsTool.rejectionForTestId("TestFoo' -exec.arg 'x"))
        .contains("quotes or whitespace");
    // The composer that consumed it still quotes clean names the same way.
    assertThat(
            RunTestsTool.frameworkCommand(
                com.navneet.executespec.smoketest.TestCommandResolver.Framework.GO,
                List.of("TestFoo"),
                null))
        .isEqualTo("go test -run 'TestFoo' ./...");
  }

  @Test
  @DisplayName("quotes, whitespace and leading options are rejected")
  void quotesWhitespaceAndOptionsRejected() {
    assertThat(RunTestsTool.rejectionForTestId("\"TestFoo\"")).isNotNull();
    assertThat(RunTestsTool.rejectionForTestId("TestFoo TestBar")).isNotNull();
    assertThat(RunTestsTool.rejectionForTestId("\tTestFoo")).isNotNull();
    assertThat(RunTestsTool.rejectionForTestId("-Dfoo=bar")).contains("may not start with '-'");
  }

  @Test
  @DisplayName("classic metachars stay rejected")
  void metacharsStillRejected() {
    assertThat(RunTestsTool.rejectionForTestId("a;rm -rf b")).isNotNull();
    assertThat(RunTestsTool.rejectionForTestId("a$(id)")).isNotNull();
    assertThat(RunTestsTool.rejectionForTestId("a|b")).isNotNull();
  }

  @Test
  @DisplayName("real framework identifiers still pass")
  void legitimateIdsAccepted() {
    assertThat(RunTestsTool.rejectionForTestId("tests/test_widget.py::test_renders")).isNull();
    assertThat(RunTestsTool.rejectionForTestId("TestParseConfig")).isNull();
    assertThat(RunTestsTool.rejectionForTestId("./pkg/...")).isNull();
    assertThat(RunTestsTool.rejectionForTestId("com.example.OrderServiceTest")).isNull();
    assertThat(RunTestsTool.rejectionForTestId("src/app/order.spec.ts")).isNull();
  }
}
