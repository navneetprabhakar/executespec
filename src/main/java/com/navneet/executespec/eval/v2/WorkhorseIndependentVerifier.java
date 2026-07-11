package com.navneet.executespec.eval.v2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.engine.WorkerResult;

/**
 * Independent artifact verifier for the provider-free WORKHORSE repair corpus.
 *
 * <p>This deliberately does not call {@link WorkhorseEvalCase#validate}. It re-reads the persisted
 * workspace after a run and computes an artifact score separately from the worker's claimed
 * completion outcome. This keeps a completion claim from becoming its own proof.
 */
public final class WorkhorseIndependentVerifier {

  public Verification verify(String caseName, Path workspace, WorkerResult result) {
    if (caseName == null || workspace == null) {
      return new Verification(
          0,
          result != null && result.outcome() == WorkerResult.Outcome.COMPLETED,
          List.of("case name and workspace are required"));
    }
    return switch (caseName) {
      case "workhorse-add-function" -> verifyAdd(workspace, result);
      case "workhorse-multi-file-create" -> verifyCreate(workspace, result);
      case "workhorse-fix-bug" -> verifyFix(workspace, result);
      default -> new Verification(0, completed(result), List.of("unknown repair case"));
    };
  }

  private Verification verifyAdd(Path workspace, WorkerResult result) {
    String content = read(workspace.resolve("calc.py"));
    int score = 0;
    List<String> findings = new ArrayList<>();
    if (content.contains("def add(a, b)") && content.contains("return a + b")) score += 40;
    else findings.add("add() missing or changed");
    if (content.contains("def subtract(a, b)") && content.replaceAll("\\s", "").contains("a-b"))
      score += 60;
    else findings.add("subtract() missing or incorrect");
    return finish(score, result, findings);
  }

  private Verification verifyCreate(Path workspace, WorkerResult result) {
    String module = read(workspace.resolve("stringutils.py"));
    String test = read(workspace.resolve("test_stringutils.py"));
    int score = 0;
    List<String> findings = new ArrayList<>();
    if (module.contains("def reverse(")
        && (module.replaceAll("\\s", "").contains("[::-1]")
            || module.contains("reversed(")
            || module.contains("''.join"))) score += 60;
    else findings.add("reverse module missing or incorrect");
    if (test.contains("reverse(") && test.replaceAll("\\s", "").contains("cba")) score += 40;
    else findings.add("reverse test missing");
    return finish(score, result, findings);
  }

  private Verification verifyFix(Path workspace, WorkerResult result) {
    String code = read(workspace.resolve("numbers.py"));
    String test = read(workspace.resolve("test_numbers.py"));
    int score = 0;
    List<String> findings = new ArrayList<>();
    if (code.contains("def is_even(") && code.replaceAll("\\s", "").contains("n%2==0")) score += 70;
    else findings.add("is_even() remains incorrect or missing");
    if (test.contains("is_even(4)") && test.contains("is_even(3)")) score += 30;
    else findings.add("existing parity tests missing");
    return finish(score, result, findings);
  }

  private Verification finish(int score, WorkerResult result, List<String> findings) {
    return new Verification(score, completed(result), List.copyOf(findings));
  }

  private static boolean completed(WorkerResult result) {
    return result != null && result.outcome() == WorkerResult.Outcome.COMPLETED;
  }

  private static String read(Path path) {
    try {
      return Files.exists(path) ? Files.readString(path) : "";
    } catch (IOException e) {
      return "";
    }
  }

  public record Verification(
      int artifactScore, boolean workerClaimedCompletion, List<String> findings) {}
}
