package com.navneet.executespec.eval.v2.cases;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.eval.v2.TierEvalCase.ValidationVerdict;
import com.navneet.executespec.eval.v2.WorkhorseEvalCase;

/**
 * Production-shaped WORKHORSE-tier agentic eval cases. Each drives the real {@link
 * com.navneet.executespec.engine.WorkerAgent} multi-turn loop against a seeded scratch workspace
 * and grades the file-system result.
 *
 * <p>Three cases span the worker's bread-and-butter:
 *
 * <ol>
 *   <li><b>add-function</b> — edit an existing file to add a function (tests targeted editing
 *       without clobbering existing code)
 *   <li><b>multi-file-create</b> — create a module + a passing test (tests greenfield multi-file
 *       generation)
 *   <li><b>fix-bug</b> — repair a deliberately broken function so its existing test would pass
 *       (tests read→diagnose→patch)
 * </ol>
 */
public final class WorkhorseCases {

  private WorkhorseCases() {}

  public static List<WorkhorseEvalCase> all() {
    return List.of(addFunctionCase(), multiFileCreateCase(), fixBugCase());
  }

  // Case 1: add a function to an existing file
  private static WorkhorseEvalCase addFunctionCase() {
    return new WorkhorseEvalCase() {
      @Override
      public String name() {
        return "workhorse-add-function";
      }

      @Override
      public String taskPrompt() {
        return "The file calc.py contains an add(a, b) function. Add a second function "
            + "subtract(a, b) that returns a - b, directly below add. Keep add unchanged. "
            + "Then call completeTask.";
      }

      @Override
      public void seedWorkspace(Path dir) throws Exception {
        Files.writeString(
            dir.resolve("calc.py"), "def add(a, b):\n    return a + b\n", StandardCharsets.UTF_8);
      }

      @Override
      public ValidationVerdict validate(Path dir, WorkerResult result) {
        String content = readOrEmpty(dir.resolve("calc.py"));
        int score = 0;
        java.util.List<String> notes = new java.util.ArrayList<>();
        if (content.contains("def add(a, b)")) {
          score += 30;
        } else {
          notes.add("add() was clobbered");
        }
        if (content.contains("def subtract(a, b)")) {
          score += 40;
        } else {
          notes.add("subtract() missing");
        }
        if (content.replaceAll("\\s", "").contains("a-b")) {
          score += 20;
        } else {
          notes.add("subtract body wrong");
        }
        if (result.outcome() == WorkerResult.Outcome.COMPLETED) {
          score += 10;
        } else {
          notes.add("outcome=" + result.outcome());
        }
        return new ValidationVerdict(
            score, notes.isEmpty() ? "clean add-function" : String.join("; ", notes));
      }
    };
  }

  // Case 2: create a module + a passing test
  private static WorkhorseEvalCase multiFileCreateCase() {
    return new WorkhorseEvalCase() {
      @Override
      public String name() {
        return "workhorse-multi-file-create";
      }

      @Override
      public String taskPrompt() {
        return "Create a Python module stringutils.py with a function reverse(s) that returns the "
            + "reversed string, and a test file test_stringutils.py with a pytest test asserting "
            + "reverse('abc') == 'cba'. Then call completeTask.";
      }

      @Override
      public void seedWorkspace(Path dir) {
        // greenfield — nothing to seed
      }

      @Override
      public ValidationVerdict validate(Path dir, WorkerResult result) {
        String mod = readOrEmpty(dir.resolve("stringutils.py"));
        String test = readOrEmpty(dir.resolve("test_stringutils.py"));
        int score = 0;
        java.util.List<String> notes = new java.util.ArrayList<>();
        if (mod.contains("def reverse(")) {
          score += 30;
        } else {
          notes.add("reverse() missing");
        }
        // Accept any of the idiomatic reversals.
        String modNs = mod.replaceAll("\\s", "");
        if (modNs.contains("[::-1]") || modNs.contains("reversed(") || modNs.contains("''.join")) {
          score += 25;
        } else {
          notes.add("reverse impl not recognized");
        }
        if (!test.isEmpty() && test.contains("reverse(")) {
          score += 25;
        } else {
          notes.add("test missing/empty");
        }
        if (test.replaceAll("\\s", "").contains("'cba'")
            || test.replaceAll("\\s", "").contains("\"cba\"")) {
          score += 10;
        } else {
          notes.add("test assertion missing");
        }
        if (result.outcome() == WorkerResult.Outcome.COMPLETED) {
          score += 10;
        } else {
          notes.add("outcome=" + result.outcome());
        }
        return new ValidationVerdict(
            score, notes.isEmpty() ? "clean multi-file create" : String.join("; ", notes));
      }
    };
  }

  // Case 3: fix a deliberately broken function
  private static WorkhorseEvalCase fixBugCase() {
    return new WorkhorseEvalCase() {
      @Override
      public String name() {
        return "workhorse-fix-bug";
      }

      @Override
      public String taskPrompt() {
        return "The function is_even(n) in numbers.py is buggy — it returns the wrong answer. There"
            + " is a test test_numbers.py that asserts is_even(4) is True and is_even(3) is"
            + " False. Read the code, find the bug, and fix is_even so the test would pass."
            + " Keep the function signature. Then call completeTask.";
      }

      @Override
      public void seedWorkspace(Path dir) throws Exception {
        // Bug: uses == 1 instead of == 0, so the result is inverted.
        Files.writeString(
            dir.resolve("numbers.py"),
            "def is_even(n):\n    return n % 2 == 1\n",
            StandardCharsets.UTF_8);
        Files.writeString(
            dir.resolve("test_numbers.py"),
            "from numbers import is_even\n\n"
                + "def test_even():\n    assert is_even(4) is True\n\n"
                + "def test_odd():\n    assert is_even(3) is False\n",
            StandardCharsets.UTF_8);
      }

      @Override
      public ValidationVerdict validate(Path dir, WorkerResult result) {
        String content = readOrEmpty(dir.resolve("numbers.py")).replaceAll("\\s", "");
        int score = 0;
        java.util.List<String> notes = new java.util.ArrayList<>();
        // content is whitespace-stripped, so the signature check must be stripped too
        // ("def is_even(n)" with spaces can never match stripped content — that was a
        // validator bug that under-scored every correct fix by 20 pts).
        if (content.contains("defis_even(n)")) {
          score += 20;
        } else {
          notes.add("signature changed");
        }
        // Correct fix: n % 2 == 0 (and NOT the buggy == 1).
        if (content.contains("n%2==0")) {
          score += 60;
        } else if (content.contains("n%2==1")) {
          notes.add("bug NOT fixed (still == 1)");
        } else if (content.contains("n%2!=1") || content.contains("not(n%2)")) {
          score += 50; // alternative correct forms
          notes.add("fixed via alternative form");
        } else {
          notes.add("unrecognized impl");
        }
        if (result.outcome() == WorkerResult.Outcome.COMPLETED) {
          score += 20;
        } else {
          notes.add("outcome=" + result.outcome());
        }
        return new ValidationVerdict(
            Math.min(100, score), notes.isEmpty() ? "bug fixed cleanly" : String.join("; ", notes));
      }
    };
  }

  private static String readOrEmpty(Path p) {
    try {
      return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
    } catch (Exception e) {
      return "";
    }
  }
}
