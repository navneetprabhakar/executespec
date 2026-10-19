package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunTestsEnvelopeTest {
  @TempDir Path root;
  private final ObjectMapper json = new ObjectMapper();

  private com.fasterxml.jackson.databind.JsonNode envelope(int exit, String output)
      throws Exception {
    return json.readTree(
        new RunTestsEnvelope(json)
            .parseAndEnvelope(
                new ExecutionContext("run", "T1", root), List.of("test_main.py"), exit, output));
  }

  @Test
  void quietPassingFileSelectorIsNotACollectionError() throws Exception {
    var result = envelope(0, ".. [100%]\n2 passed in 0.01s\n");
    assertThat(result.path("errored").isEmpty()).isTrue();
    assertThat(result.path("tests_passed").asInt()).isEqualTo(2);
    assertThat(result.path("tests_total").asInt()).isEqualTo(2);
    assertThat(result.path("unreported_requested_ids").get(0).asText()).isEqualTo("test_main.py");
    assertThat(result.path("passed").isEmpty()).isTrue();
  }

  @Test
  void collectionFailureStillBlocksVerification() throws Exception {
    var result = envelope(2, "ERROR collecting test_main.py\n1 error in 0.01s\n");
    assertThat(result.path("errored").get(0).asText()).isEqualTo("test_main.py");
    assertThat(result.path("exit_code").asInt()).isEqualTo(2);
  }

  @Test
  void zeroExitWithoutExecutedTestsCannotBecomeAllPassed() throws Exception {
    for (String output :
        List.of("no tests ran", "2 skipped in 0.01s", "2 tests collected in 0.01s")) {
      var result = envelope(0, output);
      assertThat(result.path("errored").toString()).contains("PYTEST_EXECUTION_UNPROVEN");
      assertThat(result.path("summary").asText()).doesNotContain("all passed");
    }
  }

  @Test
  void nonzeroExitOverridesApparentlyPassingCounts() throws Exception {
    var result = envelope(5, "2 passed in 0.01s\n");
    assertThat(result.path("errored").isEmpty()).isFalse();
    assertThat(result.path("exit_code").asInt()).isEqualTo(5);
  }

  @Test
  void explicitCommandCannotSilentlyReplaceStructuredSelectors() throws Exception {
    var tool = new RunTestsTool(null, null, json, null);
    var result =
        tool.execute(
            json.readTree(
                "{\"tests\":[\"test_main.py\"],\"command\":\"python3 -m pytest other.py\"}"),
            new ExecutionContext("run", "T1", root));
    assertThat(result).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) result).kind()).isEqualTo(ToolResult.ErrorKind.INVALID_ARGS);
    assertThat(((ToolResult.Err) result).message()).contains("mutually exclusive");
  }
}
