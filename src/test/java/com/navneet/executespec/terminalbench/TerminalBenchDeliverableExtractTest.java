package com.navneet.executespec.terminalbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * RL-2026-06 — pins {@link TerminalBenchRunService#extractDeliverablePaths(String)}, the
 * required-deliverable extractor that feeds the worker's deterministic gate (#2). Conservative by
 * design: only {@code /app/} paths with a file extension, deduped in first-seen order. The gate
 * only refuses on a MISSING path, so over-matching an existing input file is harmless; these tests
 * pin the common shapes seen across the Terminal-Bench corpus.
 */
class TerminalBenchDeliverableExtractTest {

  @Test
  void extracts_single_named_file() {
    // cancel-async-tasks shape.
    List<String> paths =
        TerminalBenchRunService.extractDeliverablePaths(
            "Put the function in a file called `/app/run.py` so it can be imported.");
    assertEquals(List.of("/app/run.py"), paths);
  }

  @Test
  void extracts_multiple_and_dedupes_in_order() {
    // openssl-selfsigned-cert shape: same path named twice, several distinct deliverables.
    String instruction =
        "Save it as /app/ssl/server.key with permissions 600. Save the cert as /app/ssl/server.crt."
            + " Combine them into /app/ssl/server.pem. Re-read /app/ssl/server.key to verify.";
    List<String> paths = TerminalBenchRunService.extractDeliverablePaths(instruction);
    assertEquals(
        List.of("/app/ssl/server.key", "/app/ssl/server.crt", "/app/ssl/server.pem"),
        paths,
        "distinct /app paths in first-seen order, duplicate server.key collapsed");
  }

  @Test
  void ignores_non_app_and_extensionless_paths() {
    // /tests and /usr are not deliverables; /app/sim has no extension (skipped); /app/gates.txt is.
    String instruction =
        "The simulator /app/sim reads /app/gates.txt. Tests live in /tests/test.sh. Do not touch"
            + " /usr/bin/python.";
    List<String> paths = TerminalBenchRunService.extractDeliverablePaths(instruction);
    assertEquals(List.of("/app/gates.txt"), paths);
  }

  @Test
  void empty_when_no_app_path() {
    List<String> paths =
        TerminalBenchRunService.extractDeliverablePaths(
            "Print the answer to standard output. No files required.");
    assertTrue(paths.isEmpty());
  }

  @Test
  void empty_on_null_or_blank() {
    assertTrue(TerminalBenchRunService.extractDeliverablePaths(null).isEmpty());
    assertTrue(TerminalBenchRunService.extractDeliverablePaths("   ").isEmpty());
  }
}
