package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * RR-1599 — the executor's waste-guard and delivery-evidence signatures must classify a tool-log
 * entry by capability, not by the literal name prefix.
 *
 * <p>Two defects are pinned here. (1) Commands issued under an alias ({@code executeCommand},
 * {@code build}, …) were never grouped, so {@code max-repeated-failures} could not fire for them.
 * (2) {@code mutationPathSignature} only understood the legacy {@code "path": "…"} argument form,
 * but the live tool log hashes arguments and carries the path in a {@code [target=…]} marker — so
 * edit-churn evidence returned null for every current-format entry.
 */
class WorkerToolLogEvidenceTest {

  // ---- repeated command failure grouping ------------------------------------------------

  @Test
  void everyCommandAliasIsGroupedByItsCallPrefix() {
    for (String name :
        List.of(
            "shell",
            "bash",
            "executeCommand",
            "runCommand",
            "terminalCommand",
            "background_process",
            "buildProject",
            "build",
            "runBuild",
            "runTests")) {
      String first = name + "(sig=aaaa1111) -> ERR: exit 1 boom";
      String second = name + "(sig=aaaa1111) -> ERR: exit 1 different stderr";
      assertThat(WorkerToolLogEvidence.repeatedCommandFailureSignature(first))
          .as("alias '%s' must produce a command signature", name)
          .isEqualTo(name + "(sig=aaaa1111)");
      assertThat(WorkerToolLogEvidence.repeatedCommandFailureSignature(second))
          .as("alias '%s' must group across differing stderr", name)
          .isEqualTo(WorkerToolLogEvidence.repeatedCommandFailureSignature(first));
    }
  }

  @Test
  void nonCommandAndNonCallShapedEntriesProduceNoCommandSignature() {
    assertThat(WorkerToolLogEvidence.repeatedCommandFailureSignature("writeFile(sig=ab) -> ERR"))
        .isNull();
    assertThat(WorkerToolLogEvidence.repeatedCommandFailureSignature("readFile(sig=ab) -> ERR"))
        .isNull();
    assertThat(WorkerToolLogEvidence.repeatedCommandFailureSignature("AUTO_VERIFY -> 1 failing"))
        .isNull();
    assertThat(WorkerToolLogEvidence.repeatedCommandFailureSignature("completeTask -> REFUSED (x)"))
        .isNull();
  }

  @Test
  void anUnclassifiedCallShapedEntryIsStillGrouped() {
    // Fail-closed: an unknown tool must not escape the repeated-failure waste guard.
    assertThat(
            WorkerToolLogEvidence.repeatedCommandFailureSignature("someNewTool(sig=ab) -> ERR: x"))
        .isEqualTo("someNewTool(sig=ab)");
  }

  // ---- mutation path signature ----------------------------------------------------------

  @Test
  void currentLogFormatTargetMarkerYieldsThePath() {
    assertThat(
            WorkerToolLogEvidence.mutationPathSignature(
                "writeFile(sig=ab12cd34) [target=src/app/Main.java] -> OK"))
        .isEqualTo("src/app/Main.java");
  }

  @Test
  void everyFileMutatingAliasYieldsAPath() {
    for (String name :
        List.of(
            "writeFile",
            "write_file",
            "editFile",
            "edit_file",
            "multiEdit",
            "applyPatch",
            "apply_patch",
            "ast_edit",
            "move",
            "delete")) {
      assertThat(
              WorkerToolLogEvidence.mutationPathSignature(name + "(sig=ab) [target=a/b.py] -> OK"))
          .as("alias '%s' must be attributable to a product path", name)
          .isEqualTo("a/b.py");
    }
  }

  @Test
  void legacyArgumentFormsStillResolve() {
    assertThat(
            WorkerToolLogEvidence.mutationPathSignature(
                "editFile({\"path\":\"src/App.tsx\",\"old\":\"a\",\"new\":\"b\"})"))
        .isEqualTo("src/App.tsx");
    assertThat(
            WorkerToolLogEvidence.mutationPathSignature("writeFile(path=src/App.tsx, content=x)"))
        .isEqualTo("src/App.tsx");
  }

  @Test
  void nonMutatingAndUnclassifiedEntriesProduceNoPath() {
    assertThat(
            WorkerToolLogEvidence.mutationPathSignature("readFile(sig=ab) [target=a/b.py] -> OK"))
        .isNull();
    assertThat(WorkerToolLogEvidence.mutationPathSignature("shell(sig=ab) -> OK")).isNull();
    assertThat(
            WorkerToolLogEvidence.mutationPathSignature("someNewTool(sig=ab) [target=a/b] -> OK"))
        .isNull();
    assertThat(WorkerToolLogEvidence.mutationPathSignature("TERMINAL_VERIFIED_PASS -> evidence"))
        .isNull();
  }

  // ---- file-write counting ---------------------------------------------------------------

  @Test
  void countFileWritesCoversEveryMutatingAliasAndIgnoresTheRest() {
    List<String> log =
        List.of(
            "writeFile(sig=1) [target=a] -> OK",
            "applyPatch(sig=2) [target=b] -> OK",
            "ast_edit(sig=3) [target=c] -> OK",
            "move(sig=4) [target=d] -> OK",
            "delete(sig=5) [target=e] -> OK",
            "readFile(sig=6) -> OK",
            "shell(sig=7) -> OK",
            "someNewTool(sig=8) -> OK",
            "AUTO_VERIFY -> ok");
    assertThat(WorkerToolLogEvidence.countFileWrites(log)).isEqualTo(5);
  }

  @Test
  void countFileWritesHandlesLegacyAndEmptyLogs() {
    assertThat(WorkerToolLogEvidence.countFileWrites(null)).isZero();
    assertThat(WorkerToolLogEvidence.countFileWrites(List.of())).isZero();
    assertThat(WorkerToolLogEvidence.countFileWrites(List.of("writeFile(...)", "editFile(...)")))
        .isEqualTo(2);
  }

  // RR-261 — a rejected edit is not delivered code. Failed / refused mutation entries must not
  // count.
  @Test
  void countFileWritesExcludesFailedAndRefusedMutations() {
    List<String> log =
        List.of(
            "writeFile(sig=1) [target=a] -> ERR: stale hunk",
            "editFile(sig=2) [target=b] -> ERR",
            "writeFile(sig=3) [target=c] → REFUSED (generated secret)",
            "applyPatch(sig=4) [target=d] -> OK");
    // Only the single OK mutation counts; three failed/refused attempts do not.
    assertThat(WorkerToolLogEvidence.countFileWrites(log)).isEqualTo(1);
  }

  // RR-261 — a worker that attempted ONLY rejected edits produced zero delivered files, so it
  // cannot
  // be laundered into a soft completion.
  @Test
  void countFileWritesIsZeroWhenEveryEditWasRejected() {
    List<String> log =
        List.of(
            "writeFile(sig=1) [target=a] -> ERR",
            "editFile(sig=2) [target=b] → REFUSED (no recent runTests)");
    assertThat(WorkerToolLogEvidence.countFileWrites(log)).isZero();
  }
}
