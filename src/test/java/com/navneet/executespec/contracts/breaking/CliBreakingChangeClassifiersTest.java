package com.navneet.executespec.contracts.breaking;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.contracts.ContractRegistry;

import org.junit.jupiter.api.Test;

/**
 * Tests for the CLI-backed classifiers (E2.7) — verifies graceful degradation when the underlying
 * CLI isn't installed (the typical case in CI without the worker container's toolchain), and the
 * line-parsing helpers used to map CLI output → ContractDiff.Change.
 */
class CliBreakingChangeClassifiersTest {

  @Test
  void openApi_unclassifiedWhenOasdiffMissing() {
    // Stub runner that always reports "CLI not available" — same shape
    // the real runner produces when oasdiff isn't on PATH.
    CliBreakingChangeRunner stubRunner =
        new CliBreakingChangeRunner() {
          @Override
          public CliResult run(
              String oldContent,
              String newContent,
              String prefix,
              java.util.function.BiFunction<Path, Path, List<String>> argv,
              long timeoutSeconds) {
            return new CliResult(false, -1, null, null, "CLI 'oasdiff' not available on PATH");
          }
        };
    OpenApiBreakingChangeClassifier c = new OpenApiBreakingChangeClassifier(stubRunner);
    ContractDiff diff = c.classify("openapi: 3.0", "openapi: 3.0");
    assertThat(diff.confidence()).isEqualTo(ContractDiff.Confidence.UNCLASSIFIED);
    assertThat(diff.summary()).contains("oasdiff");
    assertThat(diff.classifierName()).isEqualTo("OpenApiBreakingChangeClassifier");
    assertThat(c.type()).isEqualTo(ContractRegistry.ContractType.OPENAPI);
  }

  @Test
  void openApi_parseLines_routesErrorWarningInfoToCorrectSeverity() {
    OpenApiBreakingChangeClassifier c = new OpenApiBreakingChangeClassifier(null);
    String stdout =
        String.join(
            "\n",
            "error at /paths/~1pay/post/responses/200: response field 'currency' was removed",
            "warning at /paths/~1pay/get: parameter description changed",
            "info at /info: title changed",
            ""); // blank line ignored
    ContractDiff diff = c.parseLines(stdout);
    assertThat(diff.confidence()).isEqualTo(ContractDiff.Confidence.DETERMINISTIC);
    assertThat(diff.changes()).hasSize(3);
    assertThat(diff.changes().get(0).severity()).isEqualTo(ContractDiff.Change.Severity.BREAKING);
    assertThat(diff.changes().get(1).severity())
        .isEqualTo(ContractDiff.Change.Severity.NON_BREAKING);
    assertThat(diff.changes().get(2).severity()).isEqualTo(ContractDiff.Change.Severity.COSMETIC);
    assertThat(diff.changes().get(0).location()).startsWith("/paths");
  }

  @Test
  void proto_unclassifiedWhenBufMissing() {
    CliBreakingChangeRunner stubRunner =
        new CliBreakingChangeRunner() {
          @Override
          public CliResult run(
              String oldContent,
              String newContent,
              String prefix,
              java.util.function.BiFunction<Path, Path, List<String>> argv,
              long timeoutSeconds) {
            return new CliResult(false, -1, null, null, "CLI 'buf' not available on PATH");
          }
        };
    ProtoBreakingChangeClassifier c = new ProtoBreakingChangeClassifier(stubRunner);
    ContractDiff diff = c.classify("syntax = 'proto3';", "syntax = 'proto3';");
    assertThat(diff.confidence()).isEqualTo(ContractDiff.Confidence.UNCLASSIFIED);
    assertThat(diff.summary()).contains("buf");
  }

  @Test
  void graphql_severityFromLine_mapsAllVariants() {
    assertThat(GraphQlBreakingChangeClassifier.severityFromLine("✖ Field removed"))
        .isEqualTo(ContractDiff.Change.Severity.BREAKING);
    assertThat(GraphQlBreakingChangeClassifier.severityFromLine("⚠ Field deprecated"))
        .isEqualTo(ContractDiff.Change.Severity.NON_BREAKING);
    assertThat(GraphQlBreakingChangeClassifier.severityFromLine("✓ Field added"))
        .isEqualTo(ContractDiff.Change.Severity.COSMETIC);
    assertThat(GraphQlBreakingChangeClassifier.severityFromLine("BREAKING: kind change"))
        .isEqualTo(ContractDiff.Change.Severity.BREAKING);
    assertThat(GraphQlBreakingChangeClassifier.severityFromLine("random text")).isNull();
  }

  @Test
  void breakingChangeService_routesByType_andUnclassifiedOnUnknown() {
    com.fasterxml.jackson.databind.ObjectMapper m =
        new com.fasterxml.jackson.databind.ObjectMapper();
    BreakingChangeService svc =
        new BreakingChangeService(List.of(new JsonSchemaBreakingChangeClassifier(m)));

    ContractDiff jsonDiff =
        svc.classify(
            ContractRegistry.ContractType.JSON_SCHEMA,
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}",
            "{\"type\":\"object\",\"properties\":{}}");
    assertThat(jsonDiff.confidence()).isEqualTo(ContractDiff.Confidence.DETERMINISTIC);
    assertThat(jsonDiff.hasBreaking()).isTrue();

    ContractDiff unknownDiff = svc.classify(ContractRegistry.ContractType.EVENT_SCHEMA, "x", "y");
    assertThat(unknownDiff.confidence()).isEqualTo(ContractDiff.Confidence.UNCLASSIFIED);
    assertThat(unknownDiff.summary()).contains("EVENT_SCHEMA");
  }
}
