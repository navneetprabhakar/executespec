package com.navneet.executespec.integration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Periodically emits a progress line as a {@code LOG} SSE event during long pipeline phases, so the
 * live log keeps moving while the worker sits inside a multi-minute LLM call and no other event is
 * flowing. Doubles as a heartbeat that keeps the SSE stream warm on proxies with short idle
 * timeouts.
 *
 * <p>Call {@link #start(String, String)} when a phase begins (PLANNING / WORKING / REVIEWING /
 * VALIDATING), {@link #stop(String)} when it ends. Safe to start/stop repeatedly per run; each call
 * replaces the previous schedule so we never double-emit.
 *
 * <h2>Why the banks are large</h2>
 *
 * A tick fires every {@link #INTERVAL_SEC} seconds. The original banks held 3-5 strings picked at
 * RANDOM, so a ten-minute planning phase emitted ~40 lines drawn from three sentences and the log
 * visibly looped (dev run 350fd2fc: 25 log events, 3 distinct strings). Selection is now
 * deterministic rotation over a bank of 100+ lines, offset per run, with elapsed time appended — so
 * consecutive lines are never byte-identical and a phase reads as progressing.
 *
 * <h2>Honesty rule for anything added here</h2>
 *
 * This messenger runs on a TIMER. It does not observe the pipeline's internal sub-steps, so a line
 * may state only what is definitionally true of the phase, what the customer should expect, or how
 * the product behaves. It must never narrate a specific step it cannot see actually happening —
 * "checking the build", "picking tools", "writing the tests now". A real step emits its own event;
 * inventing one here would put a false claim in the run's log.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PerkyMessenger {

  private static final long INITIAL_DELAY_SEC = 12;
  private static final long INTERVAL_SEC = 15;

  private record Phase(String headline, String[] context) {}

  private static final Phase PLANNING =
      new Phase(
          "Planning",
          new String[] {
            "The model is drafting the task breakdown. No code has been written yet.",
            "Nothing is billed until you approve the plan.",
            "A longer or more detailed spec takes longer here.",
            "The finished plan lists each step and the files it expects to touch.",
            "Steps will appear the moment the plan is ready.",
            "You approve the plan before any code is generated.",
            "The plan gate is where you can reject or ask for changes.",
            "The spec is classified first, which decides how finely it gets broken up.",
            "A small spec becomes a handful of steps; a large one is split into waves.",
            "Every requirement in your spec has to be owned by some step before the plan is valid.",
            "A plan that contradicts itself is rejected before it reaches you.",
            "The planner is one model call, so it can sit quiet for a few minutes.",
            "Approving charges credits; submitting does not.",
            "The plan is checked for steps that would fight each other over the same file.",
            "Interface agreement between steps is checked before you see the plan.",
            "You can cancel now at no cost.",
            "Steps are ordered so a consumer never runs before the thing it consumes.",
            "A plan missing part of your spec fails rather than shipping partial work.",
            "Estimated cost and step count appear alongside the plan.",
            "The model chosen for planning may differ from the one that writes the code.",
            "Naming and vocabulary are aligned across steps so the pieces fit together.",
            "The plan is the contract for everything that follows.",
            "Large specs get one retry to come back within the step ceiling.",
            "A plan is never silently truncated to fit — it is rejected instead.",
            "Test coverage is planned with the code, not bolted on at the end.",
            "Setup and acceptance steps are preserved even when a plan is condensed.",
            "Nothing is pushed anywhere without your approval first.",
            "You will see the whole plan before a single file is written.",
            "Reviewing the plan carefully now is the cheapest place to catch a misunderstanding.",
            "This phase ends with the plan gate, where the run waits for you.",
          });

  private static final Phase WORKING =
      new Phase(
          "Building",
          new String[] {
            "Steps run in order. Each one writes files, then its build is checked.",
            "File changes appear here as they are written.",
            "A step whose build fails is retried before the run moves on.",
            "Tests are written in the same step as the code they cover.",
            "Progress per step is visible in the Steps tab.",
            "A step touching many files legitimately takes a while.",
            "The worker keeps the full history of what it has already done.",
            "Each step reports what it changed when it finishes.",
            "Build output is captured and kept with the run.",
            "A step that stops making progress is nudged, then stopped.",
            "Spend is tracked continuously against the run's ceiling.",
            "Generated code is scanned for credentials before it is written to disk.",
            "The build is checked per step, not only at the end.",
            "Later steps can see what earlier steps built.",
            "Files are written inside an isolated workspace for this run.",
            "You can stop the run at any point from here.",
            "A step is not finished until it says so and its verification agrees.",
            "The run's cost so far updates as each call completes.",
            "Repeatedly editing the same file without a passing check is caught and stopped.",
            "Both a success case and an error case are expected for anything testable.",
            "A step that fails does not silently pass the run.",
            "Work is committed to a branch, never straight to your default branch.",
            "The number of steps completed is shown at the top of this page.",
            "A long-running step is normal; a stuck one is detected and terminated.",
            "The workspace is a real checkout, so ordinary build tooling applies.",
            "Nothing is published while the run is still building.",
            "Every model call is recorded with its cost and token counts.",
            "Test files land in the conventional place for the stack.",
            "A step can read the repository to match the conventions already there.",
            "Steps that could conflict over a file were separated during planning.",
            "If a step exhausts its budget the run stops rather than spending more.",
            "The run keeps going after a recoverable failure; it stops on an unrecoverable one.",
            "The final code is what the review and the score will be measured against.",
            "Checkpoints are saved as the run proceeds, so a restart does not lose the work.",
            "What happens after the last step: review, then validation, then the report.",
          });

  private static final Phase REVIEWING =
      new Phase(
          "Reviewing",
          new String[] {
            "A reviewer is reading the finished code and checking how the pieces connect.",
            "The review is read-only — it diagnoses problems rather than editing them.",
            "Anything the reviewer finds becomes an item in the final report.",
            "The review looks at whether the parts actually wire together, not just compile.",
            "The reviewer sees your original spec alongside the code.",
            "Every requirement from the plan is available to the reviewer.",
            "The review contributes to the score but does not decide it alone.",
            "Unresolved review findings cap the final score.",
            "The reviewer can build the code to gather evidence, but cannot change it.",
            "A repair, if one is needed, is a separate checkpointed step with its own gates.",
            "The review reads the code as delivered, not as intended.",
            "Findings are graded by severity, so you can see what actually matters.",
            "The reviewer checks the request-handling and validation paths specifically.",
            "The review runs against one stable version of the workspace.",
            "A review that cannot complete is reported as failed, not as clean.",
            "This is the last look before evidence is collected.",
            "The reviewer's verdict is shown as its own line in the report.",
            "Critical findings are separated from advisory ones.",
            "Reading the whole change takes longer on a large run.",
            "What the reviewer proves, and what it could not, are both recorded.",
          });

  private static final Phase VALIDATING =
      new Phase(
          "Validating",
          new String[] {
            "Collecting build, test and review evidence into the final report.",
            "The score is assembled only from evidence that was actually measured.",
            "A signal that could not be measured is excluded, not assumed to pass.",
            "Test results are recorded with proof of when they ran.",
            "The report shows which claims are deterministic and which are model judgement.",
            "Build, test and review outcomes are reconciled against each other.",
            "A suite that printed 'passed' but exited non-zero is not counted as green.",
            "The evidence ledger in the report shows where each number came from.",
            "Delivering no files means a score of zero, whatever else passed.",
            "Unverified requirements cap the score below passing.",
            "Publication is blocked unless the evidence supports it.",
            "The report separates what was proven from what remains unproven.",
            "Cost and token totals are reconciled before the run is closed.",
            "Independent tests, where they ran, count for more than self-written ones.",
            "A failing gate here stops the run from being presented as clean.",
            "The final status reflects the weakest verified link, not the strongest.",
            "The report is durable — it will still be here when you come back.",
            "Screens, if this run produced UI, render separately and never affect the score.",
            "Almost done: the report is being written.",
            "Terminal status and the score are published together.",
          });

  private final SseBroadcaster broadcaster;
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(
          2,
          r -> {
            Thread t = new Thread(r, "v2-perky-messenger");
            t.setDaemon(true);
            return t;
          });
  private final Map<String, ScheduledFuture<?>> active = new ConcurrentHashMap<>();

  /** v2.5.12 — graceful shutdown of the scheduler on SIGTERM. */
  @jakarta.annotation.PreDestroy
  public void shutdownScheduler() {
    active.values().forEach(f -> f.cancel(false));
    active.clear();
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      scheduler.shutdownNow();
    }
  }

  public void start(String runId, String phase) {
    stop(runId);
    Phase bank = bankFor(phase);
    long startedAtNanos = System.nanoTime();
    // Deterministic rotation, offset by run id so two concurrent runs do not read out the same
    // sequence in lockstep. Random selection over a small bank is what made the log look stuck.
    AtomicInteger tick = new AtomicInteger(startOffset(runId, bank.context().length));
    ScheduledFuture<?> f =
        scheduler.scheduleAtFixedRate(
            () -> {
              try {
                int i = tick.getAndIncrement();
                long elapsedSec = (System.nanoTime() - startedAtNanos) / 1_000_000_000L;
                String context = bank.context()[Math.floorMod(i, bank.context().length)];
                String msg =
                    bank.headline() + " — " + formatElapsed(elapsedSec) + " elapsed. " + context;
                broadcaster.emit(runId, RunEvent.log(runId, null, msg));
              } catch (Exception e) {
                log.debug("PerkyMessenger emit failed for run={}: {}", runId, e.getMessage());
              }
            },
            INITIAL_DELAY_SEC,
            INTERVAL_SEC,
            TimeUnit.SECONDS);
    active.put(runId, f);
  }

  public void stop(String runId) {
    ScheduledFuture<?> f = active.remove(runId);
    if (f != null) f.cancel(false);
  }

  private static int startOffset(String runId, int bankSize) {
    if (runId == null || bankSize <= 0) return 0;
    return Math.floorMod(runId.hashCode(), bankSize);
  }

  /** Compact human elapsed: {@code 45s}, {@code 3m 05s}, {@code 1h 12m}. */
  static String formatElapsed(long totalSeconds) {
    if (totalSeconds < 0) return "0s";
    if (totalSeconds < 60) return totalSeconds + "s";
    long minutes = totalSeconds / 60;
    if (minutes < 60) return String.format("%dm %02ds", minutes, totalSeconds % 60);
    return String.format("%dh %02dm", minutes / 60, minutes % 60);
  }

  private static Phase bankFor(String phase) {
    if (phase == null) return WORKING;
    return switch (phase.toUpperCase()) {
      case "PLANNING" -> PLANNING;
      case "REVIEWING" -> REVIEWING;
      case "VALIDATING" -> VALIDATING;
      default -> WORKING;
    };
  }
}
