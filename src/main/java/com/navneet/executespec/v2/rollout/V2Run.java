package com.navneet.executespec.v2.rollout;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.navneet.executespec.v2.agent.Plan;
import com.navneet.executespec.v2.harness.ExecutionResult;
import com.navneet.executespec.v2.reviewer.ReviewerResult;

/**
 * In-memory V2 run record. Mutable — the background pipeline thread transitions it through the
 * status lifecycle while the API exposes snapshots to pollers.
 *
 * <p>Phase 9 baseline — in-memory only. DB persistence + SSE event stream land in later polish
 * passes.
 */
public class V2Run {

  public enum Status {
    /** Run has been submitted. Planner hasn't started yet. */
    SUBMITTED,
    /** Planner is generating the plan. */
    PLANNING,
    /** Plan ready, executor is running tasks. */
    EXECUTING,
    /** Executor finished, reviewer passes are running. */
    REVIEWING,
    /** Pipeline completed — reviewer verdict available. */
    COMPLETE,
    /** Pipeline failed at some phase (planner error, executor ABORTED, or reviewer failed). */
    FAILED
  }

  private final String runId;
  private final String spec;
  private final String provider;
  private final String model;
  private final String classification;
  private final Instant submittedAt;

  private volatile Status status = Status.SUBMITTED;
  private volatile Instant statusUpdatedAt;
  private volatile Plan plan;
  private volatile ExecutionResult executionResult;
  private volatile ReviewerResult reviewerResult;
  private volatile String failureMessage;
  private volatile Instant completedAt;

  /** Ordered status-transition log for the polling API + post-hoc debugging. */
  private final List<StatusEvent> statusLog = new CopyOnWriteArrayList<>();

  public V2Run(String runId, String spec, String provider, String model, String classification) {
    this.runId = runId;
    this.spec = spec;
    this.provider = provider;
    this.model = model;
    this.classification = classification;
    this.submittedAt = Instant.now();
    this.statusUpdatedAt = this.submittedAt;
    this.statusLog.add(new StatusEvent(Status.SUBMITTED, this.submittedAt, null));
  }

  public synchronized void transition(Status next, String note) {
    this.status = next;
    this.statusUpdatedAt = Instant.now();
    this.statusLog.add(new StatusEvent(next, this.statusUpdatedAt, note));
    if (next == Status.COMPLETE || next == Status.FAILED) {
      this.completedAt = this.statusUpdatedAt;
    }
  }

  // ─── getters / setters ──────────────────────────────────────────────────

  public String runId() {
    return runId;
  }

  public String spec() {
    return spec;
  }

  public String provider() {
    return provider;
  }

  public String model() {
    return model;
  }

  public String classification() {
    return classification;
  }

  public Instant submittedAt() {
    return submittedAt;
  }

  public Status status() {
    return status;
  }

  public Instant statusUpdatedAt() {
    return statusUpdatedAt;
  }

  public Plan plan() {
    return plan;
  }

  public ExecutionResult executionResult() {
    return executionResult;
  }

  public ReviewerResult reviewerResult() {
    return reviewerResult;
  }

  public String failureMessage() {
    return failureMessage;
  }

  public Instant completedAt() {
    return completedAt;
  }

  public List<StatusEvent> statusLog() {
    return List.copyOf(statusLog);
  }

  public void setPlan(Plan plan) {
    this.plan = plan;
  }

  public void setExecutionResult(ExecutionResult r) {
    this.executionResult = r;
  }

  public void setReviewerResult(ReviewerResult r) {
    this.reviewerResult = r;
  }

  public void setFailureMessage(String msg) {
    this.failureMessage = msg;
  }

  public record StatusEvent(Status status, Instant at, String note) {}
}
