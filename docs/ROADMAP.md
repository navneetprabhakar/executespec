# ExecuteSpec Roadmap

> Product-line roadmap refreshed 2026-06-19. Detailed open-work inventory lives in
> [`pending.md`](pending.md); shipped feature truth lives in `docs/features/`.

## Strategic End State

ExecuteSpec should become a combined software foundry: a platform that helps teams
develop, deploy, govern, observe, and maintain software end to end.

The current product is the coding and PR-generation core. The released terminal
CLI extends that core into the developer's local terminal. The next roadmap
expands the same agent/tool/evidence platform into three additional product
lines.

## Product Lines

### 1. ExecuteSpec Core

Current wedge: spec-driven engineering work that ends in a reviewable PR with
plan approval, cost visibility, tool traces, build/test/reviewer evidence, and a
verified score.

Near-term priorities:

- keep the released terminal CLI working as the primary developer-local surface,
- prove payment sandbox flows end to end,
- complete benchmark event hooks and publish only scorer-backed artifacts,
- improve preview/verifyBoot reliability,
- harden tenant isolation, cancellation behavior, and enterprise proof material.

### 2. Data Pipeline Builder

Separate product feature for creating and operating data pipelines across
structured and unstructured sources.

Target sources include SQL databases, NoSQL systems, warehouses, lakehouses,
HDFS, Apache Iceberg, Databricks, Snowflake, and adjacent enterprise stores.
ExecuteSpec should own the compute and orchestration layer; the customer owns
source and destination storage by default. A managed or partner-backed
destination can be offered as a priced add-on.

Required capabilities:

- connector registry for source and destination systems,
- schema discovery, profiling, and drift detection,
- pipeline planning, transformation generation, and validation,
- batch and incremental execution,
- lineage, governance, access controls, and audit trail,
- analysis layer for slice-and-dice exploration, visualization, and insights on
  processed data.

This is the largest expansion because connector depth, data governance, and
runtime reliability will matter more than demo breadth.

### 3. DevOps, Deployment, And Governance Agent

Distinct product line inside the current ExecuteSpec product. It should use the
same terminal/web agent foundation, but focus on operational software delivery:
Terraform, Docker, deployment workflows, release governance, environment drift,
policy checks, infrastructure PRs, and controlled command execution.

Required capabilities:

- infra-as-code authoring and review,
- Docker/build/deploy workflow automation,
- cloud/environment drift detection,
- policy and standards checks before deploy,
- evidence-backed deployment runbooks,
- guarded terminal/web execution with approval boundaries.

This is the closest adjacency to the current codebase because previews,
deployment, standards, contracts, terminal tools, and PR workflows already exist.

### 4. Virtual SRE

AI observability and remediation product line. The SRE agent monitors logs,
metrics, traces, alerts, and service health, identifies likely causes, raises
issues, and hands confirmed remediation work back to ExecuteSpec Core to create
a PR for human review.

Required capabilities:

- observability integrations for metrics, logs, traces, alerts, and incidents,
- automated triage, clustering, and RCA,
- issue creation with evidence and blast-radius context,
- remediation-plan generation,
- PR creation through the current ExecuteSpec run pipeline,
- post-incident summaries and recurring-problem tracking.

This line should not become an auto-mitigation system before issue evidence,
approval boundaries, and rollback posture are strong.

## Shared Platform Bets

All product lines should reuse the same primitives:

- org/RBAC/billing/BYOK controls,
- project graph, repo context, docs, memory, and RAG,
- terminal/web tool runtime,
- agent-session protocol,
- model routing and cost ledger,
- verification evidence ledger,
- PR and issue lifecycle,
- connector and integration registry.

The strategic advantage is not four disconnected products. It is one control
plane where build, deploy, observe, and remediate workflows share context and
governance.

## Suggested Sequencing

1. **Core + CLI stabilization** - treat terminal CLI as released, then keep
   server protocol, local-hands, billing, and benchmark proof tight.
2. **DevOps/Governance agent** - build first because it is closest to the
   current product and strengthens the foundry narrative.
3. **Virtual SRE** - build next so production incidents can become evidence-rich
   ExecuteSpec remediation PRs.
4. **Data Pipeline Builder** - design in parallel, but build after connector and
   governance architecture are scoped; it is the broadest product line.

## Active Execution Backlog

The narrower engineering backlog remains:

- authenticated model-matrix UI/API smoke,
- VM-side Chinese/open-model latency A/B,
- real Stripe/Razorpay sandbox flows,
- real SCM PATCH autofix run after cancellation hardening,
- benchmark event hooks from live harness runs into `/benchmark`,
- preview Phase 0 and verifyBoot,
- verify-before-complete Tiers 2-3,
- context compaction and latency work,
- C3 tenant ORM read-isolation,
- dead frontend branch cleanup.

## Cleanup Rule

Before deleting any plan, check
[`plan-disposition-2026-06-10.md`](plan-disposition-2026-06-10.md). Shipped
behavior belongs in `docs/features/`; unfinished work belongs in `pending.md` or
an active plan.
