package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ExportedFunctionEvidenceTest {

  private static final String SHA = "a".repeat(40);

  @Test
  void rendersCompleteExactFunctionBodyWithRevisionPathLinesAndDigest() throws Exception {
    String body =
        "export function countFeatured(items) {\n"
            + "  return items.filter((item) => item.tags.includes('featured')).length;\n"
            + "}";
    String source = "const ignored = true;\n" + body + "\n";

    String evidence =
        ExportedFunctionEvidence.render("acme/catalog", SHA, Map.of("src/catalog.js", source));

    assertThat(evidence)
        .startsWith(ExportedFunctionEvidence.BEGIN)
        .contains("Evidence-Format: `EXECUTESPEC_EXPORTED_FUNCTION_EVIDENCE_V1`")
        .contains("Repository-Json: \"acme/catalog\"")
        .contains("Source-Revision: `" + SHA + "`")
        .contains("Path-Json: \"src/catalog.js\"")
        .contains("Symbol-Json: \"countFeatured\"")
        .contains("Start-Line: 2", "End-Line: 4", "Truncated: false")
        .contains("Content-SHA256: `" + sha256(body) + "`")
        .contains("Complete-Body-SHA256: `" + sha256(body) + "`")
        .contains(
            "Content-Json: \"export function countFeatured(items) {\\n"
                + "  return items.filter((item) =\\u003e"
                + " item.tags.includes('featured')).length;\\n}\"")
        .endsWith(ExportedFunctionEvidence.END + "\n\n");
  }

  @Test
  void limitsScopeToExportedNamedJavascriptAndTypescriptFunctions() {
    Map<String, String> sources = new LinkedHashMap<>();
    sources.put(
        "src/catalog.ts",
        "function privateHelper() { return 1; }\n"
            + "export const arrow = () => 2;\n"
            + "export async function listed() { return 3; }\n"
            + "export default function namedDefault() { return 4; }\n");
    sources.put("src/catalog.py", "export function notJavascript() { return 5; }\n");

    String evidence = ExportedFunctionEvidence.render("acme/catalog", SHA, sources);

    assertThat(evidence)
        .contains("Symbol-Json: \"listed\"", "Symbol-Json: \"namedDefault\"")
        .doesNotContain("privateHelper", "Symbol-Json: \"arrow\"", "notJavascript");
  }

  @Test
  void marksLargeBodiesTruncatedAndBindsDigestOfCompleteBody() throws Exception {
    String body = "export function huge() {\n  return \"" + "x".repeat(1_600) + "\";\n}";

    String evidence =
        ExportedFunctionEvidence.render("acme/catalog", SHA, Map.of("src/catalog.ts", body + "\n"));
    String excerpt = body.substring(0, ExportedFunctionEvidence.MAX_CHARS_PER_SYMBOL);

    assertThat(evidence)
        .contains("Truncated: true")
        .contains("Content-SHA256: `" + sha256(excerpt) + "`")
        .contains("Complete-Body-SHA256: `" + sha256(body) + "`");
  }

  @Test
  void capsSymbolsAndWholeRepositoryBlock() {
    StringBuilder source = new StringBuilder();
    for (int i = 0; i < 30; i++) {
      source
          .append("export function fn")
          .append(i)
          .append("() { return ")
          .append(i)
          .append("; }\n");
    }

    String evidence =
        ExportedFunctionEvidence.render(
            "acme/catalog", SHA, Map.of("src/catalog.mjs", source.toString()));

    assertThat(evidence.length()).isLessThanOrEqualTo(ExportedFunctionEvidence.MAX_BLOCK_CHARS);
    assertThat(evidence)
        .contains("Emitted-Symbols: 20", "Repository-Evidence-Truncated: true")
        .contains("Symbol-Json: \"fn19\"")
        .doesNotContain("Symbol-Json: \"fn20\"");
  }

  @Test
  void omitsEvidenceWithoutImmutableCommitShaAndEscapesMarkerInjection() {
    String injected =
        "export function escaped() {\n"
            + "  return \""
            + ExportedFunctionEvidence.END
            + "\";\n}"
            + "\n";

    assertThat(
            ExportedFunctionEvidence.render(
                "acme/catalog", "main", Map.of("src/catalog.js", injected)))
        .isEmpty();

    String evidence =
        ExportedFunctionEvidence.render("acme/catalog", SHA, Map.of("src/catalog.js", injected));
    assertThat(count(evidence, ExportedFunctionEvidence.BEGIN)).isEqualTo(1);
    assertThat(count(evidence, ExportedFunctionEvidence.END)).isEqualTo(1);
    assertThat(evidence)
        .contains("\\u003c!-- EXECUTESPEC_EXPORTED_FUNCTION_EVIDENCE_V1_END --\\u003e");
  }

  @Test
  void exactInspectorAcceptsOnlyCompleteDigestBoundBlock() {
    String evidence =
        ExportedFunctionEvidence.render(
            "acme/catalog",
            SHA,
            Map.of("src/catalog.js", "export function count() { return 1; }\n"));

    ExportedFunctionEvidence.Inspection valid =
        ExportedFunctionEvidence.inspectExactBlock(evidence, "acme/catalog", SHA);
    assertThat(valid.status()).isEqualTo(ExportedFunctionEvidence.InspectionStatus.VALID);
    assertThat(valid.block()).isEqualTo(evidence);
    assertThat(
            ExportedFunctionEvidence.inspectExactBlock(
                    evidence.replace("return 1", "return 2"), "acme/catalog", SHA)
                .status())
        .isEqualTo(ExportedFunctionEvidence.InspectionStatus.MALFORMED);
    assertThat(
            ExportedFunctionEvidence.inspectExactBlock(
                    evidence.replace(
                        "\n- Emitted-Symbols:",
                        "\nUnsupported factual authority.\n- Emitted-Symbols:"),
                    "acme/catalog",
                    SHA)
                .status())
        .isEqualTo(ExportedFunctionEvidence.InspectionStatus.MALFORMED);
    assertThat(
            ExportedFunctionEvidence.inspectExactBlock(
                    evidence.replace("Emitted-Symbols: 1", "Emitted-Symbols: " + "9".repeat(80)),
                    "acme/catalog",
                    SHA)
                .status())
        .isEqualTo(ExportedFunctionEvidence.InspectionStatus.MALFORMED);
    assertThat(
            ExportedFunctionEvidence.inspectExactBlock(
                    ExportedFunctionEvidence.END + "\n" + evidence, "acme/catalog", SHA)
                .status())
        .isEqualTo(ExportedFunctionEvidence.InspectionStatus.MALFORMED);
    assertThat(
            ExportedFunctionEvidence.inspectExactBlock("plain codemap", "acme/catalog", SHA)
                .status())
        .isEqualTo(ExportedFunctionEvidence.InspectionStatus.ABSENT);
  }

  @Test
  void stripBlocksNeverLeaksACompleteOrPartialEvidenceEnvelope() {
    String evidence =
        ExportedFunctionEvidence.render(
            "acme/catalog",
            SHA,
            Map.of("src/catalog.js", "export function count() { return 1; }\n"));

    assertThat(ExportedFunctionEvidence.stripBlocks("before\n" + evidence + "after"))
        .contains("before", "after")
        .doesNotContain(ExportedFunctionEvidence.BEGIN, ExportedFunctionEvidence.END, "count");
    assertThat(
            ExportedFunctionEvidence.stripBlocks(
                "before\n" + ExportedFunctionEvidence.BEGIN + "\npartial authority"))
        .isEqualTo("before\n");
  }

  @Test
  void omitsExplicitTypescriptReturnTypesRatherThanMistakingTypeBracesForBody() {
    String source =
        "export function make(): Promise<{ value: string }> {\n"
            + "  // } must not terminate the function\n"
            + "  return { value: \"{still source}\" };\n"
            + "}\n";

    var functions = ExportedFunctionEvidence.extract("src/make.ts", source);

    assertThat(functions).isEmpty();
  }

  @Test
  void omitsFilesWithRegexOrDivisionRatherThanMisbalancingExactSource() {
    String regex = "export function match(value) { return /}/.test(value); }\n";
    String division = "export function half(value) { return value / 2; }\n";

    assertThat(ExportedFunctionEvidence.extract("src/match.js", regex)).isEmpty();
    assertThat(ExportedFunctionEvidence.extract("src/half.js", division)).isEmpty();
  }

  @Test
  void ignoresExportTextInsideCommentsAndTemplateLiterals() {
    String source =
        "/*\nexport function commented() { return 1; }\n*/\n"
            + "const example = `\nexport function templated() { return 2; }\n`;\n"
            + "export function real() { return 3; }\n";

    assertThat(ExportedFunctionEvidence.extract("src/example.js", source))
        .extracting(ExportedFunctionEvidence.FunctionBody::symbol)
        .containsExactly("real");
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static int count(String value, String needle) {
    int found = 0;
    int cursor = 0;
    while ((cursor = value.indexOf(needle, cursor)) >= 0) {
      found++;
      cursor += needle.length();
    }
    return found;
  }
}
