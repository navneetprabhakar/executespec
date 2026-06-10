# ExecuteSpec Workflows

> Current workflow catalog. For code locations and feature status, see `docs/features/`.

## 1. BUILD Run

```text
POST /api/runs
  -> validate request and tier/quota
  -> persist PLANNING run
  -> initialize workspace and repos
  -> classify spec
  -> plan
  -> AWAITING_APPROVAL
  -> approve
  -> execute tasks through WorkerAgent
  -> reviewer passes
  -> final validation/report/score
  -> PR/preview/background validation
```

Used for normal customer specs.

Key docs:

- [`features/run-execution.md`](features/run-execution.md)
- [`ARCHITECTURE.md`](ARCHITECTURE.md)

## 2. PATCH Run

PATCH mode is the thin-worker loop used when the desired fix scope is already known.

Used by:

- SWE-bench adapter.
- ProgramBench adapter.
- PR review autofix.
- Tech-debt category fix.
- CI-failure autofix.
- Code-review severity autofix.

PATCH mode skips classifier/planner/reviewer structure that would pollute small benchmark/autofix diffs.

## 3. Plan Gate

Production runs pause at `AWAITING_APPROVAL`.

Approval must confirm:

- plan acceptance,
- credit/cost confirmation,
- optional user cost cap.

Reject/cancel should not be overwritten by late worker/reviewer writes.

## 4. Project Setup

```text
Create project
  -> link repo(s)
  -> generate codemap
  -> generate per-repo architecture
  -> synthesize cross-repo architecture
  -> upload docs / generate docs
  -> run specs, chat, review, or tech-debt flows
```

See [`features/project-workbench.md`](features/project-workbench.md).

## 5. Project Chat

Project chat is grounded by:

- architecture doc,
- project memory snapshot,
- uploaded document RAG,
- generated docs,
- repo/run/PR listing tools,
- literal and semantic code search tools.

It is advisory unless a guarded action endpoint is invoked.

## 6. PR Review Workflow

```text
POST /api/pr-reviews
  -> fetch PR diff
  -> LLM review agent
  -> verdict + comments
  -> optional autofix comments
  -> PATCH run against PR head branch
```

## 7. Code Review Workflow

```text
POST /api/code-reviews
  -> clone/analyze repo
  -> Semgrep + LLM triage
  -> persist report/issues
  -> optional severity-bucket autofix
```

## 8. Tech Debt Fix Workflow

Tech-debt rollups come from validation report issues. Category-level fix dispatches a PATCH run through the common finding submitter.

## 9. Billing Workflow

```text
Select plan or top-up
  -> gateway checkout/order
  -> webhook or client verification
  -> subscription/top-up activation
  -> audit/payment history
```

Payment mutation must be tenant-scoped and idempotent.

## 10. Benchmark Workflow

```text
External harness runs task
  -> ExecuteSpec agent attempts task
  -> official/external verifier scores
  -> harness publishes event
  -> BenchmarkTrackingService stores and broadcasts
  -> /benchmark dashboard displays progress
```

See [`features/benchmarks.md`](features/benchmarks.md).
