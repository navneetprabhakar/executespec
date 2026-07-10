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
}
