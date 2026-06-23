# Investor Deck Outline

> Working deck skeleton, refreshed 2026-06-19. Use this to build the actual presentation after the billing and positioning brainstorm. Keep benchmark and compliance claims source-backed.

## 1. Title

ExecuteSpec: the software foundry for building and maintaining software with AI agents.

Suggested subtitle: Plan approved up front. Code generated in the repo. Build/test/review evidence attached to every PR. The same platform expands into deploy, govern, observe, remediate, and data-pipeline workflows.

## 2. Problem

AI coding tools help engineers type faster, but teams still struggle to trust AI-generated changes enough to merge them:

- most agents optimize for diff generation, not merge readiness,
- cost is opaque until after tokens are burned,
- multi-repo context and team governance are weak,
- verification is often "the model says it is done" instead of build/test/review evidence.

## 3. Product

ExecuteSpec today is a delivery pipeline:

1. User submits a natural-language spec.
2. System creates a task plan and cost estimate.
3. User approves the plan gate.
4. Worker executes tasks through controlled tools.
5. Reviewer/build/test gates produce a verified score.
6. System opens or updates a PR with evidence.

Positioning: not an autocomplete tool and not a free-form IDE clone. It is an async control plane for delegating bounded engineering work.

Strategic positioning: ExecuteSpec becomes a software foundry. The coding core
generates verified PRs; the released terminal CLI brings the agent into the
developer's local terminal; the same agent/tool/evidence platform expands into
DevOps/governance, Virtual SRE, and data-pipeline products.

## 4. Why Now

- Coding models are good enough to produce meaningful multi-file changes.
- The bottleneck has moved from generation to trust, verification, and cost control.
- Teams need governance around AI-authored PRs: who approved, what changed, which tests passed, and what it cost.
- Open model and BYOK ecosystems create room for a platform layer that routes work and exposes economics instead of hiding them.

## 5. Differentiation

| Existing pattern | Gap | ExecuteSpec response |
| --- | --- | --- |
| IDE copilots | Engineer still drives every step | Async spec-to-PR workflow. |
| Autonomous agents | Opaque plan and spend | Approval gate before execution cost. |
| Chat over repo | Weak merge evidence | Verified score from build, tests, reviewer, and critical issues. |
| One-repo assistants | Poor system context | Project workspace, architecture docs, RAG, memory, multi-repo PRs. |
| Managed-only AI SaaS | Gross margin risk | BYOK Team/Enterprise with zero provider markup. |

## 6. Product Surface

- Build-mode run pipeline: classify, plan, approve, execute, review, validate, PR.
- Patch-mode autofix: PR review comments, tech debt, CI failure, code review findings.
- Project workbench: repos, architecture docs, generated docs, RAG documents, project memory, chat, tech debt, contracts, standards.
- Terminal CLI: released and working, with server brain plus local hands through the agent-session/local-execution protocol.
- Benchmarks and operator dashboard: tracking layer exists, publishable claims require external scorer artifacts.

## 7. Expansion Roadmap

| Product line | Role in the software foundry |
| --- | --- |
| ExecuteSpec Core | Converts specs into verified PRs with approval, cost, and evidence controls. |
| DevOps/Governance Agent | Terminal/web agent for Terraform, Docker, deploy workflows, environment drift, policy checks, and governed operations. |
| Virtual SRE | Monitors logs/metrics/traces/alerts, raises evidence-backed issues, and routes remediation into ExecuteSpec PRs. |
| Data Pipeline Builder | Builds ETL/ELT pipelines across SQL, NoSQL, warehouses, lakehouses, HDFS, Iceberg, Databricks, Snowflake, and unstructured sources, with an analysis layer on top. |

Important boundary: for data pipelines, ExecuteSpec owns compute and orchestration. Customers own source and destination storage by default; a managed destination can be sold separately.

## 8. Business Model

Current implemented ladder:

| Tier | Price | Usage model |
| --- | --- | --- |
| Free | $0 | 15 runs/month, small specs, $3/run cap. |
| Pro | $29/month or $289/year | 30 managed-key runs/month, $5/run cap, BYOK can lift cap. |
| Team | $39/seat/month | 1-20 seats, BYOK, unlimited runs against customer provider quota, zero provider markup. |
| Enterprise | Custom | Contract, DPA/SLA path, org model matrix, aggregator/provider lanes. |

Investor framing: the main margin-safe path is Team/Enterprise BYOK. Pro is acquisition plus solo monetization, but it needs active cost monitoring because managed LLM spend can compress margin.

## 9. Early Proof

Use only currently verifiable artifacts:

- Codebase contains production-shaped run pipeline, project workbench, billing, BYOK, and validation/reporting paths.
- Validation report includes evidence ledger; score is not pure LLM self-rating.
- Terminal-Bench and SWE-bench work exists, but quote only official scorer-backed numbers when fresh artifacts are available.
- Product claim discipline is documented in `docs/launch-readiness-2026-06-10.md` and `docs/business/billing-plan-assessment.md`.

## 10. Wedge

Recommended wedge for brainstorming:

- start with teams that already use AI coding tools but still require human PR review,
- sell verified PR throughput and cost control, not "replace engineers",
- land with Team BYOK for margin safety,
- use Pro as a low-friction entry point for serious solo developers and champions.

## 11. Roadmap

Near-term gates:

- prove payment sandbox flows end to end,
- keep the released terminal CLI stable and evidence-backed,
- spec the DevOps/Governance product line first because it is closest to the current platform,
- scope Virtual SRE around evidence-backed issues that feed ExecuteSpec remediation PRs,
- design the Data Pipeline Builder connector/governance architecture before building broad connector coverage,
- complete benchmark event hooks and publish only scorer-backed artifacts,
- improve preview/verifyBoot reliability,
- harden tenant isolation and enterprise proof pack.

## 12. Ask

Decide after brainstorm:

- funding amount and runway,
- whether the round story is benchmark-led, enterprise-pilot-led, or developer-launch-led,
- whether pricing stays as-is or shifts toward a more explicit "platform fee + customer provider spend" model.

## Slide Claims To Avoid

- "SOC 2 certified"
- "SAML login enforced"
- "official leaderboard rank" unless a current official artifact exists
- "unlimited managed AI runs"
- "fully autonomous merge"
- "dedicated tenant region" unless contract delivery is scoped
