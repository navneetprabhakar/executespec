package com.navneet.executespec.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ContextEvidenceAssemblerTest {

  @Test
  void selectsOptionalEvidenceDeterministicallyWithoutTouchingMandatoryFields() {
    var assembler = new ContextEvidenceAssembler(new ContextPlanner());
    var input =
        new ContextAgentInput(
            "context",
            "diff",
            List.of("pkg-a"),
            List.of("src/A.java"),
            List.of("worker report"),
            "review",
            "decision",
            "run-1",
            "goal");

    var selected = assembler.select(input);

    assertThat(selected.packageIndexes()).containsExactly("pkg-a");
    assertThat(selected.fileIndexes()).containsExactly("src/A.java");
    assertThat(selected.workerIndexes()).containsExactly("worker report");
  }

  @Test
  void removesDuplicateHitsBeforeBudgetingSoDistinctEvidenceGetsHeadroom() {
    var assembler = new ContextEvidenceAssembler(new ContextPlanner());
    String repeated = "same-package";
    String distinct = "distinct-package";
    var selected =
        assembler.select(
            new ContextAgentInput(
                "context",
                "diff",
                List.of(repeated, repeated, distinct),
                List.of(),
                List.of(),
                "review",
                "decision",
                "run-1",
                "goal"));

    assertThat(selected.packageIndexes()).containsExactly(repeated, distinct);
  }

  @Test
  void capsPlannerEstimateToBundleEntryLimit() {
    var assembler = new ContextEvidenceAssembler(new ContextPlanner());
    List<String> oversized =
        java.util.stream.IntStream.range(0, 10)
            .mapToObj(
                i -> "entry-" + i + "-" + "x".repeat(ContextEvidenceAssembler.MAX_ITEM_CHARS * 4))
            .toList();
    var selected =
        assembler.select(
            new ContextAgentInput(
                "context",
                "diff",
                oversized,
                List.of(),
                List.of(),
                "review",
                "decision",
                "run-1",
                "goal"));

    // Ten entries fit only when planning charges the same 1,000-char cap the bundle writer
    // applies. Before this guard, each 4,000-char hit consumed about 1,000 tokens and six won.
    assertThat(selected.packageIndexes())
        .containsExactlyElementsOf(
            oversized.stream().map(ContextEvidenceAssembler::canonicalize).toList());
  }

  @Test
  void deduplicatesAfterSerializationCapSoHeadroomMatchesPersistedBundle() {
    String sharedPrefix = "x".repeat(ContextEvidenceAssembler.MAX_ITEM_CHARS);
    String first = sharedPrefix + "-first-payload";
    String second = sharedPrefix + "-second-payload";

    var assembler = new ContextEvidenceAssembler(new ContextPlanner());
    var selected =
        assembler.select(
            new ContextAgentInput(
                "context",
                "diff",
                List.of(first, second),
                List.of(),
                List.of(),
                "review",
                "decision",
                "run-1",
                "goal"));

    assertThat(selected.packageIndexes())
        .containsExactly(ContextEvidenceAssembler.canonicalize(first));
  }

  @Test
  void assignsReloadableProvenanceAndTaskPhasePerContextLane() {
    var assembler = new ContextEvidenceAssembler(new ContextPlanner());
    var items =
        assembler.itemsFor(
            new ContextAgentInput(
                "context",
                "diff",
                List.of("pkg-a"),
                List.of("src/A.java"),
                List.of("worker report"),
                "review",
                "decision",
                "run-1",
                "goal"));

    assertThat(items)
        .extracting(ContextPlanner.Item::taskPhase)
        .containsExactly("ORIENTATION", "WORKING_SET", "TASK_EVIDENCE");
    assertThat(items)
        .extracting(ContextPlanner.Item::provenance)
        .containsExactly(
            "context-agent:package-index",
            "context-agent:file-index",
            "context-agent:worker-report");
    assertThat(items)
        .extracting(ContextPlanner.Item::reloadHandle)
        .containsExactly("package:0", "file:0", "worker:0");
    assertThat(items.get(1).path()).isEqualTo("src/A.java");
    assertThat(items.get(1).range()).isEqualTo("full");
  }
}
